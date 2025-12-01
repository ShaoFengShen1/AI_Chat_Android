# 新功能更新 - 文档上传与语音对话 (v1.6.0)

## 📝 功能概述

### 1. 文档上传功能 📎

在输入框**左侧**新增回形针图标按钮，支持上传各类文档与 AI 对话：

- **支持格式**：PDF、DOC/DOCX、TXT、Markdown 等
- **使用方式**：
  1. 点击回形针图标
  2. 选择文档文件
  3. 文档名称会显示在输入框提示中
  4. 输入问题或直接发送（默认：请分析这个文档的内容）
  5. AI 会基于文档内容回答

- **技术实现**：
  - 使用 `ActivityResultContracts.GetContent()` 选择文件
  - 自动读取文件内容（TXT 直接读取，其他格式 base64 编码）
  - 文档信息作为消息前缀发送：`[文档名]\n\n用户问题`

### 2. 实时语音对话功能 🎤

在输入框**右侧**新增麦克风图标按钮，支持实时语音交互：

- **触发条件**：输入框为空且无附件时，发送按钮变为麦克风按钮
- **使用方式**：
  1. 点击麦克风按钮开始录音（按钮变红）
  2. 说话时实时传输音频流到 AI
  3. AI 实时语音回复（边说边听）
  4. 再次点击停止录音
  5. 转录文本自动作为消息发送

- **技术实现**：
  - **模型**：`gpt-4o-mini-realtime-preview`
  - **协议**：WebSocket 双向音频流
  - **音频格式**：PCM 16-bit, 16kHz, Mono
  - **功能**：
    - 实时语音识别（Whisper）
    - 实时文本生成
    - 实时语音合成（TTS）
    - 边说边听（low-latency）

---

## 🎨 UI 布局

```
┌─────────────────────────────────────────┐
│  [←] 聊天标题                      [⋮]  │
├─────────────────────────────────────────┤
│                                         │
│  消息列表区域                           │
│                                         │
├─────────────────────────────────────────┤
│  [📎] [🖼️] [输入框...] [🎤/📤]        │
└─────────────────────────────────────────┘

  回形针  图片   消息输入   麦克风/发送
  (文档) (图片)  (文本)    (语音/提交)
```

**逻辑说明**：
- 默认显示：`[📎] [🖼️] [输入框] [🎤]`
- 有输入时：`[📎] [🖼️] [输入框] [📤]` （麦克风变为发送按钮）
- 录音时：`[📎] [🖼️] [输入框] [⏹️]` （麦克风变为停止按钮，红色）

---

## 🔧 技术架构

### 新增文件

```
app/src/main/java/com/example/compose/jetchat/
├── data/
│   └── voice/
│       └── VoiceRealtimeService.kt      # 语音实时对话服务
├── ui/
│   └── chat/
│       ├── ChatScreen.kt                # 更新：UI 增加按钮
│       └── ChatViewModel.kt             # 更新：添加文档和语音逻辑
└── config/
    └── AppConfig.kt                     # 更新：语音 API 配置
```

### 核心类说明

#### VoiceRealtimeService.kt
```kotlin
class VoiceRealtimeService {
    // WebSocket 连接管理
    private var webSocket: WebSocket?
    
    // 音频录制
    private var audioRecord: AudioRecord?
    
    // 状态流
    val isRecording: StateFlow<Boolean>
    val transcription: StateFlow<String>  // 语音转文字
    val response: StateFlow<String>       // AI 回复
    
    // 主要方法
    fun startRealtimeConversation()       // 启动
    fun stopRealtimeConversation()        // 停止
}
```

#### ChatViewModel 新增方法
```kotlin
// 文档相关
fun sendMessageWithDocument(
    content: String,
    documentName: String?,
    documentContent: String?
)

// 语音相关
fun startVoiceRecording()
fun stopVoiceRecording()
val isVoiceRecording: StateFlow<Boolean>
val voiceTranscription: StateFlow<String>
```

### AppConfig 新增配置

```kotlin
object AppConfig {
    // 语音模型
    const val VOICE_MODEL = "gpt-4o-mini-realtime-preview"
    
    // WebSocket URL
    const val VOICE_WEBSOCKET_URL = "wss://api.vectorengine.ai/v1/realtime"
}
```

---

## 🔐 权限配置

### AndroidManifest.xml

```xml
<!-- 麦克风权限（新增） -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- 已有权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
    android:maxSdkVersion="32" />
```

### 运行时权限处理

```kotlin
// 文档选择权限（复用图片权限）
val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_IMAGES
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

// 麦克风权限
val micPermission = Manifest.permission.RECORD_AUDIO
```

---

## 🚀 使用示例

### 1. 文档对话示例

**场景**：上传简历分析

1. 点击回形针 📎
2. 选择 `resume.pdf`
3. 输入："帮我优化这份简历"
4. AI 分析简历内容并给出建议

**实际发送的消息格式**：
```
[resume.pdf]

帮我优化这份简历
```

### 2. 语音对话示例

**场景**：语音提问

1. 保持输入框为空
2. 点击麦克风 🎤（开始录音）
3. 说话："今天天气怎么样？"
4. 点击停止 ⏹️
5. AI 实时语音回复（同时显示文字）

