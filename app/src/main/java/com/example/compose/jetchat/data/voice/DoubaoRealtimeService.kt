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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 豆包端到端实时语音对话服务
 * 
 * 基于豆包 Realtime API 实现端到端语音对话
 * 使用自定义二进制协议进行通信
 */
class DoubaoRealtimeService(private val appContext: android.content.Context) {
    
    companion object {
        private const val TAG = "DoubaoRealtime"
        
        // 音频配置（客户端上传）
        private const val SAMPLE_RATE_16K = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING_16BIT = AudioFormat.ENCODING_PCM_16BIT
        
        // 音频配置（服务端返回 - OGG Opus 或 PCM）
        private const val SAMPLE_RATE_24K = 24000
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        
        // 事件ID定义
        private const val EVENT_START_CONNECTION = 1
        private const val EVENT_FINISH_CONNECTION = 2
        private const val EVENT_START_SESSION = 100
        private const val EVENT_FINISH_SESSION = 102
        private const val EVENT_TASK_REQUEST = 200  // 上传音频
        private const val EVENT_SAY_HELLO = 300
        private const val EVENT_CHAT_TEXT_QUERY = 501
        
        // 服务端事件
        private const val EVENT_CONNECTION_STARTED = 50
        private const val EVENT_SESSION_STARTED = 150
        private const val EVENT_TTS_RESPONSE = 352  // 音频响应
        private const val EVENT_ASR_INFO = 450  // 识别到首字（用于打断）
        private const val EVENT_ASR_RESPONSE = 451  // 识别结果
        private const val EVENT_ASR_ENDED = 459
        private const val EVENT_CHAT_RESPONSE = 550
        private const val EVENT_CHAT_ENDED = 559  // 文本响应
        
        // Message Type
        private const val MSG_TYPE_FULL_CLIENT_REQUEST: Byte = 0x01
        private const val MSG_TYPE_FULL_SERVER_RESPONSE: Byte = 0x09
        private const val MSG_TYPE_AUDIO_ONLY_REQUEST: Byte = 0x02
        private const val MSG_TYPE_AUDIO_ONLY_RESPONSE: Byte = 0x0B
        private const val MSG_TYPE_ERROR: Byte = 0x0F
        
        // Protocol Header
        private const val PROTOCOL_VERSION: Byte = 0x11  // 0b00010001
        private const val HEADER_SIZE: Byte = 0x04
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(AppConfig.WEBSOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // 实时流不限制读取超时
        .pingInterval(AppConfig.WEBSOCKET_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()
    
    // 统一管理协程生命周期，防止悬空引用
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var webSocket: WebSocket? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private val audioTrackLock = Any()  // 同步锁，防止并发访问
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription
    
    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText
    
    private val _connectionState = MutableStateFlow("未连接")
    val connectionState: StateFlow<String> = _connectionState
    
    // 用户说话结束事件（完整的ASR结果）
    private val _userSpeechCompleted = MutableStateFlow<String?>(null)
    val userSpeechCompleted: StateFlow<String?> = _userSpeechCompleted
    
    // AI回复完成事件（完整的Chat结果）
    private val _aiResponseCompleted = MutableStateFlow<String?>(null)
    val aiResponseCompleted: StateFlow<String?> = _aiResponseCompleted
    
    private var sessionId: String = ""
    private var connectId: String = ""
    private var isSessionActive = false
    
    // 音频缓冲队列（用于播放）
    private val audioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    
    /**
     * 启动端到端实时对话
     */
    fun startRealtimeConversation(
        botName: String = "豆包",
        systemRole: String = "",
        speakingStyle: String = ""
    ) {
        if (_isRecording.value) {
            Log.w(TAG, "对话已在进行中")
            return
        }
        
        connectId = UUID.randomUUID().toString()
        sessionId = UUID.randomUUID().toString()
        
        Log.d(TAG, "启动豆包端到端实时对话")
        Log.d(TAG, "Connect ID: $connectId")
        Log.d(TAG, "Session ID: $sessionId")
        Log.d(TAG, "WebSocket URL: ${AppConfig.DOUBAO_WEBSOCKET_URL}")
        Log.d(TAG, "App ID: ${AppConfig.DOUBAO_APP_ID}")
        
        // 检查配置
        if (AppConfig.DOUBAO_APP_ID == "YOUR_APP_ID" || AppConfig.DOUBAO_APP_ID.isBlank()) {
            val error = "请先在 AppConfig.kt 中配置 DOUBAO_APP_ID"
            Log.e(TAG, "❌ $error")
            _connectionState.value = error
            return
        }
        if (AppConfig.DOUBAO_ACCESS_KEY == "YOUR_ACCESS_KEY" || AppConfig.DOUBAO_ACCESS_KEY.isBlank()) {
            val error = "请先在 AppConfig.kt 中配置 DOUBAO_ACCESS_KEY\n" +
                        "获取方式: 火山引擎控制台 → 语音技术 → 豆包端到端实时语音"
            Log.e(TAG, "❌ $error")
            _connectionState.value = error
            return
        }
        
        Log.d(TAG, "📋 配置检查通过:")
        Log.d(TAG, "   App ID: ${AppConfig.DOUBAO_APP_ID}")
        Log.d(TAG, "   Access Key: ${AppConfig.DOUBAO_ACCESS_KEY.take(10)}***")
        
        _connectionState.value = "正在连接..."
        
        val request = try {
            Request.Builder()
                .url(AppConfig.DOUBAO_WEBSOCKET_URL)
                .addHeader("X-Api-App-ID", AppConfig.DOUBAO_APP_ID)
                .addHeader("X-Api-Access-Key", AppConfig.DOUBAO_ACCESS_KEY)
                .addHeader("X-Api-Resource-Id", "volc.speech.dialog")
                .addHeader("X-Api-App-Key", "PlgvMymc7f3tQnJ6")
                .addHeader("X-Api-Connect-Id", connectId)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "❌ 创建请求失败: ${e.message}", e)
            _connectionState.value = "连接失败: ${e.message}"
            return
        }
        
        Log.d(TAG, "→ 正在建立 WebSocket 连接...")
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val logid = response.header("X-Tt-Logid") ?: "未获取"
                Log.d(TAG, "✅ WebSocket 连接成功")
                Log.d(TAG, "📝 服务端 LogID: $logid (用于问题排查)")
                _connectionState.value = "已连接"
                
                // 1. 发送 StartConnection 事件
                sendStartConnection()
                
                // 2. 发送 StartSession 事件
                sendStartSession(botName, systemRole, speakingStyle)
                
                // 3. 启动录音
                startAudioRecording()
                
                // 4. 启动播放
                startAudioPlayback()
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = buildString {
                    append("连接失败: ")
                    append(t.message ?: "未知错误")
                    if (response != null) {
                        append("\n响应码: ${response.code}")
                        append("\n响应消息: ${response.message}")
                    }
                }
                Log.e(TAG, "❌ WebSocket $errorMsg", t)
                _connectionState.value = errorMsg
                cleanup()
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 已关闭: $reason")
                _connectionState.value = "已断开"
                cleanup()
            }
        })
    }
    
    /**
     * 停止实时对话
     */
    fun stopRealtimeConversation() {
        Log.d(TAG, "停止豆包端到端实时对话")
        
        // 1. 停止录音
        stopAudioRecording()
        
        // 2. 发送 FinishSession
        if (isSessionActive) {
            sendFinishSession()
        }
        
        // 3. 发送 FinishConnection（可选，用于释放连接）
        // sendFinishConnection()
        
        // 4. 关闭 WebSocket
        webSocket?.close(1000, "用户停止")
        
        cleanup()
    }
    
    /**
     * 发送 StartConnection 事件
     * 注：Connect ID在HTTP Header中，不在二进制帧中
     */
    private fun sendStartConnection() {
        val payload = "{}"
        val frame = buildBinaryFrame(
            messageType = MSG_TYPE_FULL_CLIENT_REQUEST,
            eventId = EVENT_START_CONNECTION,
            connectId = null,  // 不在二进制帧中携带
            sessionId = null,
            payload = payload.toByteArray()
        )
        webSocket?.send(ByteString.of(*frame))
        Log.d(TAG, "→ 已发送 StartConnection")
    }
    
    /**
     * 发送 StartSession 事件
     */
    private fun sendStartSession(botName: String, systemRole: String, speakingStyle: String) {
        val sessionConfig = JSONObject().apply {
            put("asr", JSONObject().apply {
                put("extra", JSONObject().apply {
                    put("end_smooth_window_ms", 1500)
                    put("enable_custom_vad", false)
                    put("enable_asr_twopass", true)
                })
            })
            put("dialog", JSONObject().apply {
                put("bot_name", botName)
                if (systemRole.isNotEmpty()) {
                    put("system_role", systemRole)
                }
                if (speakingStyle.isNotEmpty()) {
                    put("speaking_style", speakingStyle)
                }
                put("extra", JSONObject().apply {
                    put("model", "O")  // O版本支持联网和RAG
                    put("strict_audit", true)
                })
            })
            put("tts", JSONObject().apply {
                put("speaker", "zh_female_vv_jupiter_bigtts")  // 默认vv音色
                put("audio_config", JSONObject().apply {
                    put("channel", 1)
                    put("format", "pcm_s16le")  // PCM 16bit小端序,可直接播放
                    put("sample_rate", 24000)
                })
            })
        }
        
        val payload = sessionConfig.toString().toByteArray()
        val frame = buildBinaryFrame(
            messageType = MSG_TYPE_FULL_CLIENT_REQUEST,
            eventId = EVENT_START_SESSION,
            connectId = null,
            sessionId = sessionId,
            payload = payload
        )
        webSocket?.send(ByteString.of(*frame))
        isSessionActive = true
        Log.d(TAG, "→ 已发送 StartSession")
    }
    
    /**
     * 发送 FinishSession 事件
     */
    private fun sendFinishSession() {
        val payload = "{}".toByteArray()
        val frame = buildBinaryFrame(
            messageType = MSG_TYPE_FULL_CLIENT_REQUEST,
            eventId = EVENT_FINISH_SESSION,
            connectId = null,
            sessionId = sessionId,
            payload = payload
        )
        webSocket?.send(ByteString.of(*frame))
        isSessionActive = false
        Log.d(TAG, "→ 已发送 FinishSession")
    }
    
    /**
     * 启动音频录制
     */
    private fun startAudioRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_16K,
            CHANNEL_IN,
            ENCODING_16BIT
        ) * 2
        
        try {
            // 使用VOICE_COMMUNICATION启用硬件回声消除(AEC)
            @Suppress("MissingPermission")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_16K,
                CHANNEL_IN,
                ENCODING_16BIT,
                bufferSize
            )
            
            audioRecord?.startRecording()
            _isRecording.value = true
            
            recordingJob = serviceScope.launch {
                val buffer = ByteArray(640)  // 20ms 音频 = 640字节
                
                while (isActive && _isRecording.value) {
                    // 如果正在播放AI语音,跳过录音以避免回声
                    if (_isPlaying.value) {
                        delay(50)
                        continue
                    }
                    
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (bytesRead > 0) {
                        // 发送音频数据
                        val frame = buildBinaryFrame(
                            messageType = MSG_TYPE_AUDIO_ONLY_REQUEST,
                            eventId = EVENT_TASK_REQUEST,
                            connectId = null,
                            sessionId = sessionId,
                            payload = buffer.copyOf(bytesRead)
                        )
                        webSocket?.send(ByteString.of(*frame))
                    }
                    
                    delay(20)  // 模拟20ms发送间隔
                }
            }
            
            Log.d(TAG, "✅ 录音已启动")
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}")
        }
    }
    
    /**
     * 停止音频录制
     */
    private fun stopAudioRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        Log.d(TAG, "录音已停止")
    }
    
    /**
     * 启动音频播放
     */
    private fun startAudioPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_24K,
            CHANNEL_OUT,
            ENCODING_16BIT
        ) * 2
        
        try {
            // 使用VOICE_COMMUNICATION优先路由到耳机,减少扬声器回声
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_24K)
                    .setEncoding(ENCODING_16BIT)
                    .setChannelMask(CHANNEL_OUT)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            audioTrack?.play()
            _isPlaying.value = true
            
            playbackJob = serviceScope.launch {
                var idleCount = 0
                var shouldStop = false
                while (isActive && !shouldStop) {
                    val audioData = audioQueue.poll()
                    if (audioData != null) {
                        // 🔒 同步访问 audioTrack，防止 SIGSEGV
                        val writeSuccess = synchronized(audioTrackLock) {
                            val track = audioTrack
                            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                                try {
                                    track.write(audioData, 0, audioData.size)
                                    true
                                } catch (e: Exception) {
                                    Log.e(TAG, "音频写入失败: ${e.message}")
                                    false
                                }
                            } else {
                                Log.w(TAG, "AudioTrack 不可用，停止播放")
                                false
                            }
                        }
                        
                        if (writeSuccess) {
                            idleCount = 0
                            _isPlaying.value = true
                        } else {
                            shouldStop = true
                        }
                    } else {
                        delay(10)
                        idleCount++
                        // 如果队列空闲超过500ms,认为播放完成
                        if (idleCount > 50) {
                            _isPlaying.value = false
                            idleCount = 0
                        }
                    }
                }
            }
            
            Log.d(TAG, "✅ 音频播放已启动")
        } catch (e: Exception) {
            Log.e(TAG, "音频播放启动失败: ${e.message}")
        }
    }
    
    /**
     * 停止音频播放
     */
    private fun stopAudioPlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
        
        // 🔒 同步释放 audioTrack，防止并发访问崩溃
        synchronized(audioTrackLock) {
            audioTrack?.apply {
                if (state != AudioTrack.STATE_UNINITIALIZED) {
                    stop()
                    flush()  // 清空缓冲区
                }
                release()
            }
            audioTrack = null
        }
        
        audioQueue.clear()
        
        // ⏱️ 等待系统回收 AudioTrack 资源（避免第二次创建时延迟）
        Thread.sleep(50)
        
        Log.d(TAG, "音频播放已停止")
    }
    
    /**
     * 处理二进制消息
     */
    private fun handleBinaryMessage(data: ByteArray) {
        if (data.size < 4) {
            Log.w(TAG, "消息太短，忽略")
            return
        }
        
        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            
            // 读取 Header (4字节)
            val header0 = buffer.get()
            val header1 = buffer.get()
            val header2 = buffer.get()
            val header3 = buffer.get()
            
            val messageType = (header1.toInt() shr 4) and 0x0F
            val flags = header1.toInt() and 0x0F
            
            // 解析可选字段
            val hasEvent = (flags and 0x04) != 0
            var eventId = 0
            
            if (hasEvent && buffer.remaining() >= 4) {
                eventId = buffer.getInt()
            }
            
            // 读取 Session ID（如果有）
            var currentSessionId: String? = null
            if (buffer.remaining() >= 4) {
                val sessionIdSize = buffer.getInt()
                if (sessionIdSize > 0 && buffer.remaining() >= sessionIdSize) {
                    val sessionIdBytes = ByteArray(sessionIdSize)
                    buffer.get(sessionIdBytes)
                    currentSessionId = String(sessionIdBytes)
                }
            }
            
            // 读取 Payload
            if (buffer.remaining() >= 4) {
                val payloadSize = buffer.getInt()
                if (payloadSize > 0 && buffer.remaining() >= payloadSize) {
                    val payload = ByteArray(payloadSize)
                    buffer.get(payload)
                    
                    handleServerEvent(messageType, eventId, payload)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析二进制消息失败: ${e.message}")
        }
    }
    
    /**
     * 处理服务端事件
     */
    private fun handleServerEvent(messageType: Int, eventId: Int, payload: ByteArray) {
        when (eventId) {
            EVENT_CONNECTION_STARTED -> {
                Log.d(TAG, "✓ 连接已建立")
            }
            EVENT_SESSION_STARTED -> {
                val json = JSONObject(String(payload))
                val dialogId = json.optString("dialog_id", "")
                Log.d(TAG, "✓ 会话已启动，Dialog ID: $dialogId")
            }
            EVENT_ASR_INFO -> {
                Log.d(TAG, "🎙️ 检测到用户开始说话")
                // 可以在这里打断AI的播放
                stopAudioPlayback()
                audioQueue.clear()
                startAudioPlayback()
            }
            EVENT_ASR_RESPONSE -> {
                val json = JSONObject(String(payload))
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val result = results.getJSONObject(0)
                    val text = result.optString("text", "")
                    val isInterim = result.optBoolean("is_interim", false)
                    
                    if (!isInterim) {
                        _transcription.value = text
                        Log.d(TAG, "📝 识别结果: $text")
                    }
                }
            }
            EVENT_ASR_ENDED -> {
                Log.d(TAG, "🎤️ 用户说话结束")
                // 触发用户说话完成事件
                val finalTranscription = _transcription.value
                if (finalTranscription.isNotEmpty()) {
                    _userSpeechCompleted.value = finalTranscription
                }
            }
            EVENT_CHAT_RESPONSE -> {
                val json = JSONObject(String(payload))
                val content = json.optString("content", "")
                _responseText.value += content
                Log.d(TAG, "💬 文本回复: $content")
            }
            EVENT_CHAT_ENDED -> {
                Log.d(TAG, "💬 AI回复结束")
                // 触发AI回复完成事件
                val finalResponse = _responseText.value
                if (finalResponse.isNotEmpty()) {
                    _aiResponseCompleted.value = finalResponse
                    // 清空为下一轮对话准备
                    _responseText.value = ""
                    _transcription.value = ""
                }
            }
            EVENT_TTS_RESPONSE -> {
                // 音频数据 - PCM_S16LE格式,可直接播放
                if (messageType == 0x0B) {  // Audio-only response
                    audioQueue.offer(payload)
                    Log.d(TAG, "🔊 收到PCM音频数据: ${payload.size} 字节")
                }
            }
            else -> {
                Log.d(TAG, "收到事件 $eventId")
            }
        }
    }
    
    /**
     * 构建二进制帧
     */
    private fun buildBinaryFrame(
        messageType: Byte,
        eventId: Int,
        connectId: String?,
        sessionId: String?,
        payload: ByteArray
    ): ByteArray {
        val buffer = ByteBuffer.allocate(1024 * 10).order(ByteOrder.BIG_ENDIAN)
        
        // Header (4字节)
        buffer.put(PROTOCOL_VERSION)  // 协议版本 + Header Size
        
        // Message Type + Flags
        var flags: Byte = 0x04  // 携带事件ID
        val header1 = ((messageType.toInt() shl 4) or flags.toInt()).toByte()
        buffer.put(header1)
        
        // Byte2: [4bit序列化方法][4bit压缩方法]
        // 0x10 = 0001 0000 = JSON序列化 + 无压缩
        val serialization: Byte = if (messageType == MSG_TYPE_AUDIO_ONLY_REQUEST) {
            0x00  // 音频数据：Raw序列化 + 无压缩
        } else {
            0x10  // 文本数据：JSON序列化 + 无压缩
        }
        buffer.put(serialization)
        buffer.put(0x00)  // Reserved
        
        // Event ID
        buffer.putInt(eventId)
        
        // Connect ID（如果有）
        if (connectId != null) {
            val connectIdBytes = connectId.toByteArray()
            buffer.putInt(connectIdBytes.size)
            buffer.put(connectIdBytes)
        }
        
        // Session ID（如果有）
        if (sessionId != null) {
            val sessionIdBytes = sessionId.toByteArray()
            buffer.putInt(sessionIdBytes.size)
            buffer.put(sessionIdBytes)
        }
        
        // Payload
        buffer.putInt(payload.size)
        buffer.put(payload)
        
        val frameSize = buffer.position()
        val frame = ByteArray(frameSize)
        buffer.rewind()
        buffer.get(frame)
        
        // 调试日志：输出帧的前16字节（Header + Event ID等）
        if (frameSize >= 8 && eventId != EVENT_TASK_REQUEST) {
            val preview = frame.take(minOf(16, frameSize))
                .joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "📤 发送帧 [事件$eventId]: $preview...")
        }
        
        return frame
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        Log.d(TAG, "cleanup: 清理所有资源")
        
        // 停止录音和播放
        stopAudioRecording()
        stopAudioPlayback()
        
        // 🔥 取消所有协程（统一管理）
        serviceScope.cancel()
        
        // 关闭WebSocket
        webSocket?.close(1000, "cleanup")
        webSocket = null
        
        // 重置状态
        isSessionActive = false
        _isRecording.value = false
        _isPlaying.value = false
        
        // 清空所有StateFlow
        _transcription.value = ""
        _responseText.value = ""
        _connectionState.value = ""
        _userSpeechCompleted.value = null
        _aiResponseCompleted.value = null
    }
}
