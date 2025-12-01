package com.example.compose.jetchat.data.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.compose.jetchat.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 云端语音识别服务
 * 
 * 使用 OpenAI Whisper API 进行语音转文字
 * 类似于微信、QQ、Kimi 的语音识别功能
 * 
 * 优点：
 * 1. 不依赖设备本地语音识别引擎
 * 2. 支持所有 Android 设备（包括模拟器）
 * 3. 识别准确率高
 * 4. 支持多种语言
 */
class CloudVoiceRecognizer(private val context: Context) {
    
    companion object {
        private const val TAG = "CloudVoiceRecognizer"
        
        // 音频参数
        private const val SAMPLE_RATE = 16000  // Whisper 推荐 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Whisper API
        private const val WHISPER_API_URL = "https://api.vectorengine.ai/v1/audio/transcriptions"
    }
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingFile: File? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    
    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // 语音识别可能需要更长时间
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    /**
     * 开始录音
     */
    @Suppress("MissingPermission")
    fun startRecording() {
        if (_isListening.value) {
            Log.w(TAG, "录音已在进行中")
            return
        }
        
        try {
            // 创建临时音频文件
            recordingFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
            
            // 计算缓冲区大小
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            
            // 创建 AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            // 开始录音
            audioRecord?.startRecording()
            isRecording = true
            _isListening.value = true
            _error.value = null
            
            Log.d(TAG, "✓ 开始录音: ${recordingFile?.name}")
            
            // 在后台线程录音
            Thread {
                recordAudioToFile()
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "✗ 录音启动失败: ${e.message}", e)
            _error.value = "录音启动失败: ${e.message}"
            _isListening.value = false
        }
    }
    
    /**
     * 停止录音并发送到云端识别
     */
    suspend fun stopRecordingAndRecognize() {
        if (!_isListening.value) {
            Log.w(TAG, "录音未在进行中")
            return
        }
        
        try {
            // 停止录音
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            _isListening.value = false
            _isRecognizing.value = true  // 开始识别状态
            
            Log.d(TAG, "✓ 录音已停止，准备发送到云端识别...")
            
            // 检查文件
            val file = recordingFile
            if (file == null || !file.exists() || file.length() == 0L) {
                _error.value = "录音文件无效"
                Log.e(TAG, "✗ 录音文件无效")
                _isRecognizing.value = false
                return
            }
            
            Log.d(TAG, "✓ 录音文件: ${file.name}, 大小: ${file.length()} bytes")
            
            // 发送到 Whisper API 进行识别
            val transcriptionText = recognizeAudio(file)
            
            _isRecognizing.value = false  // 识别完成
            
            if (transcriptionText.isNotEmpty()) {
                _transcription.value = transcriptionText
                Log.d(TAG, "✓ 识别成功: $transcriptionText")
            } else {
                _error.value = "识别结果为空"
                Log.e(TAG, "✗ 识别结果为空")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "✗ 停止录音失败: ${e.message}", e)
            _error.value = "停止录音失败: ${e.message}"
        } finally {
            // 清理临时文件
            recordingFile?.delete()
            recordingFile = null
        }
    }
    
