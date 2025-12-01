package com.example.compose.jetchat.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地语音识别服务（备用方案）
 * 
 * 当 Realtime API 不可用时，使用 Android 系统的 SpeechRecognizer
 * 作为降级方案，实现基本的语音转文字功能
 */
class LocalSpeechRecognizer(private val context: Context) {
    
    companion object {
        private const val TAG = "LocalSpeechRecognizer"
        
        /**
         * 检查设备是否支持语音识别
         */
        fun isRecognitionAvailable(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }
    }
    
    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    /**
     * 开始语音识别
     */
    fun startListening() {
        try {
            // 检查是否支持语音识别
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                val errorMsg = "设备未安装语音识别服务\n\n解决方案：\n" +
                        "1. 安装 Google 语音服务（Google App）\n" +
                        "2. 或在设置中启用语音输入功能"
                _error.value = errorMsg
                Log.e(TAG, errorMsg)
                return
            }
            
            // 创建语音识别器
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            
            // 设置识别监听器
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "✓ 准备好接收语音")
                    _isListening.value = true
                    _error.value = null
                }
                
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "🎤 开始说话")
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // 音量变化（可用于可视化）
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    // 音频缓冲
                }
                
                override fun onEndOfSpeech() {
                    Log.d(TAG, "✓ 说话结束")
                    _isListening.value = false
                }
                
                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                        else -> "未知错误: $error"
                    }
                    
                    Log.e(TAG, "✗ 识别错误: $errorMessage")
                    _error.value = errorMessage
                    _isListening.value = false
                }
                
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.d(TAG, "✓ 识别结果: $text")
                        _transcription.value = text
                    }
                }
                
                override fun onPartialResults(partialResults: Bundle?) {
                    // 部分结果（实时显示）
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.d(TAG, "📝 部分结果: $text")
                        _transcription.value = text
                    }
                }
                
                override fun onEvent(eventType: Int, params: Bundle?) {
                    // 其他事件
                }
            })
            
            // 创建识别意图
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")  // 中文
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)  // 启用部分结果
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            
            // 开始识别
            speechRecognizer?.startListening(intent)
            
            Log.d(TAG, "✓ 语音识别已启动")
            
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别失败: ${e.message}", e)
            _error.value = "启动失败: ${e.message}"
            _isListening.value = false
        }
    }
    
    /**
     * 停止语音识别
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            _isListening.value = false
            Log.d(TAG, "✓ 语音识别已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            _isListening.value = false
            Log.d(TAG, "✓ 语音识别器已销毁")
        } catch (e: Exception) {
            Log.e(TAG, "清理资源失败: ${e.message}", e)
        }
    }
}
