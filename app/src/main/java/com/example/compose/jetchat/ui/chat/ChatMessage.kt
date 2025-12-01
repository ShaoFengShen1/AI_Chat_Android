package com.example.compose.jetchat.ui.chat

/**
 * 消息数据类
 */
data class ChatMessage(
    val id: Long = 0,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val imageBase64: String? = null,  // 图片的 base64 编码
    val documentName: String? = null,  // 文档名称
    val documentContent: String? = null,  // 文档内容（base64 或文本）
    val audioFilePath: String? = null,  // 语音文件路径（本地文件）
    val audioDuration: Int? = null,  // 语音时长（秒）
    val isTextExpanded: Boolean = false  // 语音消息的文字是否展开显示
)

/**
 * 消息角色
 */
enum class MessageRole {
    USER,      // 用户
    ASSISTANT  // AI 助手
}

/**
 * 消息状态
 */
enum class MessageStatus {
    SENDING,   // 发送中
    SENT,      // 已发送
    LOADING,   // AI 正在生成回复
    ERROR      // 错误
}
