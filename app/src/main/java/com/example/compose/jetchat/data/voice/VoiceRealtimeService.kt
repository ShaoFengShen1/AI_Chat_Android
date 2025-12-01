package com.example.compose.jetchat.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.compose.jetchat.config.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 实时语音对话服务
 * 
 * 使用 gpt-4o-realtime-preview 模型进行实时语音交互
 * 核心功能：
 * - 语音识别（Speech-to-Text）使用 Whisper
 * - GPT 对话处理（实时推理）
 * - 文本转语音（Text-to-Speech）实时合成
 * - 音频流处理（低延迟缓冲队列）
 * 
 * 技术优化：
 * - WebRTC 级别的音频采集优化
 * - 音频分片处理（160ms/片，低延迟）
 * - 智能缓冲队列（平滑播放）
 * - 自动重连机制
 */
class VoiceRealtimeService {
    
    companion object {
        private const val TAG = "VoiceRealtimeService"
        
        // 音频参数（WebRTC 优化）
        private const val SAMPLE_RATE = 24000 // 24kHz（realtime API 推荐）
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2 // 降低延迟
        
        // 音频分片参数（低延迟优化）
        private const val CHUNK_DURATION_MS = 100 // 100ms/片
        private const val CHUNK_SIZE = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000) * 2 // bytes
        
        // 播放缓冲队列大小
        private const val PLAYBACK_QUEUE_SIZE = 10
        
