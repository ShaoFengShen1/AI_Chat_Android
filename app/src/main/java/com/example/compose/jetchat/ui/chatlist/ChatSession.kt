package com.example.compose.jetchat.ui.chatlist

import com.example.compose.jetchat.data.database.ChatMessageEntity

/**
 * 会话摘要数据类
 */
data class ChatSession(
    val sessionId: String,
    val title: String,
    val lastMessage: String,
    val timestamp: Long,
    val isPinned: Boolean = false
)

/**
 * 扩展函数：从消息实体创建会话摘要
 */
fun ChatMessageEntity.toSession(): ChatSession {
    return ChatSession(
        sessionId = this.sessionId,
        title = if (this.sessionTitle.length > 15) this.sessionTitle.take(15) + "..." else this.sessionTitle,
        lastMessage = this.content.take(30) + if (this.content.length > 30) "..." else "",
        timestamp = this.timestamp,
        isPinned = this.isPinned
    )
}
