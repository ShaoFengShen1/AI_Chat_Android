package com.example.compose.jetchat.config

/**
 * 应用配置
 * 
 * 在这里修改您的 API 配置
 */
object AppConfig {
    /**
     * API 基础 URL
     * 
     * VectorEngine API 地址
     */
    const val CHAT_API_URL = "https://api.vectorengine.ai/v1/chat/completions"
    const val IMAGE_API_URL = "https://api.vectorengine.ai/v1/images/generations"

    /**
     * VectorEngine API Key
     * 
     * ⚠️ 重要：请替换为你自己的 API Key
     * 获取地址：https://vectorengine.ai
     * 
     * ⚠️ 安全提示：不要将真实的 API Key 提交到 Git！
     */
    const val API_KEY = "sk-3Epi3l7tfMiXVMDSyc6t9KjlXHLhLkSxjgTh14tL8VDoQnvS"  // 替换为你的 API Key

    /**
     * API 模型名称
     * 
     * 当前使用: gemini-2.5-pro (对话) 和 dall-e-3 (图片生成)
     */
    const val CHAT_MODEL = "gpt-5-mini"  // 对话模型
    const val IMAGE_MODEL = "doubao-seedream-3-0-t2i-250415"  // 图片生成模型
    const val INTENT_MODEL = "deepseek-chat"  // 意图识别模型（轻量快速）
    const val VOICE_MODEL = "gpt-4o-mini-realtime-preview"  // 语音对话模型（mini版，更经济实惠）
    const val WHISPER_MODEL = "whisper-1"  // 语音转文字模型（云端识别，类似微信/QQ）
    
    /**
     * WebSocket API URL（用于实时语音对话）
     * 
     * 使用 gpt-4o-mini-realtime-preview 模型：
     * - 录音 → 发送音频流 → AI处理 → 返回语音回复 → 自动播放
     * - 比 gpt-4o-realtime-preview 便宜约 90%
     * - 功能完全相同，只是推理能力略弱
     * 
     * 注意：
     * - 完整 URL 格式：wss://api.vectorengine.ai/v1/realtime?model=模型名
     * - 需要在请求头中包含 Authorization 和 OpenAI-Beta
     * - 如果无法连接，可能需要：
     *   1. 检查 API Key 是否有权限
     *   2. 确认 API 提供商是否支持 Realtime API
     *   3. 尝试使用代理
     */
    const val VOICE_WEBSOCKET_URL = "wss://api.vectorengine.ai/v1/realtime?model=$VOICE_MODEL"
    
    /**
     * Whisper API URL（语音转文字）
     * 
     * 云端语音识别，类似微信、QQ、Kimi 的语音识别功能
     * 不依赖设备本地语音识别引擎，所有设备都可用
     */
    const val WHISPER_API_URL = "https://api.vectorengine.ai/v1/audio/transcriptions"
    
    /**
     * WebSocket 连接超时配置（毫秒）
     */
    const val WEBSOCKET_CONNECT_TIMEOUT_MS = 30000L  // 30秒
    const val WEBSOCKET_READ_TIMEOUT_MS = 0L  // 无限制（实时流）
    const val WEBSOCKET_PING_INTERVAL_MS = 30000L  // 30秒心跳
    
    /**
     * 语音对话模式
     * 
     * SIMPLE: 简单模式（语音转文字 → 文字对话）
     *   - 流程：录音 → Whisper识别 → 文字回复
     *   - 优点：稳定、快速、成本低
     *   - 适用：只需要文字回复的场景
     * 
     * REALTIME: 实时模式（真语音对话，带 TTS 语音回复）
     *   - 流程：录音 → Whisper 转文字 → Chat API 生成回复 → TTS 转语音 → 播放
     *   - 优点：完整的语音对话体验（听到 AI 说话）
     *   - 使用模型：whisper-1 + gemini-2.5-pro + gpt-4o-mini-tts
     *   - 适用：需要语音回复的对话场景
     */
    enum class VoiceMode {
        SIMPLE,    // 简单模式：Whisper 语音识别（仅文字）
        REALTIME   // 实时模式：Whisper + Chat + TTS（语音回复）
    }
    
    /**
     * 当前语音对话模式（可在 UI 中切换）
     */
    var VOICE_MODE = VoiceMode.REALTIME  // 默认实时模式（简单模式已禁用）
    
    /**
     * 豆包端到端实时语音对话配置
     * 
     * 使用豆包 Realtime API 进行端到端语音对话
     * WebSocket URL: wss://openspeech.bytedance.com/api/v3/realtime/dialogue
     * 
     * ⚠️ 重要：请在火山引擎控制台获取以下参数
     * 获取地址：https://console.volcengine.com/speech
     * 
     * 注意事项：
     * 1. 需要开通豆包端到端实时语音大模型服务
     * 2. AppID 和 AccessKey 可在控制台查看
     * 3. 默认限流：60 QPM，10000 TPM
     */
    const val DOUBAO_WEBSOCKET_URL = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
    const val DOUBAO_APP_ID = "5378919538"  // ⚠️ 替换为你的 AppID
    const val DOUBAO_ACCESS_KEY = "IZADZfCNNyC6FdvWvmDQdseW1w2aIDdl"  // ⚠️ 替换为你的 Access Key
    const val DOUBAO_APP_KEY = "PlgvMymc7f3tQnJ6"  // 固定值（根据官方文档）
    
