package com.example.compose.jetchat.data.api

import com.example.compose.jetchat.config.AppConfig
import kotlinx.serialization.json.*
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/**
 * API 服务类
 */
class ApiService {
    
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
            // 配置代理（如果需要访问 Google API）
            if (AppConfig.USE_PROXY) {
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.PROXY_HOST, AppConfig.PROXY_PORT)))
            }
        }
        .connectTimeout(AppConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(AppConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 正则表达式意图检测器（备选方案）
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
                // "生成" + "图"
                Regex("生成.*?图"),
                // "画" + 可选的量词
                Regex("画(一张|一幅|一个|个)?"),
                // "帮我" 或 "给我" + "画" 或 "生成"
                Regex("(帮我|给我)(画|生成)"),
                // "给我" + "一张/一幅/一个" + 任意内容 + "图/图片"
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
     * AI意图检测器（使用AI模型）
     */
    private inner class AIIntentDetector : IntentDetector {
        override suspend fun detectIntent(message: String, hasImage: Boolean): IntentResult = withContext(Dispatchers.IO) {
            // 如果已上传图片，意图是图片识别
            if (hasImage) {
                return@withContext IntentResult(IntentType.IMAGE_RECOGNITION, confidence = 1.0f)
            }
            
            android.util.Log.d("AIIntentDetector", "开始AI意图识别 - 消息: \"$message\"")
            val startTime = System.currentTimeMillis()
            
            try {
                // 构建意图识别请求
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

                val intentRequest = buildJsonObject {
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
                    put("temperature", 0.3)  // 低温度，更确定的结果
                    put("max_tokens", 300)
                }

                val request = Request.Builder()
                    .url(AppConfig.CHAT_API_URL)
                    .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(intentRequest.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseTime = System.currentTimeMillis() - startTime
                    
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "unknown error"
                        when (response.code) {
                            429 -> android.util.Log.w("AIIntentDetector", "AI意图识别失败: 429 Too Many Requests (请求过于频繁), 降级到正则表达式")
                            401, 403 -> android.util.Log.w("AIIntentDetector", "AI意图识别失败: ${response.code} 认证错误, 降级到正则表达式")
                            else -> android.util.Log.w("AIIntentDetector", "AI意图识别失败: ${response.code}, 降级到正则表达式")
                        }
                        android.util.Log.d("AIIntentDetector", "错误详情: $errorBody")
                        return@withContext RegexIntentDetector().detectIntent(message, hasImage)
                    }

                    val responseBody = response.body?.string() ?: ""
                    val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                    val aiReply = jsonResponse["choices"]?.jsonArray?.get(0)
                        ?.jsonObject?.get("message")
                        ?.jsonObject?.get("content")
                        ?.jsonPrimitive?.content ?: ""

                    android.util.Log.d("AIIntentDetector", "AI回复: $aiReply, 耗时: ${responseTime}ms")

                    // 解析AI返回的JSON
                    try {
                    // 提取JSON部分（可能包含其他文本或Markdown代码块）
                    // 先尝试移除Markdown代码块标记
                    val cleanedReply = aiReply.replace("```json", "").replace("```", "").trim()
                    
                    // 尝试提取完整JSON（包括嵌套的大括号）
                    val jsonMatch = Regex("\\{[\\s\\S]*?\\}").find(cleanedReply)
                    if (jsonMatch == null) {
                        android.util.Log.w("AIIntentDetector", "AI回复中未找到完整JSON，降级到正则表达式")
                        android.util.Log.w("AIIntentDetector", "AI回复内容: $cleanedReply")
                        return@withContext RegexIntentDetector().detectIntent(message, hasImage)
                    }
                    
                    val intentJson = json.parseToJsonElement(jsonMatch.value).jsonObject
                        val intentStr = intentJson["intent"]?.jsonPrimitive?.content ?: "TEXT_CHAT"
                        val confidence = intentJson["confidence"]?.jsonPrimitive?.float ?: 0.9f
                        val optimizedPrompt = intentJson["optimized_prompt"]?.jsonPrimitive?.contentOrNull

                        val intentType = when (intentStr.uppercase()) {
                            "IMAGE_GENERATION" -> IntentType.IMAGE_GENERATION
                            else -> IntentType.TEXT_CHAT
                        }

                        android.util.Log.d("AIIntentDetector", "识别结果: $intentType, 置信度: $confidence")
                        if (optimizedPrompt != null) {
                            android.util.Log.d("AIIntentDetector", "优化Prompt: $optimizedPrompt")
                        }

                        IntentResult(
                            type = intentType,
                            confidence = confidence,
                            optimizedPrompt = optimizedPrompt
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("AIIntentDetector", "解析AI回复失败: ${e.message}, 降级到正则表达式")
                        RegexIntentDetector().detectIntent(message, hasImage)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AIIntentDetector", "AI意图识别异常: ${e.message}, 降级到正则表达式")
                RegexIntentDetector().detectIntent(message, hasImage)
            }
        }
    }
    
    // 意图检测器实例
    private val intentDetector: IntentDetector = if (AppConfig.USE_AI_INTENT_DETECTION) {
        AIIntentDetector()
    } else {
        RegexIntentDetector()
    }
    
    /**
     * 检测用户输入的意图
     * 
     * @param message 用户消息
     * @param hasImage 是否包含图片
     * @return 意图识别结果
     */
    suspend fun detectIntent(message: String, hasImage: Boolean = false): String {
        val result = intentDetector.detectIntent(message, hasImage)
        return when (result.type) {
            IntentType.IMAGE_GENERATION -> "image_generation"
            IntentType.IMAGE_RECOGNITION -> "image_recognition"
            IntentType.TEXT_CHAT -> "text_chat"
        }
    }
    
    /**
     * 优化图片生成 Prompt
     */
    suspend fun optimizeImagePrompt(userPrompt: String): String {
        val result = intentDetector.detectIntent(userPrompt, hasImage = false)
        return result.optimizedPrompt ?: userPrompt
    }
    
    /**
     * 生成图片
     */
    suspend fun generateImage(prompt: String): String = withContext(Dispatchers.IO) {
        val imageRequest = buildJsonObject {
            put("model", AppConfig.IMAGE_MODEL)
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
        }
        
        val request = Request.Builder()
            .url(AppConfig.IMAGE_API_URL)
            .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(imageRequest.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("图片生成失败: ${response.code}")
            }
            
            val responseBody = response.body?.string() ?: throw Exception("响应为空")
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            
            jsonResponse["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("url")?.jsonPrimitive?.content
                ?: throw Exception("未找到图片URL")
        }
    }
    
    /**
     * 下载并编码图片为 base64
     */
    suspend fun downloadAndEncodeImage(imageUrl: String): String = withContext(Dispatchers.IO) {
        downloadImageAsBase64(imageUrl)
    }

    /**
     * 下载图片并转换为 base64（极速优化版）
     * 
     * 优化策略：
     * 1. 使用流式处理，边下载边解码（节省内存）
     * 2. 激进压缩到220px（移动端显示完全足够）
     * 3. JPEG质量70%（平衡质量和大小）
     * 4. RGB_565格式（减少50%内存）
     * 5. inSampleSize=4（解码时就缩小4倍）
     * 
     * 效果：1024x1024 (~2MB PNG) → 220x220 (~20KB JPEG)
     */
    private fun downloadImageAsBase64(url: String): String {
        val startTime = System.currentTimeMillis()
        android.util.Log.d("ApiService", "开始下载图片: $url")
        
        // 设置更短的超时（图片下载不应该太久）
        val imageClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(AppConfig.IMAGE_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        imageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("下载图片失败: ${response.code}")
            }
            
            val contentLength = response.body?.contentLength() ?: 0L
            if (contentLength > 0) {
                android.util.Log.d("ApiService", "原始文件大小: ${contentLength / 1024}KB")
            }
            
            // 读取字节数组（网络流不支持mark/reset）
            val imageBytes = response.body?.bytes() 
                ?: throw Exception("图片数据为空")
            
            val downloadTime = System.currentTimeMillis()
            android.util.Log.d("ApiService", "下载完成，开始解码...")
            
            // 第一步：读取图片尺寸（不加载到内存）
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            
            // 计算极致的压缩比例
            val maxSize = AppConfig.IMAGE_DISPLAY_SIZE  // 使用配置的显示尺寸
            val sampleSize = calculateInSampleSize(options, maxSize, maxSize)
            
            android.util.Log.d("ApiService", "原始尺寸: ${options.outWidth}x${options.outHeight}, 采样率: $sampleSize")
            
            // 第二步：实际解码（使用采样率大幅降低内存）
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565  // 减少50%内存
            
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                ?: throw Exception("图片解码失败")
            
            val decodeTime = System.currentTimeMillis()
            android.util.Log.d("ApiService", "解码完成: ${bitmap.width}x${bitmap.height}, 耗时: ${decodeTime - downloadTime}ms")
            
            // 精确缩放到目标尺寸
            val finalBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                val scale = minOf(
                    maxSize.toFloat() / bitmap.width,
                    maxSize.toFloat() / bitmap.height
                )
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true  // 使用双线性过滤，质量更好
                ).also { bitmap.recycle() }
            } else {
                bitmap
            }
            
            // 使用更高压缩率（移动端不需要高质量）
            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)  // 质量70%，平衡质量和大小
            val compressedBytes = outputStream.toByteArray()
            
            finalBitmap.recycle()
            
            val totalTime = System.currentTimeMillis() - startTime
            val finalSize = compressedBytes.size / 1024
            val originalSize = imageBytes.size / 1024
            val compressionRatio = String.format("%.1f", (finalSize.toFloat() / originalSize) * 100)
            
            android.util.Log.d("ApiService", "✅ 图片处理完成: ${originalSize}KB → ${finalSize}KB (压缩到${compressionRatio}%), 总耗时: ${totalTime}ms")
            
            return Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        }
    }
    
    /**
     * 计算 inSampleSize（解码时的缩放倍数）
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // 计算最大的 inSampleSize（2 的幂次），保证缩放后尺寸仍大于目标尺寸
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * API 响应结果
     */
    data class ApiResponse(
        val text: String,           // 文本回复
        val imageBase64: String? = null  // 生成的图片（base64）
    )

    /**
     * 发送多轮对话请求（支持摘要）
     * @param conversationHistory 对话历史 List<Pair<role, content>>
     * @param currentUserMessage 当前用户输入的消息（用于判断是否生成图片）
     * @param imageBase64 图片 base64 编码（可选）
     * @return AI 的回复
     */
    suspend fun sendChatRequestWithHistory(
        conversationHistory: List<Pair<String, String>>,
        currentUserMessage: String = "",
        imageBase64: String? = null
    ): ApiResponse {
        // 使用当前用户输入来判断是否需要生成图片，而不是历史消息的最后一条
        val messageToCheck = currentUserMessage.ifEmpty { 
            conversationHistory.lastOrNull { it.first == "user" }?.second ?: ""
        }
        
        // 使用AI意图检测器
        val intentResult = intentDetector.detectIntent(messageToCheck, hasImage = imageBase64 != null)
        val shouldGenerateImage = (intentResult.type == IntentType.IMAGE_GENERATION) && imageBase64 == null
        val modelToUse = if (shouldGenerateImage) AppConfig.IMAGE_MODEL else AppConfig.CHAT_MODEL
        
        // 如果需要生成图片且有优化Prompt，使用优化后的Prompt
        val finalPrompt = if (shouldGenerateImage && intentResult.optimizedPrompt != null && AppConfig.OPTIMIZE_IMAGE_PROMPT) {
            intentResult.optimizedPrompt
        } else {
            messageToCheck
        }
        
        android.util.Log.d("ApiService", "发送多轮对话，消息数: ${conversationHistory.size}")
        android.util.Log.d("ApiService", "当前用户消息: $messageToCheck")
        android.util.Log.d("ApiService", "意图类型: ${intentResult.type}, 置信度: ${intentResult.confidence}")
        android.util.Log.d("ApiService", "是否生成图片: $shouldGenerateImage")
        android.util.Log.d("ApiService", "使用模型: $modelToUse")
        if (finalPrompt != messageToCheck) {
            android.util.Log.d("ApiService", "优化后的Prompt: $finalPrompt")
        }
        
        // 构建消息列表
        val messages = conversationHistory.map { (role, content) ->
            Message(
                role = role,
                content = JsonPrimitive(content)
            )
        }
        
        // 如果最后一条消息有图片，需要特殊处理
        val finalMessages = if (imageBase64 != null && conversationHistory.isNotEmpty()) {
            val lastMsg = conversationHistory.last()
            messages.dropLast(1) + Message(
                role = lastMsg.first,
                content = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", lastMsg.second)
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:image/jpeg;base64,$imageBase64")
                        })
                    })
                }
            )
        } else if (shouldGenerateImage && finalPrompt != messageToCheck && conversationHistory.isNotEmpty()) {
            // 如果需要生成图片且Prompt被优化了，替换最后一条用户消息
            messages.dropLast(1) + Message(
                role = "user",
                content = JsonPrimitive(finalPrompt)
            )
        } else {
            messages
        }
        
        return sendChatRequestInternal(modelToUse, finalMessages, shouldGenerateImage, finalPrompt)
    }
    
    /**
     * 发送聊天请求到 VectorEngine API（单条消息）
     * @param userMessage 用户消息
     * @param imageBase64 图片 base64 编码（可选）
     * @return AI 的回复（可能包含文本和图片）
     */
    suspend fun sendChatRequest(userMessage: String, imageBase64: String? = null): ApiResponse {
        // 使用AI意图检测器
        val intentResult = intentDetector.detectIntent(userMessage, hasImage = imageBase64 != null)
        val shouldGenerateImage = (intentResult.type == IntentType.IMAGE_GENERATION) && imageBase64 == null
        val modelToUse = if (shouldGenerateImage) AppConfig.IMAGE_MODEL else AppConfig.CHAT_MODEL
        
        // 如果需要生成图片且有优化Prompt，使用优化后的Prompt
        val finalPrompt = if (shouldGenerateImage && intentResult.optimizedPrompt != null && AppConfig.OPTIMIZE_IMAGE_PROMPT) {
            intentResult.optimizedPrompt
        } else {
            userMessage
        }
        
        android.util.Log.d("ApiService", "意图类型: ${intentResult.type}, 置信度: ${intentResult.confidence}")
        android.util.Log.d("ApiService", "检测到图片生成请求: $shouldGenerateImage")
        android.util.Log.d("ApiService", "使用模型: $modelToUse")
        if (finalPrompt != userMessage) {
            android.util.Log.d("ApiService", "优化后的Prompt: $finalPrompt")
        }
        
        // 构建消息内容（使用优化后的Prompt）
        val content = if (imageBase64 != null) {
            // 如果有图片，使用数组格式
            kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", userMessage)  // 图片识别时使用原始消息
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64,$imageBase64")
                    })
                })
            }
        } else {
            // 纯文本，使用优化后的Prompt
            kotlinx.serialization.json.JsonPrimitive(finalPrompt)
        }

        val messages = listOf(
            Message(
                role = "user",
                content = content
            )
        )
        
        return sendChatRequestInternal(modelToUse, messages, shouldGenerateImage, finalPrompt)
    }
    
    /**
     * 内部方法：实际发送请求
     */
    private suspend fun sendChatRequestInternal(
        modelToUse: String,
        messages: List<Message>,
        shouldGenerateImage: Boolean,
        imagePrompt: String = ""
    ): ApiResponse {
        val chatRequest = ChatRequest(
            model = modelToUse,
            messages = messages
        )

        // 根据请求类型选择不同的端点和请求体
        val (apiUrl, requestBody) = if (shouldGenerateImage) {
            // 图片生成请求 - 使用传入的 prompt 或从最后一条用户消息提取
            val promptText = imagePrompt.ifEmpty {
                val lastUserMessage = messages.lastOrNull { it.role == "user" }
                when (val content = lastUserMessage?.content) {
                    is JsonPrimitive -> content.content
                    else -> "生成图片"
                }
            }
            
            android.util.Log.d("ApiService", "图片生成 prompt: $promptText")
            
            val imageRequest = buildJsonObject {
                put("model", modelToUse)
                put("prompt", promptText)
                put("n", 1)
                put("size", AppConfig.IMAGE_GENERATION_SIZE)  // 可配置的生成尺寸
                put("quality", "standard")  // 使用标准质量，更快
            }
            val body = imageRequest.toString().toRequestBody("application/json".toMediaType())
            AppConfig.IMAGE_API_URL to body
        } else {
            // 聊天请求
            val body = json.encodeToString(
                ChatRequest.serializer(),
                chatRequest
            ).toRequestBody("application/json".toMediaType())
            AppConfig.CHAT_API_URL to body
        }
        
        android.util.Log.d("ApiService", "发送请求到: $apiUrl")
        android.util.Log.d("ApiService", "使用模型: $modelToUse")
        android.util.Log.d("ApiService", "消息数量: ${messages.size}")
        android.util.Log.d("ApiService", "是否生成图片: $shouldGenerateImage")
        
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            android.util.Log.d("ApiService", "响应代码: ${response.code}")
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "无错误信息"
                android.util.Log.e("ApiService", "请求失败: ${response.code} ${response.message}")
                android.util.Log.e("ApiService", "错误详情: $errorBody")
                
                // 如果是图片生成失败，回退到文本对话
                if (shouldGenerateImage) {
                    android.util.Log.w("ApiService", "图片生成失败，回退到文本对话模式")
                    
                    // 构建一个友好的提示消息，让 AI 解释为什么不能生成图片
                    val fallbackMessages = messages + Message(
                        role = "system",
                        content = JsonPrimitive("图片生成服务暂时不可用，请用文字回复用户，说明当前无法生成图片，并简要描述用户想要的图片内容。")
                    )
                    
                    return sendChatRequestInternal(
                        AppConfig.CHAT_MODEL,
                        fallbackMessages,
                        shouldGenerateImage = false,
                        imagePrompt = ""
                    )
                }
                
                throw Exception("请求失败: ${response.code} ${response.message}\n详情: $errorBody")
            }

            val responseBody = response.body?.string() 
                ?: throw Exception("响应体为空")

            android.util.Log.d("ApiService", "响应内容: ${responseBody.take(500)}...")

            // 根据请求类型处理不同的响应格式
            if (shouldGenerateImage) {
                android.util.Log.d("ApiService", "处理图片生成响应")
                // 图片生成 API 返回格式: {"data": [{"url": "...", "b64_json": "..."}]}
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                val dataArray = jsonResponse["data"]?.jsonArray
                
                if (dataArray != null && dataArray.isNotEmpty()) {
                    val firstImage = dataArray[0].jsonObject
                    
                    // 优先使用 b64_json，如果没有则使用 url
                    val b64Json = firstImage["b64_json"]?.jsonPrimitive?.content
                    if (b64Json != null) {
                        android.util.Log.d("ApiService", "提取到 base64 图片数据，长度: ${b64Json.length}")
                        return ApiResponse(text = "生成的图片：", imageBase64 = b64Json)
                    }
                    
                    val imageUrl = firstImage["url"]?.jsonPrimitive?.content
                    if (imageUrl != null) {
                        android.util.Log.d("ApiService", "收到图片 URL: $imageUrl")
                        // 下载图片并转换为 base64
                        try {
                            val imageBase64 = downloadImageAsBase64(imageUrl)
                            return ApiResponse(text = "生成的图片：", imageBase64 = imageBase64)
                        } catch (e: Exception) {
                            android.util.Log.e("ApiService", "下载图片失败", e)
                            return ApiResponse(text = "图片生成成功，但下载失败: ${e.message}", imageBase64 = null)
                        }
                    }
                }
                
                throw Exception("图片生成响应格式错误: 未找到图片数据")
            } else {
                // 聊天 API 响应
                val chatResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
                val content = chatResponse.choices.firstOrNull()?.message?.content
                    ?: throw Exception("响应格式错误: 未找到有效内容")
                return ApiResponse(text = content, imageBase64 = null)
            }
        }
    }

    /**
     * 发送语音优化的聊天请求（简洁回复）
     */
    suspend fun sendChatRequestWithVoiceOptimization(
        conversationHistory: List<Pair<String, String>>,
        currentUserMessage: String,
        voiceSystemPrompt: String
    ): ApiResponse {
        android.util.Log.d("ApiService", "发送语音优化请求，消息数: ${conversationHistory.size}")
        
        // 构建消息列表，添加语音优化的系统提示
        val messages = mutableListOf<Message>()
        
        // 添加系统提示（语音优化）
        messages.add(Message(role = "system", content = JsonPrimitive(voiceSystemPrompt)))
        
        // 添加对话历史
        messages.addAll(conversationHistory.map { (role, content) ->
            Message(role = role, content = JsonPrimitive(content))
        })
        
        val request = ChatRequest(
            model = AppConfig.CHAT_MODEL, // 使用 Gemini
            messages = messages
        )
        
        val requestBody = json.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        
        val httpRequest = Request.Builder()
            .url(AppConfig.CHAT_API_URL)
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
            .addHeader("Content-Type", "application/json")
            .build()
        
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                android.util.Log.e("ApiService", "API 请求失败 (${response.code}): $errorBody")
                throw Exception("API 请求失败: ${response.code} - $errorBody")
            }
            
            val responseBody = response.body?.string()
                ?: throw Exception("响应体为空")
            
            val chatResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
            val content = chatResponse.choices.firstOrNull()?.message?.content
                ?: throw Exception("响应格式错误: 未找到有效内容")
            
            return ApiResponse(text = content, imageBase64 = null)
        }
    }

    companion object {
        // 单例
        val instance: ApiService by lazy { ApiService() }
    }
}
