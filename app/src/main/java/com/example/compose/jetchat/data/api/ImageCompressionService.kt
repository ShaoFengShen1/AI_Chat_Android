package com.example.compose.jetchat.data.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import com.example.compose.jetchat.config.AppConfig

/**
 * 图片压缩服务
 * 
 * 职责：
 * - 下载网络图片
 * - 压缩图片（降低分辨率、调整质量）
 * - Base64编解码
 */
class ImageCompressionService {
    
    // 用于下载图片的HTTP客户端（超时时间更短）
    private val downloadClient = OkHttpClient.Builder()
        .apply {
            if (AppConfig.ENABLE_LOGGING) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                })
            }
            if (AppConfig.USE_PROXY) {
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.PROXY_HOST, AppConfig.PROXY_PORT)))
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(AppConfig.IMAGE_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 下载并压缩图片
     * 
     * @param imageUrl 图片URL
     * @param maxSize 最大尺寸（默认220px）
     * @param quality 压缩质量（0-100，默认60）
     * @return Base64编码的压缩图片
     */
    suspend fun downloadAndCompressImage(
        imageUrl: String,
        maxSize: Int = 220,
        quality: Int = 60
    ): String = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ImageCompressionService", "开始下载图片: $imageUrl")
            val startTime = System.currentTimeMillis()
            
            // 下载图片
            val request = Request.Builder()
                .url(imageUrl)
                .build()

            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("图片下载失败: ${response.code}")
            }

            val imageBytes = response.body?.bytes() ?: throw Exception("图片数据为空")
            val downloadTime = System.currentTimeMillis() - startTime
            
            android.util.Log.d("ImageCompressionService", "下载完成 - 耗时: ${downloadTime}ms, 大小: ${imageBytes.size / 1024}KB")

            // 解码并压缩
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw Exception("图片解码失败")

            val compressed = compressBitmap(bitmap, maxSize, quality)
            val compressTime = System.currentTimeMillis() - startTime - downloadTime
            
            android.util.Log.d("ImageCompressionService", "压缩完成 - 耗时: ${compressTime}ms, 大小: ${compressed.length / 1024}KB")
            android.util.Log.d("ImageCompressionService", "总耗时: ${System.currentTimeMillis() - startTime}ms")
            
            return@withContext compressed

        } catch (e: Exception) {
            android.util.Log.e("ImageCompressionService", "图片下载/压缩失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 压缩Bitmap
     * 
     * @param bitmap 原始图片
     * @param maxSize 最大尺寸
     * @param quality 压缩质量（0-100）
     * @return Base64编码的压缩图片
     */
    fun compressBitmap(
        bitmap: Bitmap,
        maxSize: Int = 220,
        quality: Int = 60
    ): String {
        val startTime = System.currentTimeMillis()
        
        // 计算缩放比例
        val scale = minOf(
            maxSize.toFloat() / bitmap.width,
            maxSize.toFloat() / bitmap.height
        )
        
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        android.util.Log.d("ImageCompressionService", "原始尺寸: ${bitmap.width}x${bitmap.height}")
        android.util.Log.d("ImageCompressionService", "压缩尺寸: ${newWidth}x${newHeight}")
        
        // 缩放图片
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        // 压缩为JPEG
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val compressedBytes = outputStream.toByteArray()
        
        // 释放资源
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        bitmap.recycle()
        
        // Base64编码
        val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        
        val duration = System.currentTimeMillis() - startTime
        android.util.Log.d("ImageCompressionService", "压缩完成 - 耗时: ${duration}ms, Base64长度: ${base64.length}")
        
        return base64
    }

    /**
     * 解码Base64图片为Bitmap
     * 
     * @param base64 Base64编码的图片
     * @return Bitmap对象
     */
    fun decodeBase64ToBitmap(base64: String): Bitmap? {
        return try {
            val imageBytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            android.util.Log.e("ImageCompressionService", "Base64解码失败: ${e.message}", e)
            null
        }
    }

    /**
     * 计算压缩后的预估大小
     * 
     * @param originalWidth 原始宽度
     * @param originalHeight 原始高度
     * @param maxSize 最大尺寸
     * @return 预估的文件大小（字节）
     */
    fun estimateCompressedSize(
        originalWidth: Int,
        originalHeight: Int,
        maxSize: Int = 220
    ): Int {
        val scale = minOf(
            maxSize.toFloat() / originalWidth,
            maxSize.toFloat() / originalHeight
        )
        
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        
        // 粗略估算：JPEG压缩后约为原始像素数的1/10
        return newWidth * newHeight / 10
    }
}
