# 豆包端到端实时语音对话功能 - 集成说明

## 功能概述

已成功集成豆包端到端实时语音对话功能，支持低延迟的语音到语音对话体验。用户可以在创建新对话时选择：
- **普通聊天**：文字对话，支持图片生成、文档上传
- **实时语音对话**：豆包端到端实时语音，超低延迟，自然对话

## 配置步骤

### 1. 获取豆包API凭证

访问火山引擎控制台获取API凭证：
- 地址：https://console.volcengine.com/speech
- 需要开通：**豆包端到端实时语音大模型**服务
- 获取参数：
  - `AppID`
  - `Access Key`

### 2. 修改配置文件

编辑 `app/src/main/java/com/example/compose/jetchat/config/AppConfig.kt`：

```kotlin
/**
 * 豆包端到端实时语音对话配置
 */
const val DOUBAO_WEBSOCKET_URL = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
const val DOUBAO_APP_ID = "YOUR_APP_ID"  // ⚠️ 替换为你的 AppID
const val DOUBAO_ACCESS_KEY = "YOUR_ACCESS_KEY"  // ⚠️ 替换为你的 Access Key
```

### 3. 权限配置

确保 `AndroidManifest.xml` 包含以下权限（已包含）：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

## 使用说明

### 创建实时语音对话

1. 在对话列表页面，点击右下角的 **+** 按钮
2. 在弹出的对话框中选择 **🎙️ 实时语音对话**
3. 进入实时语音对话界面

### 进行语音对话

在实时语音对话界面：
1. 点击 **开始对话** 按钮启动实时对话
2. 系统会：
   - 建立WebSocket连接
   - 启动麦克风录音
   - 实时发送音频到服务器
   - 接收AI语音回复并播放
3. 对话过程中：
   - 可以随时说话，AI会实时识别
   - AI回复会直接播放语音
   - 支持打断AI的回复（检测到用户说话时自动停止）
4. 点击 **停止对话** 结束会话

### 技术特性

- ✅ **超低延迟**：端到端音频流处理，延迟 < 500ms
- ✅ **自然对话**：支持实时打断，更像真人对话
- ✅ **音频格式**：
  - 上传：PCM 16000Hz 单声道 16bit
  - 下载：OGG Opus 24000Hz（自动解码播放）
- ✅ **自定义人设**：支持配置 `botName`、`systemRole`、`speakingStyle`
- ✅ **音色选择**：支持4种精品音色（vv、xiaohe、yunzhou、xiaotian）

## 文件结构

### 新增文件

```
app/src/main/java/com/example/compose/jetchat/
├── config/
│   └── AppConfig.kt                    # 新增豆包配置
├── data/voice/
│   └── DoubaoRealtimeService.kt       # ✨ 豆包实时对话服务（新增）
├── ui/chat/
│   ├── ChatScreen.kt                   # 修改：添加实时对话UI
│   └── ChatViewModel.kt                # 修改：添加实时对话逻辑
├── ui/chatlist/
│   └── ChatListScreen.kt              # 修改：添加会话类型选择
└── JetchatApp.kt                       # 修改：添加路由参数
```

### 核心类说明

#### `DoubaoRealtimeService.kt`
豆包端到端实时对话服务，负责：
- WebSocket 连接管理
- 二进制协议封装/解析
- 音频录制和播放
- 事件处理（连接、会话、ASR、TTS等）

主要方法：
- `startRealtimeConversation()`: 启动实时对话
- `stopRealtimeConversation()`: 停止实时对话
- `handleBinaryMessage()`: 处理服务端二进制消息
- `buildBinaryFrame()`: 构建客户端二进制帧

#### `ChatViewModel.kt` 新增方法
- `startDoubaoRealtimeConversation()`: 启动豆包实时对话
- `stopDoubaoRealtimeConversation()`: 停止豆包实时对话

#### `ChatScreen.kt` UI更新
- 实时对话模式专属UI卡片
- 开始/停止对话按钮
- 录音状态指示器

#### `ChatListScreen.kt` 新增组件
- `SessionTypeDialog`: 会话类型选择对话框
- 支持选择普通聊天或实时语音对话

## 技术实现细节

