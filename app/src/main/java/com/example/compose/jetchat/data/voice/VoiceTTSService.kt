package com.example.compose.jetchat.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.compose.jetchat.config.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 语音对话结果
 */
data class VoiceConversationResult(
    val userAudioPath: String?,           // 用户录音文件路径
    val userAudioDuration: Int,           // 用户录音时长（秒）
    val transcription: String,            // 语音转文字结果
    val responseText: String,             // AI 文字回复
    val ttsAudioPath: String?,            // TTS 音频文件路径（如果只返回图片则为null）
    val ttsAudioDuration: Int,            // TTS 音频时长（秒）
    val imageBase64: String?              // 生成的图片（如果有）
)

/**
 * 基于 TTS 的语音对话服务
 * 
 * 流程：录音 → Whisper 转文字 → Chat 生成回复 → TTS 转语音 → 播放
 * 
 * 使用的 API：
 * 1. Whisper API (whisper-1) - 语音识别
 * 2. Chat API (gemini-2.5-pro) - 对话生成
 * 3. TTS API (gpt-4o-mini-tts) - 文字转语音
 */
class VoiceTTSService(
    private val cloudVoiceRecognizer: CloudVoiceRecognizer
) {
    
    companion object {
        private const val TAG = "VoiceTTSService"
        
        // TTS API 配置
        private const val TTS_API_URL = "https://api.vectorengine.ai/v1/audio/speech"
        private const val TTS_MODEL = "gpt-4o-mini-tts"
        private const val TTS_VOICE = "alloy" // 可选: alloy, echo, fable, onyx, nova, shimmer
        private const val TTS_SPEED = 1.0 // 语速: 0.25 ~ 4.0
        
        // 音频播放参数
        private const val SAMPLE_RATE = 24000 // 24kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    // OkHttp 客户端
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    // 音频播放器
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    
    // 状态管理
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription
    
    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    /**
     * 开始语音对话
     */
    fun startVoiceConversation() {
        Log.d(TAG, "开始语音对话（TTS 模式）")
        _error.value = null
        _transcription.value = ""
        _response.value = ""
        
        // 开始录音
        cloudVoiceRecognizer.startRecording()
        _isRecording.value = true
    }
    
    /**
     * 停止录音（不进行识别）
     */
    fun stopRecording() {
        _isRecording.value = false
        Log.d(TAG, "停止录音")
    }
    
    /**
     * 停止录音并处理对话
     * 
     * @param onChatResponse 回调函数，返回 Pair(文本回复, 图片Base64)
     * @return VoiceConversationResult 包含录音文件路径、转录文本、TTS音频路径、图片
     */
    suspend fun stopVoiceConversation(onChatResponse: suspend (String) -> Pair<String, String?>): VoiceConversationResult? {
        try {
            _isRecording.value = false
            _isProcessing.value = true
            
            Log.d(TAG, "停止录音，开始处理...")
            
            // 1. 语音转文字（Whisper）
            cloudVoiceRecognizer.stopRecordingAndRecognize()
            
            // 等待识别完成并获取结果
            val transcription = cloudVoiceRecognizer.transcription.value
            _transcription.value = transcription
            Log.d(TAG, "✓ 语音识别完成: $transcription")
            
            // 获取录音文件路径
            val userAudioPath = cloudVoiceRecognizer.getRecordingFilePath()
            val userAudioDuration = cloudVoiceRecognizer.getRecordingDuration()
            
            if (transcription.isEmpty()) {
                _error.value = "未识别到语音内容"
                _isProcessing.value = false
                return null
            }
            
            // 2. 获取 Chat 回复（由 ViewModel 处理，可能包含图片）
            val (chatResponse, imageBase64) = onChatResponse(transcription)
            _response.value = chatResponse
            Log.d(TAG, "✓ Chat 回复: $chatResponse, 图片: ${if (imageBase64 != null) "有" else "无"}")
            
            if (chatResponse.isEmpty()) {
                _error.value = "未获取到回复"
                _isProcessing.value = false
                return null
            }
            
            // 3. 文字转语音（TTS）- 只有在有文字回复时才生成
            val ttsAudioFile = if (chatResponse.isNotBlank()) {
                textToSpeech(chatResponse)
            } else {
                null
            }
            
            val ttsAudioPath = ttsAudioFile?.absolutePath
            val ttsAudioDuration = if (ttsAudioFile != null) {
                Log.d(TAG, "✓ TTS 合成完成，文件大小: ${ttsAudioFile.length()} bytes")
                getAudioDuration(ttsAudioFile)
            } else {
                0
            }
            
            _isProcessing.value = false
            Log.d(TAG, "✓ 语音对话完成")
            
            return VoiceConversationResult(
                userAudioPath = userAudioPath,
                userAudioDuration = userAudioDuration,
                transcription = transcription,
                responseText = chatResponse,
                ttsAudioPath = ttsAudioPath,
                ttsAudioDuration = ttsAudioDuration,
                imageBase64 = imageBase64
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "语音对话失败: ${e.message}", e)
            _error.value = e.message
            _isProcessing.value = false
            _isPlaying.value = false
            return@stopVoiceConversation null
        }
    }
    
    /**
     * 文字转语音（TTS API）
     */
    suspend fun textToSpeech(text: String): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "→ 调用 TTS API，文本长度: ${text.length}")
            
            // 构建请求 JSON
            val requestJson = JSONObject().apply {
                put("model", TTS_MODEL)
                put("input", text)
                put("voice", TTS_VOICE)
                put("speed", TTS_SPEED)
                put("response_format", "mp3") // 使用 MP3 格式
            }
            
            val requestBody = requestJson.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(TTS_API_URL)
                .post(requestBody)
                .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .build()
            
            Log.d(TAG, "→ 发送 TTS 请求: ${requestJson.toString().take(200)}")
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "✗ TTS API 失败 (${response.code}): $errorBody")
                    return@withContext null
                }
                
                // 保存音频文件
                val audioBytes = response.body?.bytes()
                if (audioBytes == null || audioBytes.isEmpty()) {
                    Log.e(TAG, "✗ TTS 返回空音频")
                    return@withContext null
                }
                
                val tempFile = File.createTempFile("tts_audio_", ".mp3")
                tempFile.writeBytes(audioBytes)
                
                Log.d(TAG, "✓ TTS 音频已保存: ${tempFile.absolutePath}")
                return@withContext tempFile
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS 转换失败: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * 获取音频文件时长（秒）
     */
    private fun getAudioDuration(audioFile: File): Int {
        return try {
            val mediaPlayer = android.media.MediaPlayer()
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.prepare()
            val duration = (mediaPlayer.duration / 1000).coerceAtLeast(1) // 转换为秒，最小1秒
            mediaPlayer.release()
            duration
        } catch (e: Exception) {
            Log.e(TAG, "获取音频时长失败: ${e.message}", e)
            1 // 默认1秒
        }
    }
    
    /**
     * 停止所有操作
     */
    fun stopAll() {
        try {
            _isRecording.value = false
            _isProcessing.value = false
            _isPlaying.value = false
            
            playbackJob?.cancel()
            playbackJob = null
            
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            
            Log.d(TAG, "✓ 已停止所有操作")
        } catch (e: Exception) {
            Log.e(TAG, "停止操作失败: ${e.message}", e)
        }
    }
}
