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
 * 聊天服务
 * 
 * 职责：
 * - 处理文本对话（Gemini/GPT等模型）
 * - 管理对话上下文
 * - 处理流式响应（未来扩展）
 */
class ChatService {
    
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
     * 发送聊天消息
     * 
     * @param messages 消息列表（包含角色和内容）
     * @param model 使用的模型（默认使用配置中的模型）
     * @return AI回复内容
     */
    suspend fun chat(
        messages: List<Map<String, Any>>,
        model: String = AppConfig.CHAT_MODEL
    ): String = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ChatService", "发送聊天请求 - 模型: $model, 消息数: ${messages.size}")
            
            val requestBody = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    messages.forEach { msg ->
                        addJsonObject {
                            put("role", msg["role"] as String)
                            
                            // 处理内容（可能是文本或多模态内容）
                            val content = msg["content"]
                            when (content) {
                                is String -> put("content", content)
                                is List<*> -> putJsonArray("content") {
                                    content.forEach { item ->
                                        when (item) {
                                            is Map<*, *> -> addJsonObject {
                                                item.forEach { (key, value) ->
                                                    when (value) {
                                                        is String -> put(key.toString(), value)
                                                        is Map<*, *> -> putJsonObject(key.toString()) {
                                                            value.forEach { (k, v) ->
                                                                put(k.toString(), v.toString())
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                put("temperature", 0.7)
                put("max_tokens", 4000)
            }.toString()

            val request = Request.Builder()
                .url(AppConfig.CHAT_API_URL)
                .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                android.util.Log.e("ChatService", "聊天请求失败: ${response.code}, $responseBody")
                throw Exception("Chat request failed: ${response.code}")
            }

            // 解析响应
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val content = jsonResponse["choices"]?.jsonArray?.get(0)?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw Exception("Invalid response format")

            android.util.Log.d("ChatService", "聊天响应成功 - 内容长度: ${content.length}")
            
            return@withContext content

        } catch (e: Exception) {
            android.util.Log.e("ChatService", "聊天请求异常: ${e.message}", e)
            throw e
        }
    }

    /**
     * 发送简单的文本消息（快捷方法）
     * 
     * @param userMessage 用户消息
     * @param systemPrompt 系统提示词（可选）
     * @return AI回复
     */
    suspend fun sendMessage(
        userMessage: String,
        systemPrompt: String? = null
    ): String {
        val messages = mutableListOf<Map<String, Any>>()
        
        if (systemPrompt != null) {
            messages.add(mapOf(
                "role" to "system",
                "content" to systemPrompt
            ))
        }
        
        messages.add(mapOf(
            "role" to "user",
            "content" to userMessage
        ))
        
        return chat(messages)
    }
}
