# 语音对话模式说明

## 概述

应用支持两种语音对话模式，用户可以在聊天界面自由切换：

## 🎤 简单模式（默认）

**工作流程：**
```
用户说话 → 录制音频 → Whisper API 识别 → 文字回复 → 显示文本
```

**特点：**
- ✅ **稳定可靠**：使用成熟的 Whisper API
- ✅ **广泛兼容**：所有设备都可用，包括模拟器
- ✅ **成本较低**：只调用语音识别 API
- ✅ **易于调试**：可以看到识别的文字
- ✅ **支持编辑**：识别结果可以修改后再发送

**适用场景：**
- VectorEngine 等不支持 Realtime API 的平台
- 需要查看和编辑识别文本
- 对延迟要求不高的场景
- 成本敏感的应用

**技术实现：**
- 使用 `CloudVoiceRecognizer.kt`
- 调用 Whisper API (`/v1/audio/transcriptions`)
- 录音格式：WAV (16kHz, PCM16, Mono)
- 识别时间：通常 1-3 秒

## 🔊 实时对话模式

**工作流程：**
```
用户说话 → 音频流 → gpt-4o-realtime-preview → 音频流 → 直接播放
```

**特点：**
- ⚡ **超低延迟**：端到端音频流，无需转换
- 🗣️ **自然对话**：支持实时打断，更像真人对话
- 🎯 **语音原生**：保留语气、情感等非文字信息
- 🔄 **双向流式**：同时发送和接收音频

**适用场景：**
- 需要自然对话体验
- 对延迟敏感的实时交互
- 语音助手、客服等应用
- API 提供商支持 Realtime API

**技术实现：**
- 使用 `VoiceRealtimeService.kt`
- WebSocket 连接：`wss://api.vectorengine.ai/v1/realtime`
- 模型：`gpt-4o-realtime-preview`
- 音频格式：PCM16, 24kHz

**限制：**
- ⚠️ **需要 API 支持**：VectorEngine 目前不支持
- 💰 **成本较高**：实时流式处理成本更高
- 🔌 **需要稳定网络**：WebSocket 连接质量要求高

## 模式切换

### UI 切换

在聊天输入框上方，点击模式切换按钮：

```
┌──────────────────────────────┐
│ 🎤 简单模式  |  语音识别模式  │ ← 点击切换
└──────────────────────────────┘
```

或

```
┌──────────────────────────────┐
│ 🔊 实时对话  |  端到端语音对话 │ ← 点击切换
└──────────────────────────────┘
```

### 代码切换

```kotlin
// 在 ChatViewModel 中
viewModel.toggleVoiceMode()

// 或直接设置模式
_voiceMode.value = AppConfig.VoiceMode.SIMPLE
_voiceMode.value = AppConfig.VoiceMode.REALTIME
```

### 配置默认模式

在 `AppConfig.kt` 中设置：

```kotlin
var VOICE_MODE = VoiceMode.SIMPLE  // 默认简单模式
// 或
var VOICE_MODE = VoiceMode.REALTIME  // 默认实时模式
```

## 自动降级机制

当用户选择**实时对话模式**但 API 不支持时，系统会自动处理：

1. **检测失败**：连接 Realtime API 失败或收不到响应
2. **自动切换**：自动切换回简单模式
3. **提示用户**：显示 Snackbar 提示："实时语音对话不可用，已切换到简单模式"
4. **无缝继续**：使用 Whisper API 继续语音识别

```kotlin
// VoiceRealtimeService 错误回调
voiceService.startRealtimeConversation { error ->
    if (error.contains("不支持") || error.contains("连接失败")) {
        // 自动切换到简单模式
        _voiceMode.value = AppConfig.VoiceMode.SIMPLE
        _snackbarMessage.value = "实时语音对话不可用，已切换到简单模式"
        startCloudVoiceRecognition()  // 使用简单模式
    }
}
```

## 对比表格

| 特性 | 简单模式 | 实时对话模式 |
|------|---------|-------------|
| **延迟** | 1-3 秒 | <500 毫秒 |
| **设备要求** | 所有设备 | 所有设备 |
| **API 要求** | Whisper API | Realtime API |
| **网络稳定性** | 一般 | 高 |
| **成本** | 低 | 高 |
| **文字编辑** | ✅ 支持 | ❌ 不支持 |
| **情感保留** | ❌ 无 | ✅ 有 |
| **支持打断** | ❌ 无 | ✅ 有 |
| **调试难度** | 低 | 中等 |
| **VectorEngine** | ✅ 支持 | ❌ 不支持 |
| **OpenAI 官方** | ✅ 支持 | ✅ 支持 |

## 使用建议

### 推荐使用简单模式的情况：

1. **VectorEngine 用户**（必须）
2. 需要查看和编辑识别文本
3. 网络不稳定的环境
4. 对成本敏感的应用
5. 不需要实时对话体验

### 推荐使用实时对话模式的情况：

1. **OpenAI 官方 API 用户**
2. 需要自然对话体验
3. 语音助手类应用
4. 对延迟敏感的场景
5. 需要保留语气、情感等信息