**流程**：
```
用户说话 → 实时转录 → AI理解 → AI语音回复
         (WebSocket)   (文本)   (音频流)
```

---

## 📊 API 调用说明

### 文档上传 API

当前实现将文档内容作为文本消息发送到聊天 API：

```kotlin
// 消息格式
val messageWithDocument = "[$documentName]\n\n$content"

// 调用标准聊天 API
apiService.sendChatRequestWithHistory(history, messageWithDocument, null)
```

> **未来优化**：可以支持 Vision API 识别文档图片，或使用专门的文档解析 API。

### 语音实时 API

使用 WebSocket 协议进行双向通信：

**1. 建立连接**
```kotlin
WebSocket.connect("wss://api.vectorengine.ai/v1/realtime")
Headers: {
    "Authorization": "Bearer YOUR_API_KEY",
    "OpenAI-Beta": "realtime=v1"
}
```

**2. 初始化会话**
```json
{
  "type": "session.update",
  "session": {
    "modalities": ["text", "audio"],
    "instructions": "你是一个友好的AI助手，用中文回答用户的问题。",
    "voice": "alloy",
    "input_audio_format": "pcm16",
    "output_audio_format": "pcm16",
    "input_audio_transcription": {
      "model": "whisper-1"
    }
  }
}
```

**3. 发送音频流**
```json
{
  "type": "input_audio_buffer.append",
  "audio": "base64_encoded_pcm_audio"
}
```

**4. 接收回复**
```json
// 转录完成
{
  "type": "conversation.item.input_audio_transcription.completed",
  "transcript": "用户说的话"
}

// 文字回复（增量）
{
  "type": "response.text.delta",
  "delta": "AI回复的文字片段"
}

// 音频回复（增量）
{
  "type": "response.audio.delta",
  "delta": "base64_encoded_audio_chunk"
}
```

---

## 🧪 测试建议

### 文档上传测试

1. **TXT 文件**：纯文本，直接读取内容
2. **PDF 文件**：Base64 编码（需后端解析）
3. **大文件处理**：测试文件大小限制
4. **权限测试**：拒绝权限后的提示

### 语音对话测试

1. **麦克风权限**：首次使用请求权限
2. **网络稳定性**：WebSocket 断连重连
3. **音频质量**：清晰度、延迟测试
4. **多语言**：中英文混合识别
5. **噪音环境**：嘈杂环境下的识别率

---

## ⚠️ 注意事项

### 1. 文档上传限制

- **文件大小**：建议 < 5MB（API 请求体限制）
- **格式支持**：当前仅支持文本提取，图片/PDF 需额外处理
- **编码问题**：非 UTF-8 文件可能乱码

### 2. 语音对话限制

- **模型支持**：需要 API 提供商支持 Realtime API
  - ⚠️ **重要**：VectorEngine 可能不支持 `gpt-4o-realtime-preview`
  - 建议使用 OpenAI 官方 API：https://platform.openai.com
  - 或其他支持 Realtime API 的提供商
- **网络延迟**：建议 4G/5G 或 Wi-Fi 环境
- **电池消耗**：持续录音和 WebSocket 连接较耗电
- **隐私保护**：音频数据实时传输，请注意隐私政策

**如何切换到 OpenAI 官方 API：**
```kotlin
// AppConfig.kt
const val API_KEY = "sk-your-openai-api-key"
const val VOICE_WEBSOCKET_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"
```

### 3. 成本考虑

- **语音转文字**：Whisper API 按秒计费
- **实时对话**：WebSocket 连接时长计费
- **文档解析**：大文档 token 消耗较多

---

## 🛠️ 待优化项

### 短期优化

- [ ] 添加录音音量可视化（波形图）
- [ ] 支持暂停/继续录音
- [ ] 文档预览功能（查看选中的文档内容）
- [ ] 语音转文字显示在输入框（可编辑）

### 中期优化

- [ ] 支持 PDF 文本提取（PDF.js 或原生库）
- [ ] 支持 OCR 识别图片文档（Tesseract）
- [ ] 添加语音播放控制（暂停/调速）
- [ ] 支持多语言语音选择（voice 参数）

### 长期优化

- [ ] 离线语音识别（本地 Whisper 模型）
- [ ] 语音情绪分析（语气、情感识别）
- [ ] 多人语音对话（多说话人识别）
- [ ] 语音克隆（自定义 TTS 声音）

---

## 📚 相关文档

- **Realtime API 文档**：https://platform.openai.com/docs/guides/realtime
- **WebSocket 协议**：https://datatracker.ietf.org/doc/html/rfc6455
- **AudioRecord 文档**：https://developer.android.com/reference/android/media/AudioRecord
- **文档处理库**：
  - Apache POI (DOC/DOCX)
  - iText (PDF)
  - Tesseract (OCR)

---

## 🎉 总结

**v1.6.0** 新增两大核心功能：

1. **📎 文档上传**：让 AI 理解和分析你的文档内容
2. **🎤 语音对话**：像和真人聊天一样自然流畅

这两个功能大幅提升了用户交互体验，使 Jetchat 从纯文本聊天升级为真正的**多模态 AI 助手**。

---

**更新时间**：2025-11-27  
**版本**：v1.6.0  
**作者**：Jetchat Team