    /**
     * 豆包 ASR 配置
     * 
     * end_smooth_window_ms: 判断用户说话停止的时间窗口（毫秒）
     * - 范围：500ms - 50000ms
     * - 默认：1500ms
     * - 当前：3000ms（适合正常语速，不容易截断）
     * - 说明：用户停顿超过此时间后，系统认为说话结束
     * 
     * 重要提示：
     * - 此参数仅影响"说话结束判定"，不影响"打断功能"
     * - 用户可以随时说话打断 AI，服务端会立即通过 EVENT_ASR_INFO 触发打断
     * - 硬件回声消除(AEC)已启用，用户说话时 AI 语音会自动停止
     */
    const val DOUBAO_END_SMOOTH_WINDOW_MS = 3000  // 3秒停顿判定
    
    /**
     * 豆包音频播放优化配置
     * 
     * AUDIO_BUFFER_MULTIPLIER: AudioTrack 缓冲区倍数
     * - 默认：2倍最小缓冲
     * - 当前：4倍（减少卡顿，增加流畅度）
     * - 说明：缓冲区越大越流畅，但延迟会略微增加
     * 
     * PRE_BUFFER_PACKETS: 预缓冲音频包数量
     * - 默认：1（立即播放）
     * - 当前：3（积累3个包再播放）
     * - 说明：预缓冲可以避免播放初期的卡顿
     * 
     * BATCH_WRITE_SIZE: 批量写入音频包数量
     * - 默认：1（逐个写入）
     * - 当前：5（批量写入）
     * - 说明：减少系统调用次数，提高播放效率
     */
    const val AUDIO_BUFFER_MULTIPLIER = 4  // 缓冲区倍数
    const val PRE_BUFFER_PACKETS = 3       // 预缓冲包数
    const val BATCH_WRITE_SIZE = 5         // 批量写入大小
    
    /**
     * 会话类型枚举
     * 
     * NORMAL: 普通聊天（文字对话）
     * DOUBAO_REALTIME: 豆包端到端实时语音对话
     */
    enum class SessionType {
        NORMAL,           // 普通文字聊天
        DOUBAO_REALTIME   // 豆包端到端实时语音对话
    }
    
    /**
     * 意图识别配置
     * 
     * USE_AI_INTENT_DETECTION = true:  使用AI模型识别意图（推荐，准确率更高）
     * USE_AI_INTENT_DETECTION = false: 使用正则表达式（备选方案）
     */
    const val USE_AI_INTENT_DETECTION = true
    
    /**
     * 是否优化图片生成Prompt
     * 
     * 当检测到图片生成意图时，自动调用AI优化Prompt，提升生成质量
     */
    const val OPTIMIZE_IMAGE_PROMPT = true
    
    /**
     * 图片生成配置
     * 
     * IMAGE_GENERATION_SIZE: 生成图片尺寸
     * DALL-E 3 支持的尺寸：
     * - "1024x1024": 方形图片（推荐，~1.5-2MB）
     * - "1024x1792": 竖版图片（~2-2.5MB）
     * - "1792x1024": 横版图片（~2-2.5MB）
     * 
     * 注意：虽然生成1024x1024，但会自动压缩到IMAGE_DISPLAY_SIZE显示
     * 
     * IMAGE_DISPLAY_SIZE: 显示图片最大尺寸（像素）
     * - 移动端屏幕有限，200-256px足够清晰
     * - 通过aggressive压缩，最终文件仅15-25KB
     */
    const val IMAGE_GENERATION_SIZE = "1024x1024"  // DALL-E 3最小支持尺寸
    const val IMAGE_DISPLAY_SIZE = 220  // 显示尺寸（px），稍微提高清晰度

    /**
     * 网络请求超时时间（秒）
     */
    const val TIMEOUT_SECONDS = 60L  // 图片生成需要更长时间
    const val IMAGE_DOWNLOAD_TIMEOUT_SECONDS = 30L  // 图片下载超时（更短）

    /**
     * 是否启用日志
     */
    const val ENABLE_LOGGING = true

    /**
     * 是否使用代理
     * 
     * OpenRouter 可直接访问，无需代理
     */
    const val USE_PROXY = false

    /**
     * 代理服务器地址
     * 
     * Android 模拟器访问宿主机代理，使用特殊地址 10.0.2.2
     * 例如：如果你在宿主机上运行了代理服务器（如 Clash）在 7890 端口
     */
    const val PROXY_HOST = "10.0.2.2"  // 模拟器访问宿主机使用此地址

    /**
     * 代理服务器端口
     * 
     * 常见代理端口：
     * - Clash: 7890
     * - V2Ray: 10809
     * - 其他代理软件请查看其设置
     */
    const val PROXY_PORT = 7890  // 修改为你的代理端口
}