        // 重连参数
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1000L
    }
    
    // WebSocket 连接（带重连机制）
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.WEBSOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(AppConfig.WEBSOCKET_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(AppConfig.WEBSOCKET_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    // 音频录制（WebRTC 优化）
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    
    // 音频播放（TTS）
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val audioPlaybackQueue = ConcurrentLinkedQueue<ByteArray>()
    
    // 对话管理
    private var conversationId: String? = null
    private var sessionId: String? = null
    private var hasReceivedServerMessage = false  // 是否收到过服务器消息
    
    // 状态管理
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription
    
    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // 连接状态枚举
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR
    }
    
    /**
     * 启动实时语音对话（带智能重连）
     */
    fun startRealtimeConversation(onError: (String) -> Unit) {
        if (_connectionState.value == ConnectionState.CONNECTED || 
            _connectionState.value == ConnectionState.CONNECTING) {
            Log.w(TAG, "已经在连接中，忽略重复请求")
            return
        }
        
        connectWebSocket(onError)
    }
    
    /**
     * 建立 WebSocket 连接（核心方法）
     */
    private fun connectWebSocket(onError: (String) -> Unit) {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            
            // 构建 WebSocket 请求
            val request = Request.Builder()
                .url(AppConfig.VOICE_WEBSOCKET_URL)
                .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()
            
            // 建立 WebSocket 连接
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✓ WebSocket 连接已建立")
                    Log.d(TAG, "响应码: ${response.code}, 协议: ${response.protocol}")
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectAttempts = 0 // 重置重连计数
                    hasReceivedServerMessage = false  // 重置消息接收标志
                    
                    // 设置超时检测：如果 5 秒内没收到任何消息，说明 API 不支持
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000)
                        if (!hasReceivedServerMessage && _connectionState.value == ConnectionState.CONNECTED) {
                            Log.e(TAG, "✗ 5秒内未收到任何服务器响应")
                            Log.e(TAG, "✗ VectorEngine 不支持 Realtime API")
                            Log.d(TAG, "建议：")
                            Log.d(TAG, "  1. 使用 OpenAI 官方 API")
                            Log.d(TAG, "  2. 或使用本地语音识别（已自动降级）")
                            onError("API 提供商不支持 Realtime API\n已自动切换到本地语音识别")
                            webSocket.close(1000, "API不支持")
                        }
                    }
                    
                    // 发送会话配置（优化参数）
                    val config = JSONObject().apply {
                        put("type", "session.update")
                        put("session", JSONObject().apply {
                            // 多模态配置
                            put("modalities", JSONArray().apply {
                                put("text")
                                put("audio")
                            })
                            
                            // AI 指令
                            put("instructions", """
                                你是一个友好、专业的AI助手。
                                - 使用中文回答用户问题
                                - 回答要简洁准确
                                - 保持自然的对话语气
                                - 如果不确定，诚实告知
                            """.trimIndent())
                            
                            // 音频配置（24kHz PCM16）
                            put("voice", "alloy") // 可选: alloy, echo, fable, onyx, nova, shimmer
                            put("input_audio_format", "pcm16")
                            put("output_audio_format", "pcm16")
                            put("turn_detection", JSONObject().apply {
                                put("type", "server_vad") // 服务端 VAD（语音活动检测）
                                put("threshold", 0.5)
                                put("prefix_padding_ms", 300)
                                put("silence_duration_ms", 500)
                            })
                            
                            // 启用实时转录
                            put("input_audio_transcription", JSONObject().apply {
                                put("model", "whisper-1")
                            })
                            
                            // 温度参数（创造性）
                            put("temperature", 0.8)
                            put("max_response_output_tokens", 4096)
                        })
                    }
                    
                    Log.d(TAG, "→ 发送会话配置: ${config.toString().take(200)}...")
                    val sent = webSocket.send(config.toString())
                    if (sent) {
                        Log.d(TAG, "✓ 会话配置已发送")
                        Log.d(TAG, "等待服务器响应（session.created 或 session.updated）...")
                        // 初始化音频播放
                        initAudioPlayback()
                        // 开始录音
                        startAudioRecording(webSocket)
                    } else {
                        Log.e(TAG, "✗ 会话配置发送失败")
                        onError("配置发送失败")
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    hasReceivedServerMessage = true  // 标记已收到消息
                    val preview = if (text.length > 200) "${text.take(200)}..." else text
                    Log.d(TAG, "← 收到文本消息: $preview")
                    handleMessage(text)
                }
                
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "← 收到音频: ${bytes.size} bytes")
                    // 将音频数据加入播放队列
                    audioPlaybackQueue.offer(bytes.toByteArray())
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 正在关闭: $code - $reason")
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭: $code - $reason")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    stopAudioRecordingHardware()
                    stopAudioPlayback()
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "✗ WebSocket 连接失败: ${t.message}", t)
                    Log.e(TAG, "响应代码: ${response?.code}, 响应消息: ${response?.message}")
                    _connectionState.value = ConnectionState.ERROR
                    
                    // 判断错误类型
                    val errorMessage = when {
                        t is java.net.SocketTimeoutException -> {
                            "连接超时。可能原因：\n1. API 提供商不支持 Realtime API\n2. 网络连接不稳定\n3. 需要配置代理"
                        }
                        response?.code == 404 -> {
                            "API 端点不存在。VectorEngine 可能不支持 Realtime API。\n建议使用 OpenAI 官方 API 或其他支持的提供商。"
                        }
                        response?.code == 401 || response?.code == 403 -> {
                            "API Key 无效或无权限访问 Realtime API"
                        }
                        else -> {
                            "连接失败: ${t.message}"
                        }
                    }
                    
                    // 自动重连机制（仅对临时错误）
                    if (t is java.net.SocketTimeoutException && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++
                        Log.d(TAG, "尝试重连 ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")
                        _connectionState.value = ConnectionState.RECONNECTING
                        
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(RECONNECT_DELAY_MS * reconnectAttempts)
                            connectWebSocket(onError)
                        }
                    } else {
                        onError(errorMessage)
                        stopAudioRecordingHardware()
                        stopAudioPlayback()
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "启动语音对话失败: ${e.message}", e)
            _connectionState.value = ConnectionState.ERROR
            onError("启动失败: ${e.message}")
        }
    }
    
    /**
     * 停止实时语音对话
     */
    fun stopRealtimeConversation() {
        try {
            Log.d(TAG, "停止语音对话...")
            
            // 1. 提交音频缓冲区（在关闭 WebSocket 之前！）
            webSocket?.send(JSONObject().apply {
                put("type", "input_audio_buffer.commit")
            }.toString())
            Log.d(TAG, "→ 已提交音频缓冲区")
            
            // 2. 请求 AI 生成响应
            webSocket?.send(JSONObject().apply {
                put("type", "response.create")
                put("response", JSONObject().apply {
                    put("modalities", JSONArray().apply {
                        put("text")
                        put("audio")
                    })
                })
            }.toString())
            Log.d(TAG, "→ 已请求生成响应")
            
            // 3. 停止录音硬件（不再发送 WebSocket 消息）
            stopAudioRecordingHardware()
            
            // 4. 停止播放
            stopAudioPlayback()
            
            // 5. 延迟一下，等待服务器响应
            Thread.sleep(500)
            
            // 6. 关闭 WebSocket
            webSocket?.close(1000, "用户停止")
            webSocket = null
            
            _isRecording.value = false
            _connectionState.value = ConnectionState.DISCONNECTED
            reconnectAttempts = 0
            
            // 清空状态
            _transcription.value = ""
            _response.value = ""
            conversationId = null
            sessionId = null
            
            Log.d(TAG, "✓ 语音对话已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止语音对话失败: ${e.message}", e)
        }
    }
    
    /**
     * 开始录音并发送音频流（WebRTC 级别优化）
     */
    @Suppress("MissingPermission")
    private fun startAudioRecording(webSocket: WebSocket) {
        try {
            // 计算最小缓冲区大小
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            
            // 使用较小的缓冲区以降低延迟
            val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 2)
            
            Log.d(TAG, "音频配置: $SAMPLE_RATE Hz, buffer=$bufferSize bytes, chunk=$CHUNK_SIZE bytes")
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // WebRTC 优化源
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "✗ AudioRecord 初始化失败")
                return
            }
            
            audioRecord?.startRecording()
            _isRecording.value = true
            
            Log.d(TAG, "✓ 音频录制已启动（低延迟模式）")
            
            // 在协程中读取音频数据并分片发送
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(CHUNK_SIZE)
                var sentChunks = 0
                
                while (isActive && _isRecording.value) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (readBytes > 0) {
                        // Base64 编码（高效处理）
                        val base64Audio = android.util.Base64.encodeToString(
                            buffer.copyOf(readBytes),
                            android.util.Base64.NO_WRAP
                        )
                        
                        // 构建音频消息（JSON）
                        val audioMessage = JSONObject().apply {
                            put("type", "input_audio_buffer.append")
                            put("audio", base64Audio)
                        }
                        
                        // 发送到 WebSocket
                        val sent = webSocket.send(audioMessage.toString())
                        if (sent) {
                            sentChunks++
                            if (sentChunks % 10 == 0) {
                                Log.d(TAG, "→ 已发送 $sentChunks 个音频片段")
                            }
                        } else {
                            Log.w(TAG, "✗ 音频片段发送失败")
                        }
                    } else if (readBytes < 0) {
                        Log.w(TAG, "音频读取错误: $readBytes")
                        break
                    }
                }
                
                // 提交音频缓冲区（触发识别）
                webSocket.send(JSONObject().apply {
                    put("type", "input_audio_buffer.commit")
                }.toString())
                
                Log.d(TAG, "✓ 音频录制已停止，共发送 $sentChunks 个片段")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "启动音频录制失败: ${e.message}", e)
            _isRecording.value = false
        }
    }
    
    /**
     * 停止录音硬件（仅停止 AudioRecord，不发送 WebSocket 消息）
     */
    private fun stopAudioRecordingHardware() {
        try {
            recordingJob?.cancel()
            recordingJob = null
            
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            _isRecording.value = false
            
            Log.d(TAG, "✓ 音频录制硬件已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止音频录制失败: ${e.message}", e)
        }
    }
    
    /**
     * 初始化音频播放（TTS）
     */
    private fun initAudioPlayback() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack?.play()
            _isPlaying.value = true
            
            // 启动播放协程（从队列中消费音频）
            playbackJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive && _isPlaying.value) {
                    val audioData = audioPlaybackQueue.poll()
                    if (audioData != null) {
                        audioTrack?.write(audioData, 0, audioData.size)
                    } else {
                        delay(10) // 队列为空时短暂等待
                    }
                }
            }
            
            Log.d(TAG, "✓ 音频播放已初始化")
        } catch (e: Exception) {
            Log.e(TAG, "初始化音频播放失败: ${e.message}", e)
        }
    }
    
    /**
     * 停止音频播放
     */
    private fun stopAudioPlayback() {
        try {
            _isPlaying.value = false
            
            playbackJob?.cancel()
            playbackJob = null
            
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            
            audioPlaybackQueue.clear()
            
            Log.d(TAG, "✓ 音频播放已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止音频播放失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理接收到的消息（智能路由）
     */
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            
            when (type) {
                // 会话创建
                "session.created" -> {
                    sessionId = json.optJSONObject("session")?.optString("id")
                    Log.d(TAG, "✓ 会话已创建: $sessionId")
                }
                
                // 会话更新
                "session.updated" -> {
                    Log.d(TAG, "✓ 会话配置已更新")
                }
                
                // 对话创建
                "conversation.created" -> {
                    conversationId = json.optJSONObject("conversation")?.optString("id")
                    Log.d(TAG, "✓ 对话已创建: $conversationId")
                }
                
                // 输入音频缓冲开始
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "🎤 检测到语音开始")
                }
                
                // 输入音频缓冲结束
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🎤 检测到语音结束")
                }
                
                // 输入音频缓冲提交
                "input_audio_buffer.committed" -> {
                    Log.d(TAG, "✓ 音频缓冲已提交")
                }
                
                // 语音转文字完成
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript")
                    _transcription.value = transcript
                    Log.d(TAG, "📝 转录完成: $transcript")
                }
                
                // 语音转文字失败
                "conversation.item.input_audio_transcription.failed" -> {
                    val error = json.optJSONObject("error")
                    Log.e(TAG, "✗ 转录失败: ${error?.optString("message")}")
                }
                
                // 响应创建
                "response.created" -> {
                    val responseId = json.optJSONObject("response")?.optString("id")
                    Log.d(TAG, "✓ AI 响应已创建: $responseId")
                }
                
                // 响应开始
                "response.output_item.added" -> {
                    Log.d(TAG, "🤖 AI 开始回复...")
                }
                
                // 响应文本增量（打字机效果）
                "response.text.delta" -> {
                    val delta = json.optString("delta")
                    _response.value += delta
                    Log.d(TAG, "💬 文字: $delta")
                }
                
                // 响应文本完成
                "response.text.done" -> {
                    val text = json.optString("text")
                    Log.d(TAG, "✓ 文字回复完成: $text")
                }
                
                // 响应音频转录增量
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta")
                    _response.value += delta
                    Log.d(TAG, "🔊 音频转录: $delta")
                }
                
                // 响应音频增量（TTS）
                "response.audio.delta" -> {
                    val audioBase64 = json.optString("delta")
                    if (audioBase64.isNotEmpty()) {
                        // 解码 base64 音频并加入播放队列
                        val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP)
                        audioPlaybackQueue.offer(audioBytes)
                        Log.d(TAG, "🔊 音频片段: ${audioBytes.size} bytes（队列: ${audioPlaybackQueue.size}）")
                    }
                }
                
                // 响应音频完成
                "response.audio.done" -> {
                    Log.d(TAG, "✓ 音频回复完成")
                }
                
                // 响应完成
                "response.done" -> {
                    val response = json.optJSONObject("response")
                    val status = response?.optString("status")
                    Log.d(TAG, "✓ AI 响应完成: $status")
                }
                
                // 速率限制
                "rate_limits.updated" -> {
                    val limits = json.optJSONObject("rate_limits")
                    Log.d(TAG, "⚠️ 速率限制更新: $limits")
                }
                
                // 错误处理
                "error" -> {
                    val error = json.optJSONObject("error")
                    val code = error?.optString("code")
                    val errorMessage = error?.optString("message") ?: "未知错误"
                    Log.e(TAG, "✗ API 错误 [$code]: $errorMessage")
                }
                
                else -> {
                    Log.d(TAG, "未处理的消息类型: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopRealtimeConversation()
    }
}
