package com.example.compose.jetchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.compose.jetchat.data.api.ApiService
import com.example.compose.jetchat.data.database.ChatDao
import com.example.compose.jetchat.data.database.SessionSummaryDao
import com.example.compose.jetchat.data.database.toChatMessage
import com.example.compose.jetchat.data.database.toEntity
import com.example.compose.jetchat.data.summary.ConversationSummaryManager
import com.example.compose.jetchat.data.voice.VoiceRealtimeService
import com.example.compose.jetchat.data.voice.VoiceTTSService
import com.example.compose.jetchat.data.voice.CloudVoiceRecognizer
import com.example.compose.jetchat.data.voice.DoubaoRealtimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天 ViewModel
 * 
 * 注意：需要 Application context 用于本地语音识别
 */
class ChatViewModel(
    application: Application,
    private val sessionId: String,
    private val chatDao: ChatDao,
    private val summaryDao: SessionSummaryDao,
    private val isRealtimeMode: Boolean = false,
    private val apiService: ApiService = ApiService.instance
) : AndroidViewModel(application) {
    
    // 摘要管理器
    private val summaryManager = ConversationSummaryManager(chatDao, summaryDao, apiService)
    
    // 云端语音识别（类似微信、QQ、Kimi）
    private val cloudVoiceRecognizer = CloudVoiceRecognizer(application.applicationContext)
    
    // 语音服务
    private val voiceService = VoiceRealtimeService() // WebSocket 实时对话（目前不可用）
    private val voiceTTSService = VoiceTTSService(cloudVoiceRecognizer) // TTS 语音对话（推荐）
    
    // 豆包端到端实时对话服务
    private val doubaoRealtimeService = if (isRealtimeMode) {
        DoubaoRealtimeService(application.applicationContext)
    } else {
        null
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    // 语音对话状态
    private val _isVoiceRecording = MutableStateFlow(false)
    val isVoiceRecording: StateFlow<Boolean> = _isVoiceRecording.asStateFlow()
    
    val isVoiceRecognizing: StateFlow<Boolean> = cloudVoiceRecognizer.isRecognizing
    
    private val _voiceTranscription = MutableStateFlow("")
    val voiceTranscription: StateFlow<String> = _voiceTranscription.asStateFlow()
    
    // 语音对话模式
    private val _voiceMode = MutableStateFlow(com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME)  // 简单模式已禁用
    val voiceMode: StateFlow<com.example.compose.jetchat.config.AppConfig.VoiceMode> = _voiceMode.asStateFlow()
    
    // Snackbar 消息
    private val _snackbarMessage = MutableStateFlow("")
    val snackbarMessage: StateFlow<String> = _snackbarMessage.asStateFlow()
    
    // 🔴 AI 回复状态（用于显示停止按钮）
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    
    // 当前发送的 Job，用于终止对话
    private var currentSendJob: Job? = null

    private var messageIdCounter = 0L
    
    // 语音识别监听器的 Job，用于取消旧的监听器
    private var voiceRecognitionJob: Job? = null
    
    // 防抖：记录上次发送的语音内容，避免重复发送
    private var lastVoiceTranscription: String = ""

    init {
        // 从数据库加载历史消息
        loadMessagesFromDatabase()
    }

    /**
     * 从数据库加载消息
     */
    private fun loadMessagesFromDatabase() {
        viewModelScope.launch {
            try {
                android.util.Log.d("ChatViewModel", "开始加载会话消息: $sessionId")
                val entities = withContext(Dispatchers.IO) {
                    chatDao.getMessagesBySessionId(sessionId)
                }
                android.util.Log.d("ChatViewModel", "从数据库读取到 ${entities.size} 条消息")
                
                // 过滤掉系统占位消息
                val filteredMessages = entities
                    .filter { it.role != "system" }
                    .map { it.toChatMessage() }
                
                android.util.Log.d("ChatViewModel", "过滤后有 ${filteredMessages.size} 条消息")
                
                // 只有当数据库有消息或当前UI没有消息时才更新(避免覆盖正在进行的对话)
                if (filteredMessages.isNotEmpty() || _messages.value.isEmpty()) {
                    _messages.value = filteredMessages
                    android.util.Log.d("ChatViewModel", "消息已加载到UI")
                }
                
                // 更新消息 ID 计数器
                if (entities.isNotEmpty()) {
                    messageIdCounter = entities.maxOf { it.id } + 1
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "加载消息失败", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 强制重新加载消息(公开方法,供UI调用)
     */
    fun reloadMessages() {
        android.util.Log.d("ChatViewModel", "强制重新加载消息")
        loadMessagesFromDatabase()
    }

    /**
     * 停止当前对话
     */
    fun stopCurrentConversation() {
        android.util.Log.d("ChatViewModel", "用户终止对话")
        currentSendJob?.cancel()
        currentSendJob = null
        _isSending.value = false
        
        // 移除所有 LOADING 状态的消息
        _messages.value = _messages.value.filter { it.status != MessageStatus.LOADING }
    }
    
    /**
     * 发送消息
     */
    fun sendMessage(content: String, imageBase64: String? = null) {
        if (_isSending.value) {
            android.util.Log.w("ChatViewModel", "已有对话进行中，忽略发送")
            return
        }
        
        val userMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content,
            status = MessageStatus.SENT,
            imageBase64 = imageBase64
        )

        // 立即添加用户消息（追加到末尾）
        _messages.value = _messages.value + listOf(userMessage)

        // 添加加载中的消息（追加到末尾）
        val loadingMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.LOADING
        )
        _messages.value = _messages.value + listOf(loadingMessage)

        // 🔴 设置发送状态
        _isSending.value = true
        
        // 调用 API
        currentSendJob = viewModelScope.launch {
            try {
                // 调用 API（使用带摘要的多轮对话）
                val apiResponse = withContext(Dispatchers.IO) {
                    // 获取包含摘要的消息历史
                    val conversationHistory = summaryManager.getMessagesWithSummary(sessionId, content)
                    
                    // 发送多轮对话请求，传入当前用户输入用于判断是否生成图片
                    apiService.sendChatRequestWithHistory(conversationHistory, content, imageBase64)
                }
                
                // 🎙️ 实时对话模式：获取文本回复后，调用TTS转语音
                if (isRealtimeMode && apiResponse.text.isNotEmpty()) {
                    launch {
                        try {
                            android.util.Log.d("ChatViewModel", "🎙️ 实时模式: 调用TTS转语音")
                            val ttsAudioFile = withContext(Dispatchers.IO) {
                                voiceTTSService.textToSpeech(apiResponse.text)
                            }
                            if (ttsAudioFile != null) {
                                android.util.Log.d("ChatViewModel", "✓ TTS生成成功: ${ttsAudioFile.absolutePath}")
                            } else {
                                android.util.Log.w("ChatViewModel", "⚠ TTS生成失败，返回null")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "TTS失败: ${e.message}")
                        }
                    }
                }
                
                // 检查是否需要生成摘要（后台异步进行，不阻塞）
                launch {
                    if (summaryManager.shouldGenerateSummary(sessionId)) {
                        android.util.Log.d("ChatViewModel", "触发摘要生成...")
                        summaryManager.generateSummary(sessionId)
                    }
                }

                // 移除加载中的消息
                _messages.value = _messages.value.filter { it.id != loadingMessage.id }

                // 创建 AI 消息（初始为空）
                val aiMessageId = messageIdCounter++
                val aiMessage = ChatMessage(
                    id = aiMessageId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    imageBase64 = apiResponse.imageBase64,  // 先显示图片（如果有）
                    status = MessageStatus.SENT
                )
                _messages.value = _messages.value + listOf(aiMessage)
                
                // 打字机效果：逐字显示文本，并实时保存到数据库
                val fullText = apiResponse.text
                val typingSpeed = 30L  // 每个字延迟 30ms
                
                // 🔥 先保存用户消息到数据库（确保退出不丢失）
                val imageDesc = if (imageBase64 != null && apiResponse.text.isNotEmpty()) {
                    apiResponse.text.take(200)
                } else if (apiResponse.imageBase64 != null) {
                    content.take(200)
                } else {
                    null
                }
                
                val finalUserMessage = userMessage.copy(imageDescription = imageDesc)
                
                withContext(Dispatchers.IO) {
                    val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                    val shouldSetNewTitle = existingMessages.isEmpty() || existingMessages.first().sessionTitle == "新对话"
                    
                    val sessionTitle = if (shouldSetNewTitle) content.take(15) else existingMessages.first().sessionTitle
                    val isPinned = if (shouldSetNewTitle) false else existingMessages.first().isPinned
                    
                    chatDao.insertMessage(finalUserMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = isPinned))
                    
                    if (shouldSetNewTitle && existingMessages.isNotEmpty()) {
                        chatDao.updateSessionTitle(sessionId, sessionTitle)
                    }
                }
                
                // 打字动画
                fullText.forEachIndexed { index, _ ->
                    kotlinx.coroutines.delay(typingSpeed)
                    val currentText = fullText.substring(0, index + 1)
                    
                    // 更新消息内容
                    _messages.value = _messages.value.map { msg ->
                        if (msg.id == aiMessageId) {
                            msg.copy(content = currentText)
                        } else {
                            msg
                        }
                    }
                    
                    // 🔥 每50个字符保存一次到数据库（防止退出丢失）
                    if ((index + 1) % 50 == 0 || index == fullText.length - 1) {
                        val tempAiMessage = _messages.value.find { it.id == aiMessageId }
                        if (tempAiMessage != null) {
                            withContext(Dispatchers.IO) {
                                val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                                val sessionTitle = existingMessages.firstOrNull()?.sessionTitle ?: content.take(15)
                                val isPinned = existingMessages.firstOrNull()?.isPinned ?: false
                                
                                chatDao.insertMessage(tempAiMessage.copy(
                                    imageDescription = if (apiResponse.imageBase64 != null) content.take(200) else null
                                ).toEntity().copy(sessionTitle = sessionTitle, isPinned = isPinned))
                            }
                        }
                    }
                }
                
                // 🔥 最终保存完整消息
                android.util.Log.d("ChatViewModel", "打字完成，保存最终消息")
                
            } catch (e: Exception) {
                e.printStackTrace()
                
                // 移除加载中的消息
                _messages.value = _messages.value.filter { it.id != loadingMessage.id }

                // 更新用户消息为错误状态
                _messages.value = _messages.value.map {
                    if (it.id == userMessage.id) {
                        it.copy(status = MessageStatus.ERROR)
                    } else {
                        it
                    }
                }
            } finally {
                // 🔴 重置发送状态
                _isSending.value = false
                currentSendJob = null
            }
        }
    }

    /**
     * 重试发送消息
     */
    fun retryMessage(message: ChatMessage) {
        if (message.status == MessageStatus.ERROR && message.role == MessageRole.USER) {
            // 移除错误的消息
            _messages.value = _messages.value.filter { it.id != message.id }
            // 重新发送
            sendMessage(message.content)
        }
    }
    
    /**
     * 发送包含文档的消息
     */
    fun sendMessageWithDocument(content: String, documentName: String?, documentContent: String?) {
        if (documentContent == null) {
            sendMessage(content)
            return
        }
        
        // 创建包含文档的用户消息
        val userMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content.ifBlank { "请分析这个文档的内容" },
            status = MessageStatus.SENT,
            documentName = documentName,
            documentContent = documentContent
        )
        
        // 立即添加用户消息
        _messages.value = _messages.value + listOf(userMessage)
        
        // 保存到数据库
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                chatDao.insertMessage(userMessage.toEntity())
            }
        }
        
        // 创建 AI 加载消息
        val aiLoadingMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.LOADING
        )
        _messages.value = _messages.value + listOf(aiLoadingMessage)
        
        // 请求 AI 回复（发送文档内容作为上下文）
        viewModelScope.launch {
            try {
                // 解析文档内容
                val (fileType, actualContent) = if (documentContent.contains(":")) {
                    val parts = documentContent.split(":", limit = 2)
                    parts[0] to parts.getOrNull(1)
                } else {
                    "UNKNOWN" to documentContent
                }
                
                // 构造发送给 AI 的消息内容
                val messageToAI = when (fileType) {
                    "TEXT" -> {
                        // TXT文件直接包含文本内容
                        if (content.isNotBlank()) {
                            "文档名: $documentName\n\n文档内容:\n$actualContent\n\n用户问题: $content"
                        } else {
                            "文档名: $documentName\n\n文档内容:\n$actualContent\n\n请分析这个文档的内容。"
                        }
                    }
                    "PDF_BASE64", "DOC_BASE64", "FILE_BASE64" -> {
                        // 二进制文件，提醒AI我们提供了base64数据
                        val fileTypeDesc = when (fileType) {
                            "PDF_BASE64" -> "PDF文档"
                            "DOC_BASE64" -> "Word文档"
                            else -> "文件"
                        }
                        if (content.isNotBlank()) {
                            "文档名: $documentName ($fileTypeDesc)\n\n文档Base64数据:\n$actualContent\n\n用户问题: $content\n\n请根据文档内容回答用户问题。注意：上面是Base64编码的${fileTypeDesc}内容。"
                        } else {
                            "文档名: $documentName ($fileTypeDesc)\n\n文档Base64数据:\n$actualContent\n\n请分析这个${fileTypeDesc}的内容。注意：上面是Base64编码的文档内容，你需要解码后分析。"
                        }
                    }
                    else -> {
                        // 兼容旧的格式
                        if (content.isNotBlank()) {
                            "[$documentName]\n\n$content"
                        } else {
                            "[$documentName]\n\n请分析这个文档的内容"
                        }
                    }
                }
                
                // 调用 API（使用带摘要的多轮对话）
                val apiResponse = withContext(Dispatchers.IO) {
                    // 获取包含摘要的消息历史
                    val conversationHistory = summaryManager.getMessagesWithSummary(sessionId, messageToAI)
                    
                    // 发送多轮对话请求
                    apiService.sendChatRequestWithHistory(conversationHistory, messageToAI, null)
                }
                
                // 移除加载消息
                _messages.value = _messages.value.filter { it.id != aiLoadingMessage.id }
                
                // 创建 AI 消息
                val aiMessageId = messageIdCounter++
                val aiMessage = ChatMessage(
                    id = aiMessageId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    imageBase64 = apiResponse.imageBase64,
                    status = MessageStatus.SENT
                )
                _messages.value = _messages.value + listOf(aiMessage)
                
                // 打字机效果：逐字显示文本
                val fullText = apiResponse.text
                val typingSpeed = 30L
                
                fullText.forEachIndexed { index, _ ->
                    kotlinx.coroutines.delay(typingSpeed)
                    val currentText = fullText.substring(0, index + 1)
                    
                    _messages.value = _messages.value.map { msg ->
                        if (msg.id == aiMessageId) {
                            msg.copy(content = currentText)
                        } else {
                            msg
                        }
                    }
                }
                
                // 保存到数据库
                val finalAiMessage = _messages.value.find { it.id == aiMessageId }!!
                withContext(Dispatchers.IO) {
                    chatDao.insertMessage(userMessage.toEntity())
                    chatDao.insertMessage(finalAiMessage.toEntity())
                }
                
                // 检查是否需要生成摘要
                launch {
                    if (summaryManager.shouldGenerateSummary(sessionId)) {
                        summaryManager.generateSummary(sessionId)
                    }
                }
                
            } catch (e: Exception) {
                // 错误处理
                val errorMessage = aiLoadingMessage.copy(
                    content = "发送失败: ${e.message}",
                    status = MessageStatus.ERROR
                )
                
                _messages.value = _messages.value.map { 
                    if (it.id == aiLoadingMessage.id) errorMessage else it 
                }
            }
        }
    }
    
    /**
     * 开始语音录音
     * 
     * 根据当前模式选择：
     * - SIMPLE: 使用 Whisper 语音识别（录音 → 识别 → 文字）
     * - REALTIME: 使用实时语音对话（音频流 → 音频流）
     */
    fun startVoiceRecording() {
        android.util.Log.d("ChatViewModel", "开始语音录音，模式: ${_voiceMode.value}")
        
        // 清空上次记录，为新的识别做准备
        lastVoiceTranscription = ""
        
        viewModelScope.launch {
            _isVoiceRecording.value = true
            
            // 根据模式选择不同的语音服务
            // 🔥 简单模式已禁用，仅使用实时模式
            android.util.Log.d("ChatViewModel", "使用实时模式：端到端语音对话")
            startRealtimeVoiceConversation()
        }
    }
    
    /**
     * 切换语音对话模式
     */
    fun toggleVoiceMode() {
        _voiceMode.value = when (_voiceMode.value) {
            com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> 
                com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME
            com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> 
                com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE
        }
        
        val modeName = when (_voiceMode.value) {
            com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> "简单模式（语音识别）"
            com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> "实时模式（端到端对话）"
        }
        _snackbarMessage.value = "已切换到：$modeName"
        
        android.util.Log.d("ChatViewModel", "语音模式已切换: ${_voiceMode.value}")
    }
    
    /**
     * 启动豆包端到端实时对话
     */
    fun startDoubaoRealtimeConversation(
        botName: String = "豆包",
        systemRole: String = "",
        speakingStyle: String = ""
    ) {
        // 取消之前的监听器，避免重复发送
        voiceRecognitionJob?.cancel()
        
        voiceRecognitionJob = viewModelScope.launch {
            doubaoRealtimeService?.let { service ->
                android.util.Log.d("ChatViewModel", "启动豆包端到端实时对话")
                
                service.startRealtimeConversation(botName, systemRole, speakingStyle)
                
                try {
                    // 使用coroutineScope确保所有子协程在父协程取消时一起取消
                    kotlinx.coroutines.coroutineScope {
                        // 监听录音状态
                        launch {
                            service.isRecording.collect { isRecording ->
                                _isVoiceRecording.value = isRecording
                            }
                        }
                        
                        // 监听连接状态
                        launch {
                            service.connectionState.collect { state ->
                                _snackbarMessage.value = state
                            }
                        }
                        
                        // 监听ASR识别结果（用户说话），直接渲染为用户消息
                        launch {
                    service.userSpeechCompleted.collect { finalText ->
                        if (finalText != null && finalText.isNotEmpty() && finalText != lastVoiceTranscription) {
                            lastVoiceTranscription = finalText
                            android.util.Log.d("ChatViewModel", "✓ 豆包-用户说话: $finalText")
                            
                            // 添加用户消息气泡（右边）
                            val userMessage = ChatMessage(
                                id = messageIdCounter++,
                                sessionId = sessionId,
                                role = MessageRole.USER,
                                content = finalText,
                                status = MessageStatus.SENT
                            )
                            _messages.value = _messages.value + listOf(userMessage)
                            
                            // 保存到数据库
                            viewModelScope.launch {
                                withContext(Dispatchers.IO) {
                                    val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                                    val shouldSetNewTitle = existingMessages.isEmpty() || existingMessages.first().sessionTitle == "新对话"
                                    
                                    if (shouldSetNewTitle) {
                                        val sessionTitle = "🎙️ ${finalText.take(10)}"
                                        chatDao.insertMessage(userMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = false))
                                        if (existingMessages.isNotEmpty()) {
                                            chatDao.updateSessionTitle(sessionId, sessionTitle)
                                        }
                                    } else {
                                        val firstMessage = existingMessages.first()
                                        chatDao.insertMessage(userMessage.toEntity().copy(
                                            sessionTitle = firstMessage.sessionTitle,
                                            isPinned = firstMessage.isPinned
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
                
                        // 监听AI回复，直接渲染为AI消息
                        launch {
                            service.aiResponseCompleted.collect { aiText ->
                                if (aiText != null && aiText.isNotEmpty()) {
                                    android.util.Log.d("ChatViewModel", "✓ 豆包-AI回复: $aiText")
                                    
                                    // 添加AI消息气泡（左边），带打字机效果
                                    val aiMessageId = messageIdCounter++
                                    val aiMessage = ChatMessage(
                                        id = aiMessageId,
                                        sessionId = sessionId,
                                        role = MessageRole.ASSISTANT,
                                        content = "",
                                        status = MessageStatus.SENT
                                    )
                                    _messages.value = _messages.value + listOf(aiMessage)
                                    
                                    // 打字机效果
                                    val typingSpeed = 30L
                                    aiText.forEachIndexed { index, _ ->
                                        kotlinx.coroutines.delay(typingSpeed)
                                        val currentText = aiText.substring(0, index + 1)
                                        _messages.value = _messages.value.map { msg ->
                                            if (msg.id == aiMessageId) msg.copy(content = currentText) else msg
                                        }
                                    }
                                    
                                    // 保存到数据库
                                    val finalAiMessage = _messages.value.find { it.id == aiMessageId }!!.copy(content = aiText)
                                    withContext(Dispatchers.IO) {
                                        val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                                        val firstMessage = existingMessages.first()
                                        chatDao.insertMessage(finalAiMessage.toEntity().copy(
                                            sessionTitle = firstMessage.sessionTitle,
                                            isPinned = firstMessage.isPinned
                                        ))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.d("ChatViewModel", "豆包实时对话协程已取消")
                    throw e // 重新抛出以便正确处理取消
                }
            } ?: run {
                _snackbarMessage.value = "当前不是实时对话模式"
            }
        }
    }
    
    /**
     * 停止豆包端到端实时对话
     */
    fun stopDoubaoRealtimeConversation() {
        android.util.Log.d("ChatViewModel", "停止豆包端到端实时对话")
        
        // 取消协程监听器
        voiceRecognitionJob?.cancel()
        voiceRecognitionJob = null
        
        // 清理上次转录文本
        lastVoiceTranscription = ""
        
        // 停止服务
        doubaoRealtimeService?.stopRealtimeConversation()
        
        // 重置录音状态
        _isVoiceRecording.value = false
    }
    
    /**
     * 启动实时语音对话（REALTIME 模式 - 使用 TTS）
     */
    private fun startRealtimeVoiceConversation() {
        viewModelScope.launch {
            android.util.Log.d("ChatViewModel", "使用 TTS 模式：Whisper → Chat → TTS → 播放")
            
            // 启动录音
            voiceTTSService.startVoiceConversation()
            
            // 监听录音状态
            launch {
                voiceTTSService.isRecording.collect { isRecording ->
                    _isVoiceRecording.value = isRecording
                }
            }
            
            // 监听错误
            launch {
                voiceTTSService.error.collect { error ->
                    if (error != null) {
                        _snackbarMessage.value = error
                        android.util.Log.e("ChatViewModel", "TTS 语音对话错误: $error")
                    }
                }
            }
            
            // 实时模式不需要监听转录文本（不填充到文本框）
            // 转录文本会直接显示在语音消息气泡中
        }
    }
    
    /**
     * 启动云端语音识别（类似微信、QQ、Kimi）
     * 
     * 不依赖设备本地语音识别引擎，直接调用云端 Whisper API
     */
    private fun startCloudVoiceRecognition() {
        // 取消之前的监听器，避免重复发送
        voiceRecognitionJob?.cancel()
        
        voiceRecognitionJob = viewModelScope.launch {
            android.util.Log.d("ChatViewModel", "启动云端语音识别（Whisper API）...")
            
            // 开始录音
            cloudVoiceRecognizer.startRecording()
            
            // 监听录音状态
            launch {
                cloudVoiceRecognizer.isListening.collect { isListening ->
                    android.util.Log.d("ChatViewModel", "录音状态: $isListening")
                }
            }
            
            // 监听识别结果，直接发送
            launch {
                cloudVoiceRecognizer.transcription.collect { transcription ->
                    if (transcription.isNotEmpty() && transcription != lastVoiceTranscription) {
                        lastVoiceTranscription = transcription
                        _voiceTranscription.value = transcription
                        android.util.Log.d("ChatViewModel", "✓ 简单模式-识别结果: $transcription")
                        // 直接发送消息
                        sendMessage(transcription)
                        _voiceTranscription.value = ""
                    }
                }
            }
            
            // 监听错误
            launch {
                cloudVoiceRecognizer.error.collect { error ->
                    if (error != null) {
                        android.util.Log.e("ChatViewModel", "✗ 云端识别错误: $error")
                        _snackbarMessage.value = "语音识别失败: $error"
                        _isVoiceRecording.value = false
                    }
                }
            }
        }
    }
    
    /**
     * 停止语音录音
     */
    fun stopVoiceRecording() {
        android.util.Log.d("ChatViewModel", "停止语音录音，当前模式: ${_voiceMode.value}")
        
        // 🔥 防止重复调用
        if (!_isVoiceRecording.value) {
            android.util.Log.w("ChatViewModel", "录音未开始，忽略停止请求")
            return
        }
        
        // 🔥 立即设置为 false，防止重复触发
        _isVoiceRecording.value = false
        
        // 🔥 取消简单模式的监听器（防止重复发送消息）
        voiceRecognitionJob?.cancel()
        voiceRecognitionJob = null
        
        viewModelScope.launch {
            // 🔥 简单模式已禁用，仅使用实时模式
            run {
                    // 🎙️ 实时模式：先显示用户消息和加载动画
                    
                    // 1. 停止录音并触发云端识别
                    voiceTTSService.stopRecording()
                    cloudVoiceRecognizer.stopRecordingAndRecognize()
                    
                    // 2. 等待识别完成（最多10秒）
                    var waitCount = 0
                    while (cloudVoiceRecognizer.isRecognizing.value && waitCount < 100) {
                        kotlinx.coroutines.delay(100)
                        waitCount++
                    }
                    
                    // 3. 获取识别结果
                    val transcription = cloudVoiceRecognizer.getLastRecognitionResult()
                    if (transcription.isNullOrEmpty()) {
                        android.util.Log.w("ChatViewModel", "语音识别结果为空")
                        return@launch
                    }
                    
                    android.util.Log.d("ChatViewModel", "✓ 识别结果: $transcription")
                    
                    // 4. 立即显示用户消息（纯文本气泡，不显示语音）
                    val userMessage = ChatMessage(
                        id = messageIdCounter++,
                        sessionId = sessionId,
                        role = MessageRole.USER,
                        content = transcription,
                        status = MessageStatus.SENT
                        // 🔥 不保存语音文件路径，显示为普通文本消息
                    )
                    _messages.value = _messages.value + listOf(userMessage)
                    
                    // 5. 显示 AI 加载动画
                    val loadingMessage = ChatMessage(
                        id = messageIdCounter++,
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        content = "",
                        status = MessageStatus.LOADING
                    )
                    _messages.value = _messages.value + listOf(loadingMessage)
                    
                    // 6. 后台处理（意图识别 + 内容生成 + TTS）
                    _isSending.value = true
                    currentSendJob = launch {
                        try {
                            val voiceData = handleVoiceInput(transcription)
                            
                            // 🎙️ 调用 TTS 生成语音
                            var ttsAudioPath: String? = null
                            var ttsAudioDuration: Int = 0
                            
                            if (voiceData.text.isNotEmpty()) {
                                try {
                                    val ttsFile = withContext(Dispatchers.IO) {
                                        voiceTTSService.textToSpeech(voiceData.text)
                                    }
                                    if (ttsFile != null) {
                                        ttsAudioPath = ttsFile.absolutePath
                                        // 🔥 修复时长计算：字数 * 300ms（中文约3字/秒），转换为秒
                                        ttsAudioDuration = ((voiceData.text.length * 300) / 1000)
                                        android.util.Log.d("ChatViewModel", "✓ TTS生成成功: $ttsAudioPath, 时长: ${ttsAudioDuration}秒")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatViewModel", "TTS失败: ${e.message}")
                                }
                            }
                            
                            // 移除加载动画
                            _messages.value = _messages.value.filter { it.id != loadingMessage.id }
                            
                            // 添加 AI 回复
                            val aiMessage = ChatMessage(
                                id = messageIdCounter++,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = voiceData.text,
                                status = MessageStatus.SENT,
                                imageBase64 = voiceData.imageBase64,
                                audioFilePath = ttsAudioPath,
                                audioDuration = ttsAudioDuration
                            )
                            
                            android.util.Log.d("ChatViewModel", "✅ AI回复: 文本=${voiceData.text}, 图片=${if (voiceData.imageBase64 != null) "已包含(${voiceData.imageBase64.length}字符)" else "无"}, 语音=${ttsAudioPath ?: "无"}")
                            
                            _messages.value = _messages.value + listOf(aiMessage)
                            
                            // 保存到数据库
                            withContext(Dispatchers.IO) {
                                val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                                val shouldSetNewTitle = existingMessages.isEmpty() || existingMessages.first().sessionTitle == "新对话"
                                
                                val sessionTitle = if (shouldSetNewTitle) transcription.take(15) else existingMessages.first().sessionTitle
                                val isPinned = if (shouldSetNewTitle) false else existingMessages.first().isPinned
                                
                                chatDao.insertMessage(userMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = isPinned))
                                chatDao.insertMessage(aiMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = isPinned))
                                
                                if (shouldSetNewTitle && existingMessages.isNotEmpty()) {
                                    chatDao.updateSessionTitle(sessionId, sessionTitle)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "语音对话处理失败", e)
                            // 移除加载动画
                            _messages.value = _messages.value.filter { it.id != loadingMessage.id }
                            // 标记用户消息为错误
                            _messages.value = _messages.value.map {
                                if (it.id == userMessage.id) it.copy(status = MessageStatus.ERROR) else it
                            }
                        } finally {
                            _isSending.value = false
                            currentSendJob = null
                        }
                    }
            }
        }
    }
    
    /**
     * 处理语音输入（意图识别 + 内容生成）
     * 
     * @return VoiceResponseData 包含文本回复和可能的图片
     */
    private suspend fun handleVoiceInput(userInput: String): VoiceResponseData {
        return try {
            withContext(Dispatchers.IO) {
                // 1. 意图识别
                val intent = apiService.detectIntent(userInput)
                android.util.Log.d("ChatViewModel", "语音意图识别: $intent")
                
                when (intent) {
                    "image_generation" -> {
                        // 2a. 生成图片
                        android.util.Log.d("ChatViewModel", "检测到图片生成意图，直接生成图片")
                        
                        // 🔥 直接生成图片，不再调用 optimizeImagePrompt（会重复识别）
                        val imageUrl = apiService.generateImage(userInput)
                        val imageBase64 = apiService.downloadAndEncodeImage(imageUrl)
                        
                        // 返回图片 + 简短描述
                        VoiceResponseData(
                            text = "我为你生成了这张图片。",
                            imageBase64 = imageBase64
                        )
                    }
                    else -> {
                        // 2b. 普通对话（简洁回复）
                        android.util.Log.d("ChatViewModel", "普通对话模式")
                        
                        // 获取对话历史
                        val conversationHistory = summaryManager.getMessagesWithSummary(sessionId, userInput)
                        
                        // 添加语音对话专用的系统提示
                        val voiceSystemPrompt = """
                            你是一个语音助手，请用简洁、自然的口语回答用户问题。
                            要求：
                            1. 回答要简洁，控制在2-3句话以内
                            2. 使用口语化的表达，避免书面语
                            3. 重点突出，不要展开过多细节
                            4. 语气要友好、自然
                        """.trimIndent()
                        
                        // 发送请求（带语音优化提示）
                        val response = apiService.sendChatRequestWithVoiceOptimization(
                            conversationHistory, 
                            userInput, 
                            voiceSystemPrompt
                        )
                        
                        VoiceResponseData(
                            text = response.text,
                            imageBase64 = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val errorDetails = buildString {
                append("语音处理失败:\n")
                append("错误类型: ${e.javaClass.simpleName}\n")
                append("错误信息: ${e.message ?: "未知错误"}\n")
                if (e.cause != null) {
                    append("原因: ${e.cause?.message}\n")
                }
            }
            android.util.Log.e("ChatViewModel", errorDetails, e)
            e.printStackTrace()
            
            VoiceResponseData(
                text = "抱歉，我遇到了一些问题：${e.message ?: "未知错误"}",
                imageBase64 = null
            )
        }
    }
    
    /**
     * 语音响应数据
     */
    private data class VoiceResponseData(
        val text: String,
        val imageBase64: String?
    )
    
    /**
     * 切换语音消息的文字展开状态
     */
    fun toggleVoiceText(messageId: Long) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(isTextExpanded = !message.isTextExpanded)
            } else {
                message
            }
        }
    }
    
    /**
     * 清除 Snackbar 消息
     */
    fun clearSnackbarMessage() {
        _snackbarMessage.value = ""
    }
    
    /**
     * 清理资源
     */
    override fun onCleared() {
        super.onCleared()
        android.util.Log.d("ChatViewModel", "onCleared: 清理所有资源")
        
        // 清理豆包实时对话
        voiceRecognitionJob?.cancel()
        voiceRecognitionJob = null
        doubaoRealtimeService?.stopRealtimeConversation()
        
        // 清理其他语音服务
        voiceService.cleanup()
        voiceTTSService.stopAll()
        cloudVoiceRecognizer.cleanup()
    }
}

/**
 * ViewModel 工厂
 */
class ChatViewModelFactory(
    private val application: Application,
    private val sessionId: String,
    private val chatDao: ChatDao,
    private val summaryDao: SessionSummaryDao,
    private val isRealtimeMode: Boolean = false
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(application, sessionId, chatDao, summaryDao, isRealtimeMode) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