## 迁移到 OpenAI 以使用实时模式

如果想使用实时对话模式，需要迁移到 OpenAI 官方 API：

### 1. 获取 OpenAI API Key

访问 https://platform.openai.com/api-keys

### 2. 修改配置

```kotlin
// AppConfig.kt
const val API_KEY = "sk-your-openai-key"  // OpenAI API Key
const val VOICE_WEBSOCKET_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"
var VOICE_MODE = VoiceMode.REALTIME  // 启用实时模式
```

### 3. 测试连接

点击麦克风按钮，说话测试。如果连接成功，会看到：
- 录音时显示音频波形
- AI 回复会直接播放语音
- 支持实时打断

### 4. 成本估算

Realtime API 定价（截至2024年）：
- 音频输入：$0.06 / 分钟
- 音频输出：$0.24 / 分钟
- 文字输入：$5.00 / 1M tokens
- 文字输出：$20.00 / 1M tokens

示例：10分钟对话
- 输入：10 × $0.06 = $0.60
- 输出：10 × $0.24 = $2.40
- **总计：$3.00**

相比简单模式（仅 Whisper）：
- Whisper：$0.006 / 分钟
- 10分钟仅需：$0.06

**成本差异：50 倍**

## 常见问题

### Q1: 为什么实时模式连接失败？

A: VectorEngine 不支持 Realtime API。需要使用 OpenAI 官方 API。

### Q2: 简单模式可以实现对话吗？

A: 可以。简单模式也支持多轮对话，只是每次需要：说话 → 识别 → 发送 → 接收回复。

### Q3: 实时模式能看到文字吗？

A: 可以。Realtime API 会同时返回音频和转录文本，UI 会显示。

### Q4: 如何判断当前使用哪个模式？

A: 查看输入框上方的模式指示器：
- 🎤 简单模式 = Whisper 语音识别
- 🔊 实时对话 = 端到端语音对话

### Q5: 切换模式会影响正在进行的对话吗？

A: 不会。模式切换只影响下一次语音录音。

### Q6: 实时模式支持哪些语言？

A: gpt-4o-realtime-preview 支持多种语言，包括中文、英文等。

### Q7: 简单模式的识别准确率如何？

A: Whisper 是目前最先进的语音识别模型之一，准确率很高，尤其对中文支持很好。

### Q8: 实时模式的延迟有多低？

A: 通常在 200-500 毫秒，取决于网络质量。

## 技术架构

### 简单模式架构

```
ChatScreen (UI)
    ↓
ChatViewModel
    ↓
CloudVoiceRecognizer
    ↓
Whisper API (/v1/audio/transcriptions)
    ↓
返回文字 → 发送对话 API → 显示回复
```

### 实时模式架构

```
ChatScreen (UI)
    ↓
ChatViewModel
    ↓
VoiceRealtimeService
    ↓
WebSocket (wss://.../realtime)
    ↓
gpt-4o-realtime-preview
    ↓
音频流 ← → 音频流（双向）
```

## 开发者文档

### 添加新的语音模式

如果需要添加第三种模式（例如使用其他 API）：

1. 在 `AppConfig.kt` 添加枚举：
```kotlin
enum class VoiceMode {
    SIMPLE,
    REALTIME,
    CUSTOM  // 新模式
}
```

2. 在 `ChatViewModel.kt` 添加处理逻辑：
```kotlin
when (_voiceMode.value) {
    VoiceMode.SIMPLE -> startCloudVoiceRecognition()
    VoiceMode.REALTIME -> startRealtimeVoiceConversation()
    VoiceMode.CUSTOM -> startCustomVoiceService()  // 新实现
}
```

3. 更新 UI 显示文本

### 调试技巧

**查看日志：**
```bash
adb logcat | grep -E "ChatViewModel|CloudVoiceRecognizer|VoiceRealtimeService"
```

**简单模式日志：**
```
ChatViewModel: 使用简单模式：Whisper 语音识别
CloudVoiceRecognizer: ✓ 开始录音
CloudVoiceRecognizer: ✓ 录音已停止，准备发送到云端识别
CloudVoiceRecognizer: → 发送音频到 Whisper API...
CloudVoiceRecognizer: ✓ 识别成功: 你好，请帮我...
```

**实时模式日志：**
```
ChatViewModel: 使用实时模式：端到端语音对话
VoiceRealtimeService: ✓ WebSocket 连接已建立
VoiceRealtimeService: ✓ 音频录制已启动
VoiceRealtimeService: → 发送音频片段: 4800 bytes
VoiceRealtimeService: ← 收到音频响应: 9600 bytes
VoiceRealtimeService: ✓ 播放音频回复
```

## 总结

- **简单模式**：适合大多数用户，稳定可靠，成本低
- **实时模式**：适合追求极致体验的用户，需要 OpenAI API
- **自动降级**：确保在任何情况下都能使用语音功能
- **灵活切换**：用户可以根据需求随时切换模式

选择适合你的模式，享受语音对话的便利！🎉
