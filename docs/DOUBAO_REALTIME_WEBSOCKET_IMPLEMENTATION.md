# 豆包实时语音对话 WebSocket 实现详解

## 📋 目录

1. [技术概述](#技术概述)
2. [完整实现流程](#完整实现流程)
3. [核心组件详解](#核心组件详解)
4. [二进制协议解析](#二进制协议解析)
5. [音频流处理](#音频流处理)
6. [状态管理与生命周期](#状态管理与生命周期)
7. [错误处理与重连](#错误处理与重连)
8. [实际问题与解决方案](#实际问题与解决方案)

---

## 技术概述

### 什么是豆包实时对话？

豆包实时对话是字节跳动推出的端到端实时语音对话API，基于 **WebSocket** 实现音频流的双向传输，无需等待完整音频录制完成即可实时识别和响应。

### 核心技术栈

```
WebSocket Layer (应用层)
    ↓
二进制协议 (自定义格式)
    ↓
音频流 (PCM 16kHz 16bit Mono)
    ↓
Android AudioRecord/AudioTrack (硬件层)
```

### 与传统方案对比

| 特性 | 传统方案（REST API） | 豆包实时对话（WebSocket） |
|------|---------------------|--------------------------|
| 延迟 | 5-10秒 | <1秒 |
| 实现方式 | 录音→上传→等待→下载 | 边录边传边播 |
| 用户体验 | 等待感明显 | 实时对话 |
| 技术复杂度 | 低 | 高 |
| 资源占用 | 低 | 高（需要保持连接） |

---

## 完整实现流程

### 总览架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     Android 应用层                            │
├─────────────────────────────────────────────────────────────┤
│  UI层 (ChatScreen.kt)                                        │
│    ↓ 用户点击麦克风按钮                                        │
│  ViewModel (ChatViewModel.kt)                                │
│    ↓ 调用 startRealtimeVoiceConversation()                   │
│  Service层 (DoubaoRealtimeService.kt)                        │
│    ├─ WebSocket连接管理                                      │
│    ├─ 音频录制 (AudioRecord)                                  │
│    ├─ 音频播放 (AudioTrack)                                   │
│    └─ 二进制协议编解码                                         │
├─────────────────────────────────────────────────────────────┤
│                      网络传输层                               │
│  OkHttp WebSocket ←→ 豆包服务器                              │
├─────────────────────────────────────────────────────────────┤
│                      豆包云端                                 │
│  ├─ 语音识别 (ASR)                                           │
│  ├─ 自然语言处理 (NLU)                                        │
│  ├─ 对话生成 (LLM)                                           │
│  └─ 语音合成 (TTS)                                           │
└─────────────────────────────────────────────────────────────┘
```

### 详细时序图

```sequence
用户->ChatScreen: 点击麦克风
ChatScreen->ChatViewModel: startVoiceRecording()
ChatViewModel->DoubaoRealtimeService: connect()

Note over DoubaoRealtimeService: 阶段1: 建立连接
DoubaoRealtimeService->豆包服务器: WebSocket连接请求
豆包服务器-->DoubaoRealtimeService: 连接成功

Note over DoubaoRealtimeService: 阶段2: 发送配置
DoubaoRealtimeService->豆包服务器: start_dialogue消息
豆包服务器-->DoubaoRealtimeService: 配置确认

Note over DoubaoRealtimeService: 阶段3: 音频采集
DoubaoRealtimeService->AudioRecord: 开始录音
loop 实时传输
    AudioRecord->DoubaoRealtimeService: PCM音频数据
    DoubaoRealtimeService->豆包服务器: 二进制音频帧
end

Note over 豆包服务器: 阶段4: 实时处理
豆包服务器->豆包服务器: ASR识别
豆包服务器->豆包服务器: NLU理解
豆包服务器->豆包服务器: LLM生成回复
豆包服务器->豆包服务器: TTS合成

Note over DoubaoRealtimeService: 阶段5: 接收播放
loop 实时播放
    豆包服务器-->DoubaoRealtimeService: 二进制音频帧
    DoubaoRealtimeService->AudioTrack: PCM音频数据
    AudioTrack->用户: 播放声音
end

用户->ChatScreen: 再次点击停止
ChatScreen->DoubaoRealtimeService: disconnect()
DoubaoRealtimeService->豆包服务器: 关闭连接
```

---

## 核心组件详解

### 1. DoubaoRealtimeService.kt - 核心服务

#### 1.1 类结构

```kotlin
class DoubaoRealtimeService(
    private val context: Context,
    private val apiKey: String,
    private val appId: String
) {
    // ===== 核心组件 =====
    private var webSocket: WebSocket? = null      // WebSocket连接
    private var audioRecord: AudioRecord? = null  // 音频录制
    private var audioTrack: AudioTrack? = null    // 音频播放
    
    // ===== 协程管理 =====
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    
    // ===== 线程安全 =====
    @Volatile private var isRecording = false
    @Volatile private var isPlaying = false
    private val audioTrackLock = Any()
    
    // ===== 状态流 =====
    private val _connectionState = MutableStateFlow<ConnectionState>(Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
}
```

#### 1.2 连接建立流程

```kotlin
/**
 * 连接到豆包实时服务
 * 
 * 步骤：
 * 1. 构建WebSocket URL（包含认证参数）
 * 2. 创建OkHttpClient
 * 3. 建立WebSocket连接
 * 4. 发送start_dialogue配置消息
 */
fun connect() {
    if (webSocket != null) {
        Log.w(TAG, "WebSocket已连接")
        return
    }
    
    serviceScope.launch {
        try {
            // 步骤1: 构建URL
            val url = buildWebSocketUrl()
            Log.d(TAG, "连接URL: $url")
            
            // 步骤2: 创建HTTP客户端
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // ⚠️ 长连接不设超时
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)  // ✅ 心跳保活
                .build()
            
            // 步骤3: 创建WebSocket请求
            val request = Request.Builder()
                .url(url)
                .build()
            
            // 步骤4: 建立连接
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ WebSocket连接成功")
                    _connectionState.value = ConnectionState.Connected
                    
                    // 步骤5: 发送配置消息
                    sendStartDialogueMessage()
                }
                
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // 接收二进制消息（音频数据或文本消息）
                    handleBinaryMessage(bytes.toByteArray())
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket连接失败", t)
                    _connectionState.value = ConnectionState.Error(t.message ?: "连接失败")
                    _error.value = t.message
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket已关闭: code=$code, reason=$reason")
                    _connectionState.value = ConnectionState.Disconnected
                    cleanup()
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "连接异常", e)
            _error.value = e.message
        }
    }
}

/**
 * 构建WebSocket URL
 * 
 * 格式: wss://openspeech.bytedance.com/api/v3/realtime/dialogue?appid=xxx&token=xxx&cluster=xxx
 */
private fun buildWebSocketUrl(): String {
    val baseUrl = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
    
    // 认证参数
    val params = mapOf(
        "appid" to appId,
        "token" to apiKey,
        "cluster" to "volcengine_input_common"  // 服务集群
    )
    
    val queryString = params.entries.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, "UTF-8")}"
    }
    
    return "$baseUrl?$queryString"
}
```

#### 1.3 发送配置消息

```kotlin
/**
 * 发送start_dialogue消息
 * 
 * 必须在连接成功后立即发送，否则连接会被服务器关闭
 */
private fun sendStartDialogueMessage() {
    val config = JSONObject().apply {
        put("type", "start_dialogue")
        put("audio_config", JSONObject().apply {
            put("sample_rate", 16000)      // 采样率：16kHz
            put("channels", 1)             // 单声道
            put("bits_per_sample", 16)     // 采样位深：16bit
            put("encoding", "pcm")         // 编码格式：PCM
        })
        put("dialogue_config", JSONObject().apply {
            put("enable_asr", true)        // 启用语音识别
            put("enable_tts", true)        // 启用语音合成
            put("language", "zh-CN")       // 语言：中文
        })
    }
    
    val jsonString = config.toString()
    Log.d(TAG, "→ 发送配置: $jsonString")
    
    // 🔥 关键：使用二进制协议发送
    val encodedMessage = encodeMessage(jsonString)
    webSocket?.send(ByteString.of(*encodedMessage))
}
```

---

### 2. 二进制协议实现

#### 2.1 协议格式

豆包使用自定义二进制协议，格式如下：

```
┌────────────────┬─────────────────────────────┐
│  4字节头部      │      负载数据                │
│  (消息长度)     │      (JSON或音频)            │
└────────────────┴─────────────────────────────┘

头部格式（Big Endian - 大端序）：
  字节0   字节1   字节2   字节3
┌───────┬───────┬───────┬───────┐
│ 长度高位 │       │       │ 长度低位│
└───────┴───────┴───────┴───────┘

示例：
消息长度 = 123 (0x0000007B)
头部 = [0x00, 0x00, 0x00, 0x7B]
```

#### 2.2 编码实现

```kotlin
/**
 * 编码消息为二进制格式
 * 
 * @param json JSON字符串
 * @return 二进制数组（4字节头部 + UTF-8负载）
 */
private fun encodeMessage(json: String): ByteArray {
    // 步骤1: 将JSON转换为UTF-8字节数组
    val payload = json.toByteArray(Charsets.UTF_8)
    val length = payload.size
    
    Log.d(TAG, "编码消息: 长度=$length, 内容=${json.take(100)}")
    
    // 步骤2: 创建4字节头部（大端序）
    val header = ByteBuffer.allocate(4)
        .order(ByteOrder.BIG_ENDIAN)  // 🔥 关键：豆包使用大端序
        .putInt(length)
        .array()
    
    // 调试日志：显示头部字节
    Log.d(TAG, "头部字节: ${header.joinToString(" ") { "%02X".format(it) }}")
    
    // 步骤3: 拼接头部和负载
    return header + payload
}

/**
 * 为什么要用大端序？
 * 
 * 网络协议通常使用大端序（Big Endian），也称为"网络字节序"
 * - 大端序：高位字节存储在低地址（人类阅读习惯）
 * - 小端序：低位字节存储在低地址（x86/ARM默认）
 * 
 * 示例：数字 0x12345678
 * - 大端序内存：[0x12, 0x34, 0x56, 0x78]
 * - 小端序内存：[0x78, 0x56, 0x34, 0x12]
 */
```

#### 2.3 解码实现

```kotlin
/**
 * 解码二进制消息
 * 
 * 处理粘包和半包问题：
 * - 粘包：一次接收到多条消息
 * - 半包：一条消息分多次接收
 */
private class MessageDecoder {
    // 缓冲区，存储未完成的消息
    private val buffer = ByteArrayOutputStream()
    
    /**
     * 解码新接收的数据
     * 
     * @param newBytes 新接收的字节
     * @return 已完整接收的消息列表
     */
    fun decode(newBytes: ByteArray): List<String> {
        // 步骤1: 追加到缓冲区
        buffer.write(newBytes)
        
        val messages = mutableListOf<String>()
        
        // 步骤2: 循环提取完整消息
        while (buffer.size() >= 4) {  // 至少有头部
            val data = buffer.toByteArray()
            
            // 步骤3: 读取消息长度（大端序）
            val length = ByteBuffer.wrap(data, 0, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            
            Log.d(TAG, "解码: 当前缓冲区=${buffer.size()}字节, 需要=$length字节")
            
            // 步骤4: 检查是否接收完整
            if (data.size < 4 + length) {
                Log.d(TAG, "半包，等待更多数据...")
                break  // 半包，等待更多数据
            }
            
            // 步骤5: 提取消息内容
            val message = String(data, 4, length, Charsets.UTF_8)
            messages.add(message)
            
            Log.d(TAG, "✓ 解码成功: ${message.take(100)}")
            
            // 步骤6: 从缓冲区移除已处理的数据
            buffer.reset()
            if (data.size > 4 + length) {
                // 还有剩余数据（粘包情况）
                buffer.write(data, 4 + length, data.size - 4 - length)
            }
        }
        
        return messages
    }
    
    fun clear() {
        buffer.reset()
    }
}

/**
 * 处理接收到的二进制消息
 */
private val decoder = MessageDecoder()

private fun handleBinaryMessage(bytes: ByteArray) {
    serviceScope.launch {
        try {
            val messages = decoder.decode(bytes)
            
            for (message in messages) {
                val json = JSONObject(message)
                val type = json.optString("type")
                
                when (type) {
                    "transcription" -> {
                        // 识别结果
                        val text = json.optString("text")
                        _transcription.value = text
                        Log.d(TAG, "← 识别结果: $text")
                    }
                    
                    "audio" -> {
                        // 音频数据
                        val audioBase64 = json.optString("data")
                        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                        playAudio(audioBytes)
                    }
                    
                    "error" -> {
                        // 错误消息
                        val error = json.optString("message")
                        _error.value = error
                        Log.e(TAG, "← 服务器错误: $error")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "消息解析失败", e)
        }
    }
}
```

---

### 3. 音频录制与发送

#### 3.1 AudioRecord 初始化

```kotlin
/**
 * 初始化AudioRecord
 * 
 * 参数必须与服务器配置一致：
 * - 采样率：16000 Hz
 * - 通道：MONO（单声道）
 * - 编码：PCM_16BIT
 */
private fun initAudioRecord() {
    try {
        // 步骤1: 计算缓冲区大小
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        val bufferSize = minBufferSize * 2  // ✅ 使用2倍最小缓冲区
        
        Log.d(TAG, "AudioRecord缓冲区: $bufferSize 字节")
        
        // 步骤2: 创建AudioRecord
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 🔥 消除回声
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        
        // 步骤3: 检查状态
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord初始化失败")
        }
        
        Log.d(TAG, "✓ AudioRecord初始化成功")
        
    } catch (e: Exception) {
        Log.e(TAG, "AudioRecord初始化失败", e)
        _error.value = "麦克风初始化失败: ${e.message}"
    }
}

/**
 * 为什么使用 VOICE_COMMUNICATION？
 * 
 * AudioSource选项对比：
 * - DEFAULT: 默认麦克风
 * - MIC: 标准麦克风（可能有回声）
 * - VOICE_COMMUNICATION: 语音通话优化
 *   ✅ 自动回声消除（AEC）
 *   ✅ 自动增益控制（AGC）
 *   ✅ 噪声抑制（NS）
 * - VOICE_RECOGNITION: 语音识别优化（单向）
 */
```

#### 3.2 录音循环

```kotlin
/**
 * 开始录音并实时发送
 */
fun startRecording() {
    if (isRecording) {
        Log.w(TAG, "已在录音中")
        return
    }
    
    // 初始化AudioRecord
    initAudioRecord()
    
    // 启动录音
    audioRecord?.startRecording()
    isRecording = true
    
    Log.d(TAG, "🎤 开始录音")
    
    // 启动录音协程
    recordingJob = serviceScope.launch {
        try {
            val buffer = ByteArray(BUFFER_SIZE)  // 通常1024或2048字节
            
            while (isActive && isRecording) {
                // 步骤1: 从麦克风读取数据
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (bytesRead > 0) {
                    Log.d(TAG, "→ 录音数据: $bytesRead 字节")
                    
                    // 步骤2: 发送到服务器
                    sendAudioData(buffer.copyOf(bytesRead))
                } else {
                    Log.w(TAG, "读取音频失败: $bytesRead")
                }
                
                // 步骤3: 短暂延迟，避免CPU占用过高
                delay(10)
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音异常", e)
            _error.value = "录音失败: ${e.message}"
        } finally {
            Log.d(TAG, "录音协程结束")
        }
    }
}

/**
 * 发送音频数据到服务器
 * 
 * @param audioData PCM音频数据
 */
private fun sendAudioData(audioData: ByteArray) {
    try {
        // 🔥 直接发送原始PCM数据（无需编码为JSON）
        webSocket?.send(ByteString.of(*audioData))
        
        Log.d(TAG, "→ 发送音频: ${audioData.size} 字节")
        
    } catch (e: Exception) {
        Log.e(TAG, "发送音频失败", e)
    }
}

/**
 * 停止录音
 */
fun stopRecording() {
    if (!isRecording) return
    
    isRecording = false
    
    // 取消录音协程
    recordingJob?.cancel()
    recordingJob = null
    
    // 停止并释放AudioRecord
    try {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "✓ 录音已停止")
    } catch (e: Exception) {
        Log.e(TAG, "停止录音失败", e)
    }
}
```

---

### 4. 音频接收与播放

#### 4.1 AudioTrack 初始化

```kotlin
/**
 * 初始化AudioTrack
 * 
 * 参数必须与服务器返回的音频格式一致
 */
private fun initAudioTrack() {
    try {
        // 步骤1: 计算缓冲区大小
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        val bufferSize = minBufferSize * 2
        
        Log.d(TAG, "AudioTrack缓冲区: $bufferSize 字节")
        
        // 步骤2: 创建AudioTrack
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,  // 音乐流
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM  // 🔥 流式播放模式
        )
        
        // 步骤3: 检查状态
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack初始化失败")
        }
        
        Log.d(TAG, "✓ AudioTrack初始化成功")
        
    } catch (e: Exception) {
        Log.e(TAG, "AudioTrack初始化失败", e)
        _error.value = "播放器初始化失败: ${e.message}"
    }
}

/**
 * MODE_STREAM vs MODE_STATIC
 * 
 * MODE_STREAM（流式播放）：
 * ✅ 适合实时音频流
 * ✅ 边接收边播放
 * ✅ 内存占用低
 * 
 * MODE_STATIC（静态播放）：
 * - 适合短音频
 * - 需要一次性加载完整音频
 * - 延迟低但内存占用高
 */
```

#### 4.2 播放循环

```kotlin
/**
 * 播放音频数据（线程安全版本）
 * 
 * @param audioData PCM音频数据
 */
private fun playAudio(audioData: ByteArray) {
    if (!isPlaying) {
        // 首次播放，启动AudioTrack
        startPlayback()
    }
    
    // 🔥 关键：使用synchronized保证线程安全
    synchronized(audioTrackLock) {
        val track = audioTrack
        
        // 检查状态
        if (track?.state == AudioTrack.STATE_INITIALIZED) {
            try {
                // 写入音频数据
                val bytesWritten = track.write(audioData, 0, audioData.size)
                
                if (bytesWritten < 0) {
                    Log.e(TAG, "AudioTrack.write() 失败: $bytesWritten")
                } else {
                    Log.d(TAG, "← 播放音频: $bytesWritten 字节")
                }
            } catch (e: Exception) {
                Log.e(TAG, "播放音频异常", e)
            }
        } else {
            Log.w(TAG, "AudioTrack未初始化，跳过播放")
        }
    }
}

/**
 * 启动播放
 */
private fun startPlayback() {
    if (isPlaying) return
    
    initAudioTrack()
    audioTrack?.play()
    isPlaying = true
    
    Log.d(TAG, "🔊 开始播放")
}

/**
 * 停止播放
 */
fun stopPlayback() {
    if (!isPlaying) return
    
    isPlaying = false
    
    // 🔥 线程安全的清理
    synchronized(audioTrackLock) {
        val track = audioTrack
        audioTrack = null  // 先置空，防止其他线程访问
        
        try {
            if (track?.state == AudioTrack.STATE_INITIALIZED) {
                track.stop()
            }
            track?.release()
            Log.d(TAG, "✓ 播放已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止播放失败", e)
        }
    }
}
```

#### 4.3 为什么需要线程安全？

```kotlin
/**
 * 多线程访问场景分析
 * 
 * 场景1: 播放协程写入数据
 * Thread-1: playbackJob.launch {
 *     audioTrack.write(buffer)  // ← 正在写入
 * }
 * 
 * 场景2: 用户点击停止
 * Thread-2 (UI): stopPlayback() {
 *     audioTrack.release()      // ← 同时释放
 * }
 * 
 * 结果: SIGSEGV崩溃（访问已释放的Native对象）
 * 
 * 解决方案:
 * 1. @Volatile: 确保audioTrack引用的可见性
 * 2. synchronized: 确保write和release不会同时执行
 * 3. 状态检查: 写入前检查STATE_INITIALIZED
 */

@Volatile
private var audioTrack: AudioTrack? = null
private val audioTrackLock = Any()

fun write(buffer: ByteArray) {
    synchronized(audioTrackLock) {  // ← 加锁
        val track = audioTrack      // ← 本地变量，避免多次读取
        if (track?.state == AudioTrack.STATE_INITIALIZED) {
            track.write(buffer, 0, buffer.size)
        }
    }
}

fun cleanup() {
    synchronized(audioTrackLock) {  // ← 加锁
        val track = audioTrack
        audioTrack = null           // ← 先置空
        track?.release()            // ← 再释放
    }
}
```

---

### 5. 生命周期管理

#### 5.1 协程作用域设计

```kotlin
/**
 * 协程生命周期管理
 * 
 * 为什么使用 serviceScope？
 * - 统一管理所有协程的生命周期
 * - Service销毁时自动取消所有协程
 * - 防止内存泄漏
 */
class DoubaoRealtimeService {
    // 🔥 核心：使用SupervisorJob
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )
    
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    
    /**
     * SupervisorJob vs Job
     * 
     * Job（普通Job）：
     * - 子协程失败 → 父协程失败 → 所有兄弟协程取消
     * 
     * SupervisorJob：
     * ✅ 子协程失败 → 其他协程继续运行
     * ✅ 适合独立任务（录音失败不影响播放）
     */
    
    fun startRecording() {
        recordingJob = serviceScope.launch {
            try {
                // 录音逻辑
            } catch (e: Exception) {
                // ✅ 录音失败，但播放协程继续运行
                Log.e(TAG, "录音失败", e)
            }
        }
    }
    
    fun cleanup() {
        // 🔥 取消所有协程
        recordingJob?.cancel()
        playbackJob?.cancel()
        serviceScope.cancel()  // ← 取消整个作用域
        
        Log.d(TAG, "所有协程已取消")
    }
}
```

#### 5.2 资源清理时机

```kotlin
/**
 * 完整的清理流程
 */
fun cleanup() {
    Log.d(TAG, "开始清理资源...")
    
    // 步骤1: 停止录音
    stopRecording()
    
    // 步骤2: 停止播放
    stopPlayback()
    
    // 步骤3: 关闭WebSocket
    try {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        Log.d(TAG, "✓ WebSocket已关闭")
    } catch (e: Exception) {
        Log.e(TAG, "关闭WebSocket失败", e)
    }
    
    // 步骤4: 取消所有协程
    recordingJob?.cancel()
    playbackJob?.cancel()
    serviceScope.cancel()
    
    // 步骤5: 清理解码器缓冲区
    decoder.clear()
    
    // 步骤6: 重置状态
    _connectionState.value = ConnectionState.Disconnected
    _transcription.value = ""
    isRecording = false
    isPlaying = false
    
    Log.d(TAG, "✓ 资源清理完成")
}

/**
 * 调用时机
 * 
 * 1. 用户主动停止对话
 * 2. Service销毁（onDestroy）
 * 3. WebSocket连接断开
 * 4. 发生错误需要重置
 */
```

---

### 6. 错误处理与重连

#### 6.1 错误类型

```kotlin
/**
 * 错误分类
 */
sealed class RealtimeError(val message: String) {
    // 1. 连接错误
    class ConnectionError(message: String) : RealtimeError(message)
    class NetworkError(message: String) : RealtimeError(message)
    
    // 2. 音频错误
    class AudioRecordError(message: String) : RealtimeError(message)
    class AudioPlaybackError(message: String) : RealtimeError(message)
    
    // 3. 协议错误
    class ProtocolError(message: String) : RealtimeError(message)
    class DecodeError(message: String) : RealtimeError(message)
    
    // 4. 服务器错误
    class ServerError(message: String) : RealtimeError(message)
}

/**
 * 错误处理策略
 */
private fun handleError(error: RealtimeError) {
    Log.e(TAG, "错误: ${error.message}")
    _error.value = error.message
    
    when (error) {
        is ConnectionError -> {
            // 连接错误 → 重连
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnect()
            } else {
                cleanup()
            }
        }
        
        is AudioRecordError -> {
            // 录音错误 → 重新初始化AudioRecord
            stopRecording()
            delay(1000)
            startRecording()
        }
        
        is AudioPlaybackError -> {
            // 播放错误 → 重新初始化AudioTrack
            stopPlayback()
            delay(1000)
            initAudioTrack()
        }
        
        is ProtocolError, is ServerError -> {
            // 协议或服务器错误 → 断开连接
            cleanup()
        }
    }
}
```

#### 6.2 重连机制

```kotlin
/**
 * 自动重连
 */
private var reconnectAttempts = 0
private val MAX_RECONNECT_ATTEMPTS = 3

private fun reconnect() {
    reconnectAttempts++
    
    Log.d(TAG, "尝试重连... ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
    
    serviceScope.launch {
        // 步骤1: 清理旧连接
        cleanup()
        
        // 步骤2: 延迟后重连（指数退避）
        val delay = (1000L * reconnectAttempts)  // 1s, 2s, 3s
        delay(delay)
        
        // 步骤3: 重新连接
        connect()
    }
}

/**
 * 网络状态监听（可选）
 */
private fun observeNetworkState() {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
        as ConnectivityManager
    
    connectivityManager.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "网络可用")
                if (_connectionState.value is ConnectionState.Error) {
                    reconnect()
                }
            }
            
            override fun onLost(network: Network) {
                Log.w(TAG, "网络断开")
                _connectionState.value = ConnectionState.Error("网络断开")
            }
        }
    )
}
```

---

### 7. 实际问题与解决方案

#### 问题1: 连接成功后立即断开

```kotlin
// ❌ 错误实现
fun connect() {
    webSocket = client.newWebSocket(request, listener)
    // 连接成功后什么都没做 → 服务器超时关闭
}

