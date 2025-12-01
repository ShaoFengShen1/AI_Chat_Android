package com.example.compose.jetchat.data.database

import androidx.room.*

/**
 * 会话摘要 DAO
 */
@Dao
interface SessionSummaryDao {
    
    /**
     * 获取会话摘要
     */
    @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId")
    suspend fun getSummary(sessionId: String): SessionSummaryEntity?
    
    /**
     * 插入或更新摘要
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSummary(summary: SessionSummaryEntity)
    
    /**
     * 删除会话摘要
     */
    @Query("DELETE FROM session_summaries WHERE sessionId = :sessionId")
    suspend fun deleteSummary(sessionId: String)
}
