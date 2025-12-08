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
 * 意图检测服务
 * 
 * 职责：
 * - 使用AI模型或正则表达式识别用户意图
 * - 区分文本对话、图片生成、图片识别
 * - 优化图片生成的Prompt
 */
class IntentDetectionService {
    
    /**
     * 意图类型
     */
    enum class IntentType {
        TEXT_CHAT,          // 普通文本对话
        IMAGE_GENERATION,   // 图片生成
        IMAGE_RECOGNITION   // 图片识别（已上传图片）
    }

    /**
     * 意图识别结果
     */
    data class IntentResult(
        val type: IntentType,
        val confidence: Float = 1.0f,  // 置信度 0-1
        val optimizedPrompt: String? = null  // 优化后的Prompt（用于图片生成）
    )

    /**
     * 意图检测器接口
     */
    interface IntentDetector {
        suspend fun detectIntent(message: String, hasImage: Boolean = false): IntentResult
    }
    
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
     * 正则表达式意图检测器（快速、离线）
     */
    private class RegexIntentDetector : IntentDetector {
        override suspend fun detectIntent(message: String, hasImage: Boolean): IntentResult {
            // 如果已上传图片，意图是图片识别
            if (hasImage) {
                return IntentResult(IntentType.IMAGE_RECOGNITION, confidence = 1.0f)
            }
            
            val lowerMessage = message.lowercase()
            
            // 图片生成的模式检测
            val patterns = listOf(
                Regex("生成.*?图"),
                Regex("画(一张|一幅|一个|个)?"),
                Regex("(帮我|给我)(画|生成)"),
                Regex("(给我|帮我)(一张|一幅|一个).*(图|图片)")
            )
            
            val matched = patterns.find { it.containsMatchIn(lowerMessage) }
            val isImageGen = matched != null
            
            android.util.Log.d("RegexIntentDetector", "检测图片生成关键词 - 消息: \"$message\"")
            android.util.Log.d("RegexIntentDetector", "匹配到的模式: ${matched?.pattern ?: "无"}, 结果: $isImageGen")
            
            return if (isImageGen) {
                IntentResult(IntentType.IMAGE_GENERATION, confidence = 0.8f)
            } else {
                IntentResult(IntentType.TEXT_CHAT, confidence = 0.9f)
            }
        }
    }

    /**
     * AI意图检测器（准确、需要网络）
     */
    private inner class AIIntentDetector : IntentDetector {
        override suspend fun detectIntent(message: String, hasImage: Boolean): IntentResult = withContext(Dispatchers.IO) {
            // 如果已上传图片，意图是图片识别
            if (hasImage) {
                return@withContext IntentResult(IntentType.IMAGE_RECOGNITION, confidence = 1.0f)
            }

            try {
                android.util.Log.d("AIIntentDetector", "开始AI意图识别 - 消息: \"$message\"")
                
                val systemPrompt = """
你是一个专业的意图识别助手。请分析用户的消息，判断用户的意图类型。

意图类型：
1. IMAGE_GENERATION - 用户想要生成/创建/画一张图片
2. TEXT_CHAT - 普通的文本对话

请用JSON格式回复，包含以下字段：
- intent: 意图类型（IMAGE_GENERATION 或 TEXT_CHAT）
- confidence: 置信度（0-1之间的浮点数）
- optimized_prompt: 如果是图片生成，提供一个优化后的英文Prompt（详细、专业、适合DALL-E 3）

示例1：
用户消息："生成一只可爱的猫"
你的回复：{"intent":"IMAGE_GENERATION","confidence":0.95,"optimized_prompt":"A cute cat with fluffy fur, sitting gracefully, soft lighting, photorealistic style, high quality"}

示例2：
用户消息："今天天气怎么样？"
你的回复：{"intent":"TEXT_CHAT","confidence":0.98,"optimized_prompt":null}

现在分析这条消息：
""".trimIndent()

                val requestBody = buildJsonObject {
                    put("model", AppConfig.INTENT_MODEL)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", message)
                        }
                    }
                    put("temperature", 0.3)
                    put("max_tokens", 5000)
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
                    android.util.Log.e("AIIntentDetector", "API 请求失败: ${response.code}, $responseBody")
                    throw Exception("Intent detection failed: ${response.code}")
                }

                // 解析响应
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                val content = jsonResponse["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: throw Exception("Invalid response format")

                android.util.Log.d("AIIntentDetector", "AI 返回内容: $content")

                // 清理 Markdown 代码块（如果有）
                val cleanedContent = content
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                android.util.Log.d("AIIntentDetector", "清理后的内容: $cleanedContent")

                // 提取JSON（处理可能包含其他文本的情况）
                val jsonMatch = Regex("\\{[\\s\\S]*?\\}").find(cleanedContent)
                if (jsonMatch == null) {
                    android.util.Log.e("AIIntentDetector", "未找到有效的JSON，内容: $cleanedContent")
                    throw Exception("No valid JSON found in response")
                }

                // 解析JSON结果
                val resultJson = json.parseToJsonElement(jsonMatch.value).jsonObject
                val intent = resultJson["intent"]?.jsonPrimitive?.content ?: "TEXT_CHAT"
                val confidence = resultJson["confidence"]?.jsonPrimitive?.float ?: 0.5f
                val optimizedPrompt = resultJson["optimized_prompt"]?.jsonPrimitive?.contentOrNull

                android.util.Log.d("AIIntentDetector", "识别结果 - 意图: $intent, 置信度: $confidence, 优化Prompt: $optimizedPrompt")

                return@withContext IntentResult(
                    type = when (intent) {
                        "IMAGE_GENERATION" -> IntentType.IMAGE_GENERATION
                        else -> IntentType.TEXT_CHAT
                    },
                    confidence = confidence,
                    optimizedPrompt = optimizedPrompt
                )

            } catch (e: Exception) {
                android.util.Log.e("AIIntentDetector", "AI意图识别失败，降级到正则: ${e.message}", e)
                // 降级到正则表达式
                return@withContext regexDetector.detectIntent(message, hasImage)
            }
        }
    }

    // 创建检测器实例
    private val regexDetector = RegexIntentDetector()
    private val aiDetector = AIIntentDetector()

    /**
     * 检测用户意图
     * 
     * @param message 用户消息
     * @param hasImage 是否已上传图片
     * @param useAI 是否使用AI检测（默认true，失败时自动降级到正则）
     * @return 意图识别结果
     */
    suspend fun detectIntent(
        message: String, 
        hasImage: Boolean = false,
        useAI: Boolean = true
    ): IntentResult {
        return if (useAI) {
            aiDetector.detectIntent(message, hasImage)
        } else {
            regexDetector.detectIntent(message, hasImage)
        }
    }

    /**
     * 仅检测意图类型（兼容旧代码）
     * 
     * @return "IMAGE_GENERATION" 或 "TEXT_CHAT"
     */
    suspend fun detectIntentType(message: String, hasImage: Boolean = false): String {
        val result = detectIntent(message, hasImage)
        return when (result.type) {
            IntentType.IMAGE_GENERATION -> "IMAGE_GENERATION"
            IntentType.IMAGE_RECOGNITION -> "IMAGE_RECOGNITION"
            IntentType.TEXT_CHAT -> "TEXT_CHAT"
        }
    }
}
