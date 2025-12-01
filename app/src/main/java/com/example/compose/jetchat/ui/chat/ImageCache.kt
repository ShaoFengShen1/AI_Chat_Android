package com.example.compose.jetchat.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片缓存管理器 - 使用 LRU 缓存解码后的 Bitmap
 */
object ImageCache {
    // LRU 缓存，最大容量为可用内存的 1/8
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            // 返回 Bitmap 占用的内存大小（KB）
            return bitmap.byteCount / 1024
        }
    }

    /**
     * 异步解码 Base64 图片，带缓存
     */
    suspend fun decodeBitmap(base64: String): Bitmap? = withContext(Dispatchers.Default) {
        try {
            // 使用 Base64 的哈希作为缓存 key（避免存储大字符串）
            val cacheKey = base64.hashCode().toString()
            
            // 先检查缓存
            bitmapCache.get(cacheKey)?.let { 
                android.util.Log.d("ImageCache", "从缓存获取图片")
                return@withContext it 
            }
            
            // 缓存未命中，解码图片
            android.util.Log.d("ImageCache", "解码图片并加入缓存")
            val startTime = System.currentTimeMillis()
            
            val imageBytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            val decodeTime = System.currentTimeMillis() - startTime
            android.util.Log.d("ImageCache", "图片解码耗时: ${decodeTime}ms")
            
            // 加入缓存
            bitmap?.let { bitmapCache.put(cacheKey, it) }
            
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("ImageCache", "图片解码失败", e)
            null
        }
    }

    /**
     * 清理缓存
     */
    fun clearCache() {
        bitmapCache.evictAll()
    }
}
