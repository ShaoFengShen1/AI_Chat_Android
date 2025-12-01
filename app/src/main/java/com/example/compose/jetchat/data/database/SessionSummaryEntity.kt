package com.example.compose.jetchat.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话摘要实体
 * 用于存储对话的压缩摘要，实现长期记忆功能
 */
@Entity(tableName = "session_summaries")
data class SessionSummaryEntity(
    @PrimaryKey
    val sessionId: String,
    
    /**
     * 摘要内容：AI 生成的对话总结
     */
    val summary: String,
    
    /**
     * 摘要覆盖的最后一条消息 ID
     * 用于判断是否需要更新摘要
     */
    val lastSummarizedMessageId: Long,
    
    /**
     * 摘要创建时间
     */
    val createdAt: Long,
    
    /**
     * 摘要更新时间
     */
    val updatedAt: Long
)