### 二进制协议

豆包Realtime API使用自定义二进制协议：

```
[4字节Header] + [可选字段] + [4字节Payload Size] + [Payload]
```

**Header结构**：
```
Byte 0: 协议版本(0x11)
Byte 1: 消息类型 + 标志位
Byte 2: 序列化方法 + 压缩方法
Byte 3: 保留字段
```

**消息类型**：
- `0x01`: 客户端文本请求
- `0x02`: 客户端音频请求
- `0x09`: 服务端文本响应
- `0x0B`: 服务端音频响应
- `0x0F`: 错误信息

### 事件流程

```
客户端                                服务端
  |                                     |
  |------ StartConnection ---------->  |
  |<----- ConnectionStarted ---------- |
  |                                     |
  |------ StartSession -------------->  |
  |<----- SessionStarted ------------- |
  |                                     |
  |------ TaskRequest (音频流) ------> |
  |<----- ASRInfo (检测到首字) ------- |
  |<----- ASRResponse (识别结果) ----- |
  |<----- ASREnded (说话结束) -------- |
  |<----- ChatResponse (文本回复) ---- |
  |<----- TTSResponse (音频流) ------- |
  |                                     |
  |------ FinishSession ------------->  |
  |<----- SessionFinished ------------ |
```

### 音频处理

**录音**：
- 采样率：16000Hz
- 声道：单声道
- 位深：16bit
- 缓冲区：640字节（20ms音频）
- 发送间隔：20ms

**播放**：
- 采样率：24000Hz
- 格式：OGG Opus（服务端返回）
- 使用 `AudioTrack` 实时播放
- 队列缓冲：`ConcurrentLinkedQueue`

### 状态管理

使用 Kotlin `StateFlow` 管理状态：
- `isRecording`: 录音状态
- `isPlaying`: 播放状态
- `transcription`: 识别文本
- `responseText`: AI回复文本
- `connectionState`: 连接状态

## 常见问题

### Q1: 连接失败怎么办？

**检查项**：
1. AppID 和 Access Key 是否正确
2. 网络是否正常（可能需要科学上网）
3. 是否开通了豆包端到端实时语音大模型服务
4. 检查控制台日志中的错误信息

### Q2: 没有声音播放？

**检查项**：
1. 确保已授予录音权限
2. 检查设备音量
3. 查看日志中是否收到 `TTSResponse` 事件
4. 确认服务端返回的音频格式（应为 OGG Opus）

### Q3: 识别不准确？

**优化方法**：
1. 在安静环境下使用
2. 靠近麦克风说话
3. 语速适中、吐字清晰
4. 可以启用 `enable_asr_twopass` 提升准确率

### Q4: 延迟较高？

**优化方法**：
1. 使用稳定的网络连接
2. 减少 `end_smooth_window_ms` 参数（默认1500ms）
3. 使用有线网络或优质WiFi

### Q5: 成本如何计算？

**计费说明**：
- 限流：默认 60 QPM，10000 TPM
- 按音频时长和Token计费
- 详见火山引擎控制台计费说明

## 下一步优化

### 功能扩展
- [ ] 支持自定义克隆音色（SC版本）
- [ ] 支持外部RAG输入
- [ ] 支持内置联网搜索
- [ ] 添加对话历史记录
- [ ] 支持导出音频文件

### 性能优化
- [ ] 音频压缩优化
- [ ] 网络重连机制
- [ ] 错误恢复策略
- [ ] 音频质量自适应

### UI改进
- [ ] 音频波形可视化
- [ ] 实时字幕显示
- [ ] 情感表情动画
- [ ] 黑暗模式优化

## 参考文档

- 豆包Realtime API官方文档：https://www.volcengine.com/docs/6561/
- 火山引擎控制台：https://console.volcengine.com/speech
- Android音频开发：https://developer.android.com/guide/topics/media/

## 版本信息

- 集成日期：2025-12-02
- Android最低版本：API 24 (Android 7.0)
- 目标版本：API 34 (Android 14)
- Kotlin版本：1.9+
- Compose版本：1.5+

---

**注意**：本功能需要稳定的网络连接和麦克风权限。使用前请确保已正确配置API凭证。
