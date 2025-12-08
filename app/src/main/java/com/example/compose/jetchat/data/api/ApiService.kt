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
 * API 服务类（门面模式）
 * 
 * ⚠️ 此类已重构为门面模式，建议直接使用独立的服务类：
 * - IntentDetectionService：意图识别
 * - ChatService：文本对话
 * - ImageGenerationService：图片生成
 * - ImageCompressionService：图片压缩
 * 
 * 此类保留用于向后兼容，将在未来版本中移除。
 * 
 * @see IntentDetectionService
 * @see ChatService
 * @see ImageGenerationService
 * @see ImageCompressionService
 */
@Deprecated(
    message = "ApiService 已重构为模块化服务，建议使用独立服务类: IntentDetectionService, ChatService, ImageGenerationService, ImageCompressionService"
)
class ApiService {
    
    // 模块化服务实例
    private val intentDetectionService = IntentDetectionService()
    private val chatService = ChatService()
    private val imageGenerationService = ImageGenerationService()
    private val imageCompressionService = ImageCompressionService()
    
    // 保留JSON解析器用于兼容性
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 保留HTTP客户端用于遗留方法
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
     * 检测用户输入的意图（门面方法）
     * 
     * @param message 用户消息
     * @param hasImage 是否包含图片
     * @return 意图识别结果
     * @deprecated 请使用 IntentDetectionService.detectIntentType() 代替
     */
    @Deprecated(
        message = "Use IntentDetectionService.detectIntentType() instead",
        replaceWith = ReplaceWith("IntentDetectionService().detectIntentType(message, hasImage)")
    )
    suspend fun detectIntent(message: String, hasImage: Boolean = false): String {
        return intentDetectionService.detectIntentType(message, hasImage)
    }
    
    /**
     * 优化图片生成 Prompt（门面方法）
     * 
     * @deprecated 请使用 IntentDetectionService.detectIntent().optimizedPrompt 代替
     */
    @Deprecated("Use IntentDetectionService.detectIntent() and get optimizedPrompt from result")
    suspend fun optimizeImagePrompt(userPrompt: String): String {
        val result = intentDetectionService.detectIntent(userPrompt, hasImage = false)
        return result.optimizedPrompt ?: userPrompt
    }
    
    /**
     * 生成图片（门面方法）
     * 
     * @deprecated 请使用 ImageGenerationService.generateImage() 代替
     */
    @Deprecated(
        message = "Use ImageGenerationService.generateImage() instead",
        replaceWith = ReplaceWith("ImageGenerationService().generateImage(prompt, size = \"1024x1024\")")
    )
    suspend fun generateImage(prompt: String): String {
        return imageGenerationService.generateImage(prompt, size = "1024x1024")
    }
    
    /**
     * 下载并编码图片为 base64（门面方法）
     * 
     * @deprecated 请使用 ImageCompressionService.downloadAndCompressImage() 代替
     */
    @Deprecated(
        message = "Use ImageCompressionService.downloadAndCompressImage() instead",
        replaceWith = ReplaceWith("ImageCompressionService().downloadAndCompressImage(imageUrl)")
    )
    suspend fun downloadAndEncodeImage(imageUrl: String): String {
        return imageCompressionService.downloadAndCompressImage(imageUrl)
    }



    /**
     * API 响应结果
     */
    data class ApiResponse(
        val text: String,           // 文本回复
        val imageBase64: String? = null  // 生成的图片（base64）
    )

    /**
     * 发送多轮对话请求（支持摘要）（门面方法）
     * @param conversationHistory 对话历史 List<Pair<role, content>>
     * @param currentUserMessage 当前用户输入的消息（用于判断是否生成图片）
     * @param imageBase64 图片 base64 编码（可选）
     * @return AI 的回复
     * 
     * @deprecated 请直接使用 IntentDetectionService + ChatService / ImageGenerationService
     */
    @Deprecated("Use modular services: IntentDetectionService, ChatService, ImageGenerationService")
    suspend fun sendChatRequestWithHistory(
        conversationHistory: List<Pair<String, String>>,
        currentUserMessage: String = "",
        imageBase64: String? = null
    ): ApiResponse {
        // 使用当前用户输入来判断是否需要生成图片，而不是历史消息的最后一条
        val messageToCheck = currentUserMessage.ifEmpty { 
            conversationHistory.lastOrNull { it.first == "user" }?.second ?: ""
        }
        
        // 使用模块化的意图检测服务
        val intentResult = intentDetectionService.detectIntent(messageToCheck, hasImage = imageBase64 != null)
        val shouldGenerateImage = (intentResult.type == IntentDetectionService.IntentType.IMAGE_GENERATION) && imageBase64 == null
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
     * 发送聊天请求到 VectorEngine API（单条消息）（门面方法）
     * @param userMessage 用户消息
     * @param imageBase64 图片 base64 编码（可选）
     * @return AI 的回复（可能包含文本和图片）
     * 
     * @deprecated 请直接使用 IntentDetectionService + ChatService / ImageGenerationService
     */
    @Deprecated("Use modular services: IntentDetectionService, ChatService, ImageGenerationService")
    suspend fun sendChatRequest(userMessage: String, imageBase64: String? = null): ApiResponse {
        // 使用模块化的意图检测服务
        val intentResult = intentDetectionService.detectIntent(userMessage, hasImage = imageBase64 != null)
        val shouldGenerateImage = (intentResult.type == IntentDetectionService.IntentType.IMAGE_GENERATION) && imageBase64 == null
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
                        // 使用模块化的图片压缩服务下载并转换为 base64
                        try {
                            val imageBase64 = imageCompressionService.downloadAndCompressImage(imageUrl)
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
