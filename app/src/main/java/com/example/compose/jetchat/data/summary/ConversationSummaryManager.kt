package com.example.compose.jetchat.data.summary

import com.example.compose.jetchat.data.api.ApiService
import com.example.compose.jetchat.data.database.ChatDao
import com.example.compose.jetchat.data.database.SessionSummaryDao
import com.example.compose.jetchat.data.database.SessionSummaryEntity
import com.example.compose.jetchat.ui.chat.ChatMessage
import com.example.compose.jetchat.ui.chat.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对话摘要管理器
 * 负责智能压缩历史对话，保持上下文理解的同时避免 token 超限
 */
class ConversationSummaryManager(
    private val chatDao: ChatDao,
    private val summaryDao: SessionSummaryDao,
    private val apiService: ApiService
) {
    
    companion object {
        // 每隔 10 轮对话触发一次摘要
        const val SUMMARY_INTERVAL = 10
        
        // 保留最近 6 轮对话
        const val RECENT_MESSAGES_COUNT = 6
        
        // 摘要提示词
        const val SUMMARY_SYSTEM_PROMPT = """
你是一个专业的对话摘要助手。请将以下对话压缩成简洁的摘要，保留关键信息：
1. 用户的主要问题和需求
2. 重要的上下文信息
3. AI 提供的关键答案和建议
4. 任何需要记住的偏好或设置

摘要应该简短（100-200字），但要包含足够的信息让 AI 在后续对话中理解上下文。
"""
    }
    
    /**
     * 检查是否需要生成摘要
     * @return true 表示需要生成摘要
     */
    suspend fun shouldGenerateSummary(sessionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val messages = chatDao.getMessagesBySessionId(sessionId)
            val summary = summaryDao.getSummary(sessionId)
            
            if (summary == null) {
                // 第一次：超过 10 条消息就生成摘要
                messages.size >= SUMMARY_INTERVAL
            } else {
                // 后续：距离上次摘要又超过 10 条消息
                val newMessagesCount = messages.count { it.id > summary.lastSummarizedMessageId }
                newMessagesCount >= SUMMARY_INTERVAL
            }
        }
    }
    
    /**
     * 生成对话摘要
     */
    suspend fun generateSummary(sessionId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("SummaryManager", "开始生成摘要：$sessionId")
                
                val messages = chatDao.getMessagesBySessionId(sessionId)
                val existingSummary = summaryDao.getSummary(sessionId)
                
                // 确定需要摘要的消息范围
                val messagesToSummarize = if (existingSummary == null) {
                    // 第一次：摘要所有消息（除了最近 6 条）
                    if (messages.size > RECENT_MESSAGES_COUNT) {
                        messages.dropLast(RECENT_MESSAGES_COUNT)
                    } else {
                        return@withContext null  // 消息太少，不需要摘要
                    }
                } else {
                    // 后续：摘要上次之后的消息（除了最近 6 条）
                    val newMessages = messages.filter { it.id > existingSummary.lastSummarizedMessageId }
                    if (newMessages.size > RECENT_MESSAGES_COUNT) {
                        newMessages.dropLast(RECENT_MESSAGES_COUNT)
                    } else {
                        return@withContext null  // 新消息太少，不需要更新摘要
                    }
                }
                
                // 构建要摘要的对话文本
                val conversationText = buildConversationText(messagesToSummarize, existingSummary?.summary)
                
                // 调用 API 生成摘要
                val summaryResponse = apiService.sendSummaryRequest(conversationText)
                
                // 保存摘要
                val lastMessageId = messagesToSummarize.lastOrNull()?.id ?: 0
                val now = System.currentTimeMillis()
                
                val summaryEntity = SessionSummaryEntity(
                    sessionId = sessionId,
                    summary = summaryResponse,
                    lastSummarizedMessageId = lastMessageId,
                    createdAt = existingSummary?.createdAt ?: now,
                    updatedAt = now
                )
                
                summaryDao.insertOrUpdateSummary(summaryEntity)
                
                android.util.Log.d("SummaryManager", "摘要生成成功：${summaryResponse.take(100)}...")
                
                summaryResponse
            } catch (e: Exception) {
                android.util.Log.e("SummaryManager", "生成摘要失败", e)
                null
            }
        }
    }
    
    /**
     * 获取用于发送 API 的消息列表（包含摘要）
     */
    suspend fun getMessagesWithSummary(
        sessionId: String,
        newUserMessage: String
    ): List<Pair<String, String>> {  // Pair<role, content>
        return withContext(Dispatchers.IO) {
            val messages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
            val summary = summaryDao.getSummary(sessionId)
            
            val result = mutableListOf<Pair<String, String>>()
            
            // 1. 如果有摘要，使用摘要 + 最近消息
            if (summary != null) {
                result.add("system" to "以下是之前的对话摘要：\n${summary.summary}")
                
                // 2. 添加摘要之后的所有消息
                val recentMessages = messages.filter { it.id > summary.lastSummarizedMessageId }
                recentMessages.forEach {
                    result.add(it.role to it.content)
                }
            } else {
                // 没有摘要，添加所有历史消息（第 1-9 轮都会完整发送）
                messages.forEach {
                    result.add(it.role to it.content)
                }
            }
            
            // 3. 添加当前用户消息
            result.add("user" to newUserMessage)
            
            android.util.Log.d("SummaryManager", "发送消息数：${result.size}，有摘要：${summary != null}")
            
            result
        }
    }
    
    /**
     * 构建用于摘要的对话文本
     */
    private fun buildConversationText(
        messages: List<com.example.compose.jetchat.data.database.ChatMessageEntity>,
        previousSummary: String?
    ): String {
        val builder = StringBuilder()
        
        // 如果有之前的摘要，先加上
        if (previousSummary != null) {
            builder.append("【之前的摘要】\n")
            builder.append(previousSummary)
            builder.append("\n\n【新的对话】\n")
        }
        
        // 添加对话内容
        messages.forEach { msg ->
            val role = if (msg.role == "user") "用户" else "AI"
            builder.append("$role: ${msg.content}\n")
        }
        
        return builder.toString()
    }
}

/**
 * 发送摘要请求（ApiService 扩展）
 */
suspend fun ApiService.sendSummaryRequest(conversationText: String): String {
    return withContext(Dispatchers.IO) {
        try {
            // 使用聊天模型生成摘要
            val summaryPrompt = """
${ConversationSummaryManager.SUMMARY_SYSTEM_PROMPT}

对话内容：
$conversationText

请生成摘要：
"""
            
            val response = sendChatRequest(summaryPrompt, null)
            response.text
        } catch (e: Exception) {
            android.util.Log.e("ApiService", "生成摘要失败", e)
            throw e
        }
    }
}
