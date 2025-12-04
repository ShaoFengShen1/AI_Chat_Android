package com.example.compose.jetchat.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.compose.jetchat.ui.chat.ChatMessage
import com.example.compose.jetchat.ui.chat.MessageRole
import com.example.compose.jetchat.ui.chat.MessageStatus

/**
 * 聊天消息实体
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val role: String,  // "user" 或 "assistant"
    val content: String,
    val timestamp: Long,
    val status: String = "sent",  // "sent" 或 "error"
    val sessionTitle: String = "新对话",  // 会话标题
    val isPinned: Boolean = false,  // 是否置顶
    val imageBase64: String? = null,  // 图片的 base64 编码
    val imageDescription: String? = null,  // 图片的文本描述(用于上下文管理,防止多模态幻觉)
    val documentName: String? = null,  // 文档名称
    val documentContent: String? = null,  // 文档内容（base64 或文本）
    val audioFilePath: String? = null,  // 语音文件路径
    val audioDuration: Int? = null  // 语音时长（秒）
)

/**
 * 扩展函数：将实体转换为 UI 模型
 */
fun ChatMessageEntity.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = this.id,
        sessionId = this.sessionId,
        role = when (this.role) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> MessageRole.USER
        },
        content = this.content,
        timestamp = this.timestamp,
        status = when (this.status) {
            "error" -> MessageStatus.ERROR
            else -> MessageStatus.SENT
        },
        imageBase64 = this.imageBase64,
        imageDescription = this.imageDescription,
        documentName = this.documentName,
        documentContent = this.documentContent,
        audioFilePath = this.audioFilePath,
        audioDuration = this.audioDuration
    )
}

/**
 * 扩展函数：将 UI 模型转换为实体
 */
fun ChatMessage.toEntity(): ChatMessageEntity {
    return ChatMessageEntity(
        id = if (this.id == 0L) 0 else this.id,  // 让数据库自动生成 ID
        sessionId = this.sessionId,
        role = when (this.role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
        },
        content = this.content,
        timestamp = this.timestamp,
        status = when (this.status) {
            MessageStatus.ERROR -> "error"
            else -> "sent"
        },
        imageBase64 = this.imageBase64,
        imageDescription = this.imageDescription,
        documentName = this.documentName,
        documentContent = this.documentContent,
        audioFilePath = this.audioFilePath,
        audioDuration = this.audioDuration
    )
}
