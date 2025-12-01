package com.example.compose.jetchat.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用数据库
 */
@Database(
    entities = [ChatMessageEntity::class, SessionSummaryEntity::class],
    version = 7,  // 升级版本以支持语音消息字段
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun chatDao(): ChatDao
    abstract fun sessionSummaryDao(): SessionSummaryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jetchat_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
