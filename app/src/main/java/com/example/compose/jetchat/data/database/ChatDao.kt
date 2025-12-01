package com.example.compose.jetchat.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 聊天消息 DAO
 */
@Dao
interface ChatDao {
    
    /**
     * 插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    /**
     * 批量插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    /**
     * 根据会话 ID 获取所有消息
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionId(sessionId: String): List<ChatMessageEntity>

    /**
     * 根据会话 ID 获取消息流（实时更新）
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySessionIdFlow(sessionId: String): Flow<List<ChatMessageEntity>>

    /**
     * 获取所有唯一的会话 ID
     */
    @Query("SELECT sessionId FROM chat_messages GROUP BY sessionId ORDER BY MAX(timestamp) DESC")
    suspend fun getAllSessionIds(): List<String>

    /**
     * 获取每个会话的最新消息
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE id IN (
            SELECT MAX(id) FROM chat_messages GROUP BY sessionId
        )
        ORDER BY timestamp DESC
    """)
    suspend fun getLatestMessagePerSession(): List<ChatMessageEntity>

    /**
     * 获取每个会话的最新消息（Flow）
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE id IN (
            SELECT MAX(id) FROM chat_messages GROUP BY sessionId
        )
        ORDER BY timestamp DESC
    """)
    fun getLatestMessagePerSessionFlow(): Flow<List<ChatMessageEntity>>

    /**
     * 删除会话的所有消息
     */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySessionId(sessionId: String)

    /**
     * 删除所有消息
     */
    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    /**
     * 更新会话标题
     */
    @Query("UPDATE chat_messages SET sessionTitle = :title WHERE sessionId = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    /**
     * 获取会话标题
     */
    @Query("SELECT sessionTitle FROM chat_messages WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionTitle(sessionId: String): String?

    /**
     * 置顶/取消置顶会话
     */
    @Query("UPDATE chat_messages SET isPinned = :isPinned WHERE sessionId = :sessionId")
    suspend fun togglePinSession(sessionId: String, isPinned: Boolean)
}