// ✅ 正确实现
override fun onOpen(webSocket: WebSocket, response: Response) {
    // 必须立即发送start_dialogue消息
    sendStartDialogueMessage()
}
```

**原因：** 豆包服务器要求连接后10秒内发送配置消息，否则关闭连接。

---

#### 问题2: 音频无法播放

```kotlin
// ❌ 错误配置
AudioTrack(
    STREAM_MUSIC,
    8000,  // ← 采样率不匹配
    CHANNEL_OUT_STEREO,  // ← 立体声不匹配
    ENCODING_PCM_8BIT,  // ← 位深不匹配
    bufferSize,
    MODE_STREAM
)

// ✅ 正确配置（必须与服务器一致）
AudioTrack(
    STREAM_MUSIC,
    16000,  // ← 16kHz
    CHANNEL_OUT_MONO,  // ← 单声道
    ENCODING_PCM_16BIT,  // ← 16bit
    bufferSize,
    MODE_STREAM
)
```

**原因：** 音频格式不匹配导致播放失败或杂音。

---

#### 问题3: 消息解析失败

```kotlin
// ❌ 错误实现（未处理粘包）
override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    val json = String(bytes.toByteArray(), Charsets.UTF_8)
    // 直接解析 → 可能包含多条消息或半条消息
    parseJSON(json)
}

