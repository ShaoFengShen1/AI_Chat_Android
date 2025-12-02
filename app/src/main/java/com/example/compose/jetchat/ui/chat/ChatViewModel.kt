package com.example.compose.jetchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.compose.jetchat.data.api.ApiService
import com.example.compose.jetchat.data.database.ChatDao
import com.example.compose.jetchat.data.database.SessionSummaryDao
import com.example.compose.jetchat.data.database.toChatMessage
import com.example.compose.jetchat.data.database.toEntity
import com.example.compose.jetchat.data.summary.ConversationSummaryManager
import com.example.compose.jetchat.data.voice.VoiceRealtimeService
import com.example.compose.jetchat.data.voice.VoiceTTSService
import com.example.compose.jetchat.data.voice.CloudVoiceRecognizer
import com.example.compose.jetchat.data.voice.DoubaoRealtimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天 ViewModel
 * 
 * 注意：需要 Application context 用于本地语音识别
 */
class ChatViewModel(
    application: Application,
    private val sessionId: String,
    private val chatDao: ChatDao,
    private val summaryDao: SessionSummaryDao,
    private val isRealtimeMode: Boolean = false,
    private val apiService: ApiService = ApiService.instance
) : AndroidViewModel(application) {
    
    // 摘要管理器
    private val summaryManager = ConversationSummaryManager(chatDao, summaryDao, apiService)
    
    // 云端语音识别（类似微信、QQ、Kimi）
    private val cloudVoiceRecognizer = CloudVoiceRecognizer(application.applicationContext)
    
    // 语音服务
    private val voiceService = VoiceRealtimeService() // WebSocket 实时对话（目前不可用）
    private val voiceTTSService = VoiceTTSService(cloudVoiceRecognizer) // TTS 语音对话（推荐）
    
    // 豆包端到端实时对话服务
    private val doubaoRealtimeService = if (isRealtimeMode) {
        DoubaoRealtimeService(application.applicationContext)
    } else {
        null
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    // 语音对话状态
    private val _isVoiceRecording = MutableStateFlow(false)
    val isVoiceRecording: StateFlow<Boolean> = _isVoiceRecording.asStateFlow()
    
    val isVoiceRecognizing: StateFlow<Boolean> = cloudVoiceRecognizer.isRecognizing
    
    private val _voiceTranscription = MutableStateFlow("")
    val voiceTranscription: StateFlow<String> = _voiceTranscription.asStateFlow()
    
    // 语音对话模式
    private val _voiceMode = MutableStateFlow(com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE)
    val voiceMode: StateFlow<com.example.compose.jetchat.config.AppConfig.VoiceMode> = _voiceMode.asStateFlow()
    
    // Snackbar 消息
    private val _snackbarMessage = MutableStateFlow("")
    val snackbarMessage: StateFlow<String> = _snackbarMessage.asStateFlow()

    private var messageIdCounter = 0L
    
    // 语音识别监听器的 Job，用于取消旧的监听器
    private var voiceRecognitionJob: Job? = null
    
    // 防抖：记录上次发送的语音内容，避免重复发送
    private var lastVoiceTranscription: String = ""

    init {
        // 从数据库加载历史消息
        loadMessagesFromDatabase()
    }

    /**
     * 从数据库加载消息
     */
    private fun loadMessagesFromDatabase() {
        viewModelScope.launch {
            try {
                val entities = withContext(Dispatchers.IO) {
                    chatDao.getMessagesBySessionId(sessionId)
                }
                // 过滤掉系统占位消息
                _messages.value = entities
                    .filter { it.role != "system" }
                    .map { it.toChatMessage() }
                
                // 更新消息 ID 计数器
                if (entities.isNotEmpty()) {
                    messageIdCounter = entities.maxOf { it.id } + 1
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 发送消息
     */
    fun sendMessage(content: String, imageBase64: String? = null) {
        val userMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content,
            status = MessageStatus.SENT,
            imageBase64 = imageBase64
        )

        // 立即添加用户消息（追加到末尾）
        _messages.value = _messages.value + listOf(userMessage)

        // 添加加载中的消息（追加到末尾）
        val loadingMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.LOADING
        )
        _messages.value = _messages.value + listOf(loadingMessage)

        // 调用 API
        viewModelScope.launch {
            try {
                // 调用 API（使用带摘要的多轮对话）
                val apiResponse = withContext(Dispatchers.IO) {
                    // 获取包含摘要的消息历史
                    val conversationHistory = summaryManager.getMessagesWithSummary(sessionId, content)
                    
                    // 发送多轮对话请求，传入当前用户输入用于判断是否生成图片
                    apiService.sendChatRequestWithHistory(conversationHistory, content, imageBase64)
                }
                
                // 🎙️ 实时对话模式：获取文本回复后，调用TTS转语音
                if (isRealtimeMode && apiResponse.text.isNotEmpty()) {
                    launch {
                        try {
                            android.util.Log.d("ChatViewModel", "🎙️ 实时模式: 调用TTS转语音")
                            val ttsAudioFile = withContext(Dispatchers.IO) {
                                voiceTTSService.textToSpeech(apiResponse.text)
                            }
                            if (ttsAudioFile != null) {
                                android.util.Log.d("ChatViewModel", "✓ TTS生成成功: ${ttsAudioFile.absolutePath}")
                            } else {
                                android.util.Log.w("ChatViewModel", "⚠ TTS生成失败，返回null")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "TTS失败: ${e.message}")
                        }
                    }
                }
                
                // 检查是否需要生成摘要（后台异步进行，不阻塞）
                launch {
                    if (summaryManager.shouldGenerateSummary(sessionId)) {
                        android.util.Log.d("ChatViewModel", "触发摘要生成...")
                        summaryManager.generateSummary(sessionId)
                    }
                }

                // 移除加载中的消息
                _messages.value = _messages.value.filter { it.id != loadingMessage.id }

                // 创建 AI 消息（初始为空）
                val aiMessageId = messageIdCounter++
                val aiMessage = ChatMessage(
                    id = aiMessageId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    imageBase64 = apiResponse.imageBase64,  // 先显示图片（如果有）
                    status = MessageStatus.SENT
                )
                _messages.value = _messages.value + listOf(aiMessage)
                
                // 打字机效果：逐字显示文本
                val fullText = apiResponse.text
                val typingSpeed = 30L  // 每个字延迟 30ms
                
                fullText.forEachIndexed { index, _ ->
                    kotlinx.coroutines.delay(typingSpeed)
                    val currentText = fullText.substring(0, index + 1)
                    
                    // 更新消息内容
                    _messages.value = _messages.value.map { msg ->
                        if (msg.id == aiMessageId) {
                            msg.copy(content = currentText)
                        } else {
                            msg
                        }
                    }
                }
                
                // 获取最终的完整消息（用于保存到数据库）
                val finalAiMessage = _messages.value.find { it.id == aiMessageId }!!.copy(
                    content = fullText
                )

                // 保存用户消息和 AI 回复到数据库
                withContext(Dispatchers.IO) {
                    // 查询现有消息（在保存之前）
                    val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                    
                    // 判断是否需要设置新标题
                    val shouldSetNewTitle = existingMessages.isEmpty() || 
                                          existingMessages.first().sessionTitle == "新对话"
                    
                    if (shouldSetNewTitle) {
                        // 第一条消息或标题还是"新对话"：使用用户输入的文本作为会话标题
                        val sessionTitle = content.take(15)
                        android.util.Log.d("ChatViewModel", "设置会话标题: $sessionTitle")
                        chatDao.insertMessage(userMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = false))
                        chatDao.insertMessage(finalAiMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = false))
                        
                        // 如果之前有"新对话"的消息，更新它们的标题
                        if (existingMessages.isNotEmpty()) {
                            chatDao.updateSessionTitle(sessionId, sessionTitle)
                        }
                    } else {
                        // 后续消息：继承第一条消息的标题和置顶状态
                        val firstMessage = existingMessages.first()
                        android.util.Log.d("ChatViewModel", "继承标题: ${firstMessage.sessionTitle}, 置顶: ${firstMessage.isPinned}")
                        chatDao.insertMessage(userMessage.toEntity().copy(
                            sessionTitle = firstMessage.sessionTitle,
                            isPinned = firstMessage.isPinned
                        ))
                        chatDao.insertMessage(finalAiMessage.toEntity().copy(
                            sessionTitle = firstMessage.sessionTitle,
                            isPinned = firstMessage.isPinned
                        ))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                
                // 移除加载中的消息
                _messages.value = _messages.value.filter { it.id != loadingMessage.id }

                // 更新用户消息为错误状态
                _messages.value = _messages.value.map {
                    if (it.id == userMessage.id) {
                        it.copy(status = MessageStatus.ERROR)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * 重试发送消息
     */
    fun retryMessage(message: ChatMessage) {
        if (message.status == MessageStatus.ERROR && message.role == MessageRole.USER) {
            // 移除错误的消息
            _messages.value = _messages.value.filter { it.id != message.id }
            // 重新发送
            sendMessage(message.content)
        }
    }
    
    /**
     * 发送包含文档的消息
     */
    fun sendMessageWithDocument(content: String, documentName: String?, documentContent: String?) {
        if (documentContent == null) {
            sendMessage(content)
            return
        }
        
        // 创建包含文档的用户消息
        val userMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content.ifBlank { "请分析这个文档的内容" },
            status = MessageStatus.SENT,
            documentName = documentName,
            documentContent = documentContent
        )
        
        // 立即添加用户消息
        _messages.value = _messages.value + listOf(userMessage)
        
        // 保存到数据库
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                chatDao.insertMessage(userMessage.toEntity())
            }
        }
        
        // 创建 AI 加载消息
        val aiLoadingMessage = ChatMessage(
            id = messageIdCounter++,
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.LOADING
        )
        _messages.value = _messages.value + listOf(aiLoadingMessage)
        
        // 请求 AI 回复（发送文档内容作为上下文）
        viewModelScope.launch {
            try {
                // 构造发送给 AI 的消息内容
                val messageToAI = if (content.isNotBlank()) {
                    "[$documentName]\n\n$content"
                } else {
                    "[$documentName]\n\n请分析这个文档的内容"
                }
                
                // 调用 API（使用带摘要的多轮对话）
                val apiResponse = withContext(Dispatchers.IO) {
                    // 获取包含摘要的消息历史
                    val conversationHistory = summaryManager.getMessagesWithSummary(sessionId, messageToAI)
                    
                    // 发送多轮对话请求
                    apiService.sendChatRequestWithHistory(conversationHistory, messageToAI, null)
                }
                
                // 移除加载消息
                _messages.value = _messages.value.filter { it.id != aiLoadingMessage.id }
                
                // 创建 AI 消息
                val aiMessageId = messageIdCounter++
                val aiMessage = ChatMessage(
                    id = aiMessageId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    imageBase64 = apiResponse.imageBase64,
                    status = MessageStatus.SENT
                )
                _messages.value = _messages.value + listOf(aiMessage)
                
                // 打字机效果：逐字显示文本
                val fullText = apiResponse.text
                val typingSpeed = 30L
                
                fullText.forEachIndexed { index, _ ->
                    kotlinx.coroutines.delay(typingSpeed)
                    val currentText = fullText.substring(0, index + 1)
                    
                    _messages.value = _messages.value.map { msg ->
                        if (msg.id == aiMessageId) {
                            msg.copy(content = currentText)
                        } else {
                            msg
                        }
                    }
                }
                
                // 保存到数据库
                val finalAiMessage = _messages.value.find { it.id == aiMessageId }!!
                withContext(Dispatchers.IO) {
                    chatDao.insertMessage(userMessage.toEntity())
                    chatDao.insertMessage(finalAiMessage.toEntity())
                }
                
                // 检查是否需要生成摘要
                launch {
                    if (summaryManager.shouldGenerateSummary(sessionId)) {
                        summaryManager.generateSummary(sessionId)
                    }
                }
                
            } catch (e: Exception) {
                // 错误处理
                val errorMessage = aiLoadingMessage.copy(
                    content = "发送失败: ${e.message}",
                    status = MessageStatus.ERROR
                )
                
                _messages.value = _messages.value.map { 
                    if (it.id == aiLoadingMessage.id) errorMessage else it 
                }
            }
        }
    }
    
    /**
     * 开始语音录音
     * 
     * 根据当前模式选择：
     * - SIMPLE: 使用 Whisper 语音识别（录音 → 识别 → 文字）
     * - REALTIME: 使用实时语音对话（音频流 → 音频流）
     */
    fun startVoiceRecording() {
        android.util.Log.d("ChatViewModel", "开始语音录音，模式: ${_voiceMode.value}")
        
        // 清空上次记录，为新的识别做准备
        lastVoiceTranscription = ""
        
        viewModelScope.launch {
            _isVoiceRecording.value = true
            
            // 根据模式选择不同的语音服务
            when (_voiceMode.value) {
                com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> {
                    android.util.Log.d("ChatViewModel", "使用简单模式：Whisper 语音识别")
                    startCloudVoiceRecognition()
                }
                com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> {
                    android.util.Log.d("ChatViewModel", "使用实时模式：端到端语音对话")
                    startRealtimeVoiceConversation()
                }
            }
        }
    }
    
    /**
     * 切换语音对话模式
     */
    fun toggleVoiceMode() {
        _voiceMode.value = when (_voiceMode.value) {
            com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> 
                com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME
            com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> 
                com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE
        }
        
        val modeName = when (_voiceMode.value) {
            com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> "简单模式（语音识别）"
            com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> "实时模式（端到端对话）"
        }
        _snackbarMessage.value = "已切换到：$modeName"
        
        android.util.Log.d("ChatViewModel", "语音模式已切换: ${_voiceMode.value}")
    }
    
    /**
     * 启动豆包端到端实时对话
     */
    fun startDoubaoRealtimeConversation(
        botName: String = "豆包",
        systemRole: String = "",
        speakingStyle: String = ""
    ) {
        // 取消之前的监听器，避免重复发送
        voiceRecognitionJob?.cancel()
        
        voiceRecognitionJob = viewModelScope.launch {
            doubaoRealtimeService?.let { service ->
                android.util.Log.d("ChatViewModel", "启动豆包端到端实时对话")
                
                service.startRealtimeConversation(botName, systemRole, speakingStyle)
                
                // 监听录音状态
                launch {
                    service.isRecording.collect { isRecording ->
                        _isVoiceRecording.value = isRecording
                    }
                }
                
                // 监听连接状态
                launch {
                    service.connectionState.collect { state ->
                        _snackbarMessage.value = state
                    }
                }
                
                // 监听ASR识别结果（用户说话），直接渲染为用户消息
                launch {
                    service.userSpeechCompleted.collect { finalText ->
                        if (finalText != null && finalText.isNotEmpty() && finalText != lastVoiceTranscription) {
                            lastVoiceTranscription = finalText
                            android.util.Log.d("ChatViewModel", "✓ 豆包-用户说话: $finalText")
                            
                            // 添加用户消息气泡（右边）
                            val userMessage = ChatMessage(
                                id = messageIdCounter++,
                                sessionId = sessionId,
                                role = MessageRole.USER,
                                content = finalText,
                                status = MessageStatus.SENT
                            )
                            _messages.value = _messages.value + listOf(userMessage)
                            
                            // 保存到数据库
                            viewModelScope.launch {
                                withContext(Dispatchers.IO) {
                                    val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                                    val shouldSetNewTitle = existingMessages.isEmpty() || existingMessages.first().sessionTitle == "新对话"
                                    
                                    if (shouldSetNewTitle) {
                                        val sessionTitle = "🎙️ ${finalText.take(10)}"
                                        chatDao.insertMessage(userMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = false))
                                        if (existingMessages.isNotEmpty()) {
                                            chatDao.updateSessionTitle(sessionId, sessionTitle)
                                        }
                                    } else {
                                        val firstMessage = existingMessages.first()
                                        chatDao.insertMessage(userMessage.toEntity().copy(
                                            sessionTitle = firstMessage.sessionTitle,
                                            isPinned = firstMessage.isPinned
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 监听AI回复，直接渲染为AI消息
                launch {
                    service.aiResponseCompleted.collect { aiText ->
                        if (aiText != null && aiText.isNotEmpty()) {
                            android.util.Log.d("ChatViewModel", "✓ 豆包-AI回复: $aiText")
                            
                            // 添加AI消息气泡（左边），带打字机效果
                            val aiMessageId = messageIdCounter++
                            val aiMessage = ChatMessage(
                                id = aiMessageId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = "",
                                status = MessageStatus.SENT
                            )
                            _messages.value = _messages.value + listOf(aiMessage)
                            
                            // 打字机效果
                            val typingSpeed = 30L
                            aiText.forEachIndexed { index, _ ->
                                kotlinx.coroutines.delay(typingSpeed)
                                val currentText = aiText.substring(0, index + 1)
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == aiMessageId) msg.copy(content = currentText) else msg
                                }
                            }
                            
                            // 保存到数据库
                            val finalAiMessage = _messages.value.find { it.id == aiMessageId }!!.copy(content = aiText)
                            viewModelScope.launch {
                                withContext(Dispatchers.IO) {
                                    val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                                    val firstMessage = existingMessages.first()
                                    chatDao.insertMessage(finalAiMessage.toEntity().copy(
                                        sessionTitle = firstMessage.sessionTitle,
                                        isPinned = firstMessage.isPinned
                                    ))
                                }
                            }
                        }
                    }
                }
            } ?: run {
                _snackbarMessage.value = "当前不是实时对话模式"
            }
        }
    }
    
    /**
     * 停止豆包端到端实时对话
     */
    fun stopDoubaoRealtimeConversation() {
        doubaoRealtimeService?.stopRealtimeConversation()
    }
    
    /**
     * 启动实时语音对话（REALTIME 模式 - 使用 TTS）
     */
    private fun startRealtimeVoiceConversation() {
        viewModelScope.launch {
            android.util.Log.d("ChatViewModel", "使用 TTS 模式：Whisper → Chat → TTS → 播放")
            
            // 启动录音
            voiceTTSService.startVoiceConversation()
            
            // 监听录音状态
            launch {
                voiceTTSService.isRecording.collect { isRecording ->
                    _isVoiceRecording.value = isRecording
                }
            }
            
            // 监听错误
            launch {
                voiceTTSService.error.collect { error ->
                    if (error != null) {
                        _snackbarMessage.value = error
                        android.util.Log.e("ChatViewModel", "TTS 语音对话错误: $error")
                    }
                }
            }
            
            // 实时模式不需要监听转录文本（不填充到文本框）
            // 转录文本会直接显示在语音消息气泡中
        }
    }
    
    /**
     * 启动云端语音识别（类似微信、QQ、Kimi）
     * 
     * 不依赖设备本地语音识别引擎，直接调用云端 Whisper API
     */
    private fun startCloudVoiceRecognition() {
        // 取消之前的监听器，避免重复发送
        voiceRecognitionJob?.cancel()
        
        voiceRecognitionJob = viewModelScope.launch {
            android.util.Log.d("ChatViewModel", "启动云端语音识别（Whisper API）...")
            
            // 开始录音
            cloudVoiceRecognizer.startRecording()
            
            // 监听录音状态
            launch {
                cloudVoiceRecognizer.isListening.collect { isListening ->
                    android.util.Log.d("ChatViewModel", "录音状态: $isListening")
                }
            }
            
            // 监听识别结果，直接发送
            launch {
                cloudVoiceRecognizer.transcription.collect { transcription ->
                    if (transcription.isNotEmpty() && transcription != lastVoiceTranscription) {
                        lastVoiceTranscription = transcription
                        _voiceTranscription.value = transcription
                        android.util.Log.d("ChatViewModel", "✓ 简单模式-识别结果: $transcription")
                        // 直接发送消息
                        sendMessage(transcription)
                        _voiceTranscription.value = ""
                    }
                }
            }
            
            // 监听错误
            launch {
                cloudVoiceRecognizer.error.collect { error ->
                    if (error != null) {
                        android.util.Log.e("ChatViewModel", "✗ 云端识别错误: $error")
                        _snackbarMessage.value = "语音识别失败: $error"
                        _isVoiceRecording.value = false
                    }
                }
            }
        }
    }
    
    /**
     * 停止语音录音
     */
    fun stopVoiceRecording() {
        android.util.Log.d("ChatViewModel", "停止语音录音，当前模式: ${_voiceMode.value}")
        
        viewModelScope.launch {
            when (_voiceMode.value) {
                com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> {
                    // 简单模式：停止云端录音并识别
                    cloudVoiceRecognizer.stopRecordingAndRecognize()
                }
                com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> {
                    // 实时模式：语音对话（Whisper → 意图识别 → 图片生成/Chat → TTS）
                    val result = voiceTTSService.stopVoiceConversation { transcription ->
                        // 处理语音输入（意图识别 + 内容生成）
                        val voiceData = handleVoiceInput(transcription)
                        Pair(voiceData.text, voiceData.imageBase64)
                    }
                    
                    if (result != null) {
                        // 1. 添加用户语音消息
                        val userMessage = ChatMessage(
                            id = messageIdCounter++,
                            sessionId = sessionId,
                            role = MessageRole.USER,
                            content = result.transcription, // 显示转录文本
                            status = MessageStatus.SENT,
                            audioFilePath = result.userAudioPath, // 保存录音文件路径
                            audioDuration = result.userAudioDuration
                        )
                        _messages.value = _messages.value + listOf(userMessage)
                        
                        // 2. 添加 AI 回复（可能包含图片和/或语音）
                        val aiMessage = ChatMessage(
                            id = messageIdCounter++,
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT,
                            content = result.responseText, // 显示回复文本
                            status = MessageStatus.SENT,
                            imageBase64 = result.imageBase64, // 图片（如果有）
                            audioFilePath = result.ttsAudioPath, // 保存 TTS 音频路径
                            audioDuration = result.ttsAudioDuration
                        )
                        _messages.value = _messages.value + listOf(aiMessage)
                        
                        // 3. 保存到数据库
                        withContext(Dispatchers.IO) {
                            val existingMessages = chatDao.getMessagesBySessionId(sessionId).filter { it.role != "system" }
                            val shouldSetNewTitle = existingMessages.isEmpty() || existingMessages.first().sessionTitle == "新对话"
                            
                            if (shouldSetNewTitle) {
                                val sessionTitle = result.transcription.take(15)
                                chatDao.insertMessage(userMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = false))
                                chatDao.insertMessage(aiMessage.toEntity().copy(sessionTitle = sessionTitle, isPinned = false))
                                if (existingMessages.isNotEmpty()) {
                                    chatDao.updateSessionTitle(sessionId, sessionTitle)
                                }
                            } else {
                                val firstMessage = existingMessages.first()
                                chatDao.insertMessage(userMessage.toEntity().copy(
                                    sessionTitle = firstMessage.sessionTitle,
                                    isPinned = firstMessage.isPinned
                                ))
                                chatDao.insertMessage(aiMessage.toEntity().copy(
                                    sessionTitle = firstMessage.sessionTitle,
                                    isPinned = firstMessage.isPinned
                                ))
                            }
                        }
                    }
                }
            }
            
            _isVoiceRecording.value = false
        }
    }
    
    /**
     * 处理语音输入（意图识别 + 内容生成）
     * 
     * @return VoiceResponseData 包含文本回复和可能的图片
     */
    private suspend fun handleVoiceInput(userInput: String): VoiceResponseData {
        return try {
            withContext(Dispatchers.IO) {
                // 1. 意图识别
                val intent = apiService.detectIntent(userInput)
                android.util.Log.d("ChatViewModel", "语音意图识别: $intent")
                
                when (intent) {
                    "image_generation" -> {
                        // 2a. 生成图片
                        android.util.Log.d("ChatViewModel", "检测到图片生成意图")
                        
                        // 优化图片生成 Prompt
                        val optimizedPrompt = if (com.example.compose.jetchat.config.AppConfig.OPTIMIZE_IMAGE_PROMPT) {
                            apiService.optimizeImagePrompt(userInput)
                        } else {
                            userInput
                        }
                        
                        // 生成图片
                        val imageUrl = apiService.generateImage(optimizedPrompt)
                        val imageBase64 = apiService.downloadAndEncodeImage(imageUrl)
                        
                        // 返回图片 + 简短描述
                        VoiceResponseData(
                            text = "我为你生成了这张图片。",
                            imageBase64 = imageBase64
                        )
                    }
                    else -> {
                        // 2b. 普通对话（简洁回复）
                        android.util.Log.d("ChatViewModel", "普通对话模式")
                        
                        // 获取对话历史
                        val conversationHistory = summaryManager.getMessagesWithSummary(sessionId, userInput)
                        
                        // 添加语音对话专用的系统提示
                        val voiceSystemPrompt = """
                            你是一个语音助手，请用简洁、自然的口语回答用户问题。
                            要求：
                            1. 回答要简洁，控制在2-3句话以内
                            2. 使用口语化的表达，避免书面语
                            3. 重点突出，不要展开过多细节
                            4. 语气要友好、自然
                        """.trimIndent()
                        
                        // 发送请求（带语音优化提示）
                        val response = apiService.sendChatRequestWithVoiceOptimization(
                            conversationHistory, 
                            userInput, 
                            voiceSystemPrompt
                        )
                        
                        VoiceResponseData(
                            text = response.text,
                            imageBase64 = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val errorDetails = buildString {
                append("语音处理失败:\n")
                append("错误类型: ${e.javaClass.simpleName}\n")
                append("错误信息: ${e.message ?: "未知错误"}\n")
                if (e.cause != null) {
                    append("原因: ${e.cause?.message}\n")
                }
            }
            android.util.Log.e("ChatViewModel", errorDetails, e)
            e.printStackTrace()
            
            VoiceResponseData(
                text = "抱歉，我遇到了一些问题：${e.message ?: "未知错误"}",
                imageBase64 = null
            )
        }
    }
    
    /**
     * 语音响应数据
     */
    private data class VoiceResponseData(
        val text: String,
        val imageBase64: String?
    )
    
    /**
     * 切换语音消息的文字展开状态
     */
    fun toggleVoiceText(messageId: Long) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(isTextExpanded = !message.isTextExpanded)
            } else {
                message
            }
        }
    }
    
    /**
     * 清除 Snackbar 消息
     */
    fun clearSnackbarMessage() {
        _snackbarMessage.value = ""
    }
    
    /**
     * 清理资源
     */
    override fun onCleared() {
        super.onCleared()
        voiceService.cleanup()
        voiceTTSService.stopAll()
        cloudVoiceRecognizer.cleanup()
    }
}

/**
 * ViewModel 工厂
 */
class ChatViewModelFactory(
    private val application: Application,
    private val sessionId: String,
    private val chatDao: ChatDao,
    private val summaryDao: SessionSummaryDao,
    private val isRealtimeMode: Boolean = false
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(application, sessionId, chatDao, summaryDao, isRealtimeMode) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