    /**
     * 将音频录制到文件
     */
    private fun recordAudioToFile() {
        val file = recordingFile ?: return
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val buffer = ByteArray(bufferSize)
        
        try {
            FileOutputStream(file).use { outputStream ->
                // 写入 WAV 文件头（占位）
                writeWavHeader(outputStream, 0, SAMPLE_RATE, 1, 16)
                
                var totalBytes = 0
                
                // 录音循环
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
                
                // 更新 WAV 文件头
                outputStream.flush()
                updateWavHeader(file, totalBytes)
                
                Log.d(TAG, "✓ 录音完成，总字节数: $totalBytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 录音写入文件失败: ${e.message}", e)
            _error.value = "录音写入失败: ${e.message}"
        }
    }
    
    /**
     * 写入 WAV 文件头
     */
    private fun writeWavHeader(
        out: FileOutputStream,
        dataLength: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        
        val header = ByteArray(44)
        
        // RIFF chunk
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        val chunkSize = 36 + dataLength
        header[4] = (chunkSize and 0xff).toByte()
        header[5] = ((chunkSize shr 8) and 0xff).toByte()
        header[6] = ((chunkSize shr 16) and 0xff).toByte()
        header[7] = ((chunkSize shr 24) and 0xff).toByte()
        
        // WAVE format
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        header[16] = 16  // fmt chunk size
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        header[20] = 1  // PCM format
        header[21] = 0
        
        header[22] = channels.toByte()
        header[23] = 0
        
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        
        header[32] = (blockAlign.toInt() and 0xff).toByte()
        header[33] = ((blockAlign.toInt() shr 8) and 0xff).toByte()
        
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        header[40] = (dataLength and 0xff).toByte()
        header[41] = ((dataLength shr 8) and 0xff).toByte()
        header[42] = ((dataLength shr 16) and 0xff).toByte()
        header[43] = ((dataLength shr 24) and 0xff).toByte()
        
        out.write(header)
    }
    
    /**
     * 更新 WAV 文件头中的数据长度
     */
    private fun updateWavHeader(file: File, dataLength: Int) {
        try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                // 更新 RIFF chunk size (offset 4)
                raf.seek(4)
                val chunkSize = 36 + dataLength
                raf.write(byteArrayOf(
                    (chunkSize and 0xff).toByte(),
                    ((chunkSize shr 8) and 0xff).toByte(),
                    ((chunkSize shr 16) and 0xff).toByte(),
                    ((chunkSize shr 24) and 0xff).toByte()
                ))
                
                // 更新 data chunk size (offset 40)
                raf.seek(40)
                raf.write(byteArrayOf(
                    (dataLength and 0xff).toByte(),
                    ((dataLength shr 8) and 0xff).toByte(),
                    ((dataLength shr 16) and 0xff).toByte(),
                    ((dataLength shr 24) and 0xff).toByte()
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 更新 WAV 文件头失败: ${e.message}", e)
        }
    }
    
    /**
     * 调用 Whisper API 识别音频
     */
    private suspend fun recognizeAudio(file: File): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "→ 发送音频到 Whisper API...")
            
            // 构建 multipart 请求
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody("audio/wav".toMediaTypeOrNull())
                )
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "zh")  // 中文
                .addFormDataPart("response_format", "json")
                .build()
            
            val request = Request.Builder()
                .url(WHISPER_API_URL)
                .addHeader("Authorization", "Bearer ${AppConfig.API_KEY}")
                .post(requestBody)
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Log.d(TAG, "← Whisper API 响应: HTTP ${response.code}")
            
            if (!response.isSuccessful) {
                val errorMsg = "识别失败: HTTP ${response.code}"
                Log.e(TAG, "✗ $errorMsg\n响应: $responseBody")
                _error.value = errorMsg
                return@withContext ""
            }
            
            // 解析响应
            val jsonResponse = JSONObject(responseBody ?: "{}")
            val text = jsonResponse.optString("text", "")
            
            if (text.isEmpty()) {
                Log.w(TAG, "⚠ 识别结果为空")
            }
            
            return@withContext text
            
        } catch (e: IOException) {
            Log.e(TAG, "✗ 网络请求失败: ${e.message}", e)
            _error.value = "网络请求失败: ${e.message}"
            return@withContext ""
        } catch (e: Exception) {
            Log.e(TAG, "✗ 识别失败: ${e.message}", e)
            _error.value = "识别失败: ${e.message}"
            return@withContext ""
        }
    }
    
    /**
     * 获取录音文件路径（用于语音消息显示）
     */
    fun getRecordingFilePath(): String? {
        return recordingFile?.absolutePath
    }
    
    /**
     * 获取录音时长（秒）
     */
    fun getRecordingDuration(): Int {
        val file = recordingFile ?: return 0
        if (!file.exists()) return 0
        
        return try {
            val mediaPlayer = android.media.MediaPlayer()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            val duration = (mediaPlayer.duration / 1000).coerceAtLeast(1)
            mediaPlayer.release()
            duration
        } catch (e: Exception) {
            Log.e(TAG, "获取录音时长失败: ${e.message}", e)
            1
        }
    }
    
    /**
     * 取消录音
     */
    fun cancelRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _isListening.value = false
        
        // 清理临时文件
        recordingFile?.delete()
        recordingFile = null
        
        Log.d(TAG, "✓ 录音已取消")
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        cancelRecording()
        Log.d(TAG, "✓ 资源已清理")
    }
}
