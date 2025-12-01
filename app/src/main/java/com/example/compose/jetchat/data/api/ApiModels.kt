package com.example.compose.jetchat.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenRouter API 请求数据类（OpenAI 兼容格式）
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>
)

@Serializable
data class Message(
    val role: String,  // "user" 或 "assistant"
    val content: JsonElement  // 可以是字符串或对象数组
)

/**
 * OpenRouter API 响应数据类
 */
@Serializable
data class ChatResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: MessageResponse
)

@Serializable
data class MessageResponse(
    val content: String
)