// ✅ 正确实现（使用解码器）
override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    val messages = decoder.decode(bytes.toByteArray())
    for (message in messages) {
        parseJSON(message)
    }
}
```

**原因：** WebSocket可能一次接收多条消息（粘包）或半条消息（半包）。

---

#### 问题4: SIGSEGV崩溃

```kotlin
// ❌ 错误实现（无线程保护）
var audioTrack: AudioTrack? = null

fun playAudio(data: ByteArray) {
    audioTrack?.write(data, 0, data.size)  // ← 可能同时被release
}

fun cleanup() {
    audioTrack?.release()  // ← 可能同时被write
}

// ✅ 正确实现（线程安全）
@Volatile
private var audioTrack: AudioTrack? = null
private val audioTrackLock = Any()

fun playAudio(data: ByteArray) {
    synchronized(audioTrackLock) {
        val track = audioTrack
        if (track?.state == AudioTrack.STATE_INITIALIZED) {
            track.write(data, 0, data.size)
        }
    }
}

fun cleanup() {
    synchronized(audioTrackLock) {
        val track = audioTrack
        audioTrack = null
        track?.release()
    }
}
```

**原因：** 多线程并发访问AudioTrack导致访问已释放的Native对象。

---

## 总结

### 核心技术要点

1. **WebSocket长连接** - 实时双向通信
2. **二进制协议** - 自定义格式，需要精确的字节操作
3. **音频流处理** - AudioRecord录制 + AudioTrack播放
4. **线程安全** - @Volatile + synchronized保护Native对象
5. **生命周期管理** - serviceScope统一管理协程
6. **错误处理** - 分类处理 + 自动重连

### 关键挑战

1. ⭐⭐⭐⭐⭐ **Native崩溃** - JNI对象的线程安全
2. ⭐⭐⭐⭐ **二进制协议** - 字节序、粘包、半包
3. ⭐⭐⭐⭐ **音频同步** - 录制和播放的实时性
4. ⭐⭐⭐ **资源释放** - 正确的清理时机
5. ⭐⭐⭐ **网络稳定性** - 断线重连

### 性能指标

- **延迟：** <1秒（相比REST API的5-10秒）
- **带宽：** ~32KB/s（16kHz 16bit Mono）
- **内存：** 动态缓冲，通常<10MB
- **CPU：** 音频编解码占用较低

### 适用场景

✅ **适合：**
- 实时语音对话
- 语音客服系统
- 在线语音助手
- 实时翻译

❌ **不适合：**
- 简单的语音识别（用REST API更简单）
- 离线场景
- 网络不稳定环境

---

<div align="center">

**本文档详细说明了豆包实时语音对话的完整实现流程**

*技术要点：WebSocket + 二进制协议 + 音频流处理 + 线程安全*

</div>
