package com.example.compose.jetchat.data.api

import com.example.compose.jetchat.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 图片生成服务
 * 
 * 职责：
 * - 调用DALL-E/SeedDream等图片生成API
 * - 优化生成Prompt
 * - 处理生成结果
 */
class ImageGenerationService {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .apply {
            if (AppConfig.ENABLE_LOGGING) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }
            if (AppConfig.USE_PROXY) {
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.PROXY_HOST, AppConfig.PROXY_PORT)))
            }
        }
        .connectTimeout(AppConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(AppConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 生成图片
     * 
     * @param prompt 生成提示词（可以是中文或英文）
     * @param model 使用的模型（默认使用配置中的模型）
     * @param size 图片尺寸
     * @param quality 图片质量
     * @return 图片URL或Base64数据
     */
    suspend fun generateImage(
        prompt: String,
        model: String = AppConfig.IMAGE_MODEL,
        size: String = "512x512",
        quality: String = "standard"
    ): String = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ImageGenerationService", "生成图片 - Prompt: $prompt")
            android.util.Log.d("ImageGenerationService", "模型: $model, 尺寸: $size")
            
            val requestBody = buildJsonObject {
                put("model", model)
                put("prompt", prompt)
                put("size", size)
                put("quality", quality)
                put("n", 1)
            }.toString()

            val request = Request.Builder()
                .url(AppConfig.IMAGE_API_URL)
                .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                android.util.Log.e("ImageGenerationService", "图片生成失败: ${response.code}")
                android.util.Log.e("ImageGenerationService", "响应: $responseBody")
                throw Exception("Image generation failed: ${response.code}")
            }

            // 解析响应
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            
            // 尝试获取 URL（DALL-E 返回格式）
            val imageUrl = jsonResponse["data"]?.jsonArray?.get(0)?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            
            if (imageUrl != null) {
                android.util.Log.d("ImageGenerationService", "图片生成成功 - URL: $imageUrl")
                return@withContext imageUrl
            }
            
            // 尝试获取 Base64（部分API返回格式）
            val base64Data = jsonResponse["data"]?.jsonArray?.get(0)?.jsonObject?.get("b64_json")?.jsonPrimitive?.contentOrNull
            
            if (base64Data != null) {
                android.util.Log.d("ImageGenerationService", "图片生成成功 - Base64数据长度: ${base64Data.length}")
                return@withContext base64Data
            }
            
            throw Exception("Invalid response format: no image data found")

        } catch (e: Exception) {
            android.util.Log.e("ImageGenerationService", "图片生成异常: ${e.message}", e)
            throw e
        }
    }

    /**
     * 使用优化后的Prompt生成图片
     * 
     * @param originalPrompt 原始提示词（可能是中文）
     * @param optimizedPrompt 优化后的英文Prompt（可选）
     * @return 图片URL或Base64数据
     */
    suspend fun generateImageWithOptimizedPrompt(
        originalPrompt: String,
        optimizedPrompt: String?
    ): String {
        // 优先使用优化后的Prompt
        val finalPrompt = optimizedPrompt ?: originalPrompt
        
        android.util.Log.d("ImageGenerationService", "原始Prompt: $originalPrompt")
        android.util.Log.d("ImageGenerationService", "使用Prompt: $finalPrompt")
        
        return generateImage(finalPrompt)
    }
}
