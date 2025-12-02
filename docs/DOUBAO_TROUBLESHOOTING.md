# 豆包实时对话连接故障排查

⚠️ **重要更新 (2025-12-02)**: 
根据官方文档深度分析,已识别关键问题。**请优先查看项目根目录的 `DOUBAO_CRITICAL_FIXES.md` 文件**,其中包含:
- 🔴 音频格式不匹配问题 (播放失败的根本原因)
- 🔴 WebSocket连接详细诊断步骤
- 🔴 二进制协议规范说明

---

## 🔴 首要修复: 音频格式配置

### 症状
WebSocket连接成功,但AI回复无声音或噪音

### 原因
服务端默认返回OGG Opus音频,需要解码才能播放

### ✅ 解决方案 (已自动应用)
最新代码已配置为PCM格式,无需手动修改:
```kotlin
put("audio_config", JSONObject().apply {
    put("format", "pcm_s16le")  // PCM 16bit小端序
    put("sample_rate", 24000)
})
```

---

## 问题：WebSocket 连接失败

### 检查清单

#### 1. 检查 API 凭证配置 ✅

打开 `AppConfig.kt`，确认以下配置已正确填写：

```kotlin
const val DOUBAO_APP_ID = "你的AppID"  // 不是 "YOUR_APP_ID"
const val DOUBAO_ACCESS_KEY = "你的AccessKey"  // 不是 "YOUR_ACCESS_KEY"
```

**如何获取**：
- 访问：https://console.volcengine.com/speech
- 开通服务：豆包端到端实时语音大模型
- 查看凭证：在控制台获取 AppID 和 Access Key

#### 2. 检查网络连接 🌐

**测试方法**：
1. 确保手机/模拟器可以访问互联网
2. 尝试在浏览器打开：https://openspeech.bytedance.com
3. 如果无法访问，可能需要：
   - 检查 WiFi/移动网络
   - 检查防火墙设置
   - 使用 VPN（如果在受限网络环境）

#### 3. 检查权限配置 🔒

确保 `AndroidManifest.xml` 包含：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**运行时权限**：
在应用运行时，需要授予"麦克风"权限。

#### 4. 查看详细日志 📋

在 Android Studio 的 Logcat 中过滤：
```
Tag: DoubaoRealtime
```

**关键日志**：

✅ **成功连接**：
```
D DoubaoRealtime: 启动豆包端到端实时对话
D DoubaoRealtime: WebSocket URL: wss://openspeech.bytedance.com/api/v3/realtime/dialogue
D DoubaoRealtime: App ID: 你的AppID
D DoubaoRealtime: → 正在建立 WebSocket 连接...
D DoubaoRealtime: ✅ WebSocket 连接成功
D DoubaoRealtime: ✓ 连接已建立
```

❌ **连接失败 - 配置错误**：
```
E DoubaoRealtime: 请先在 AppConfig.kt 中配置豆包 API 凭证
```
**解决方案**：填写正确的 AppID 和 Access Key

❌ **连接失败 - 网络错误**：
```
E DoubaoRealtime: ❌ WebSocket 连接失败: Unable to resolve host
```
**解决方案**：检查网络连接

❌ **连接失败 - 认证错误**：
```
E DoubaoRealtime: ❌ WebSocket 连接失败
响应码: 401
响应消息: Unauthorized
```
**解决方案**：检查 AppID 和 Access Key 是否正确

❌ **连接失败 - 服务未开通**：
```
E DoubaoRealtime: ❌ WebSocket 连接失败
响应码: 403
响应消息: Forbidden
```
**解决方案**：在火山引擎控制台开通"豆包端到端实时语音大模型"服务

#### 5. 常见错误码

| 错误码 | 说明 | 解决方案 |
|--------|------|----------|
| 401 | 认证失败 | 检查 Access Key 是否正确 |
| 403 | 权限不足 | 开通服务或检查 AppID 权限 |
| 404 | 接口不存在 | 检查 URL 是否正确 |
| 500 | 服务器错误 | 稍后重试或联系技术支持 |
| 503 | 服务不可用 | 稍后重试 |

#### 6. 测试步骤

1. **基础配置测试**

在 `DoubaoRealtimeService.kt` 的 `startRealtimeConversation()` 方法开始处查看日志：

```
2025-12-02 12:38:53.xxx DoubaoRealtime: 启动豆包端到端实时对话
2025-12-02 12:38:53.xxx DoubaoRealtime: WebSocket URL: wss://...
2025-12-02 12:38:53.xxx DoubaoRealtime: App ID: 你的AppID
```

如果看不到这些日志，说明方法没有被调用。

2. **网络测试**

使用 curl 测试连接（在电脑终端）：

```bash
curl -v -H "X-Api-App-ID: 你的AppID" \
     -H "X-Api-Access-Key: 你的AccessKey" \
     -H "X-Api-Resource-Id: volc.speech.dialog" \
     -H "X-Api-App-Key: PlgvMymc7f3tQnJ6" \
     "https://openspeech.bytedance.com/api/v3/realtime/dialogue"
```

3. **权限测试**

在应用启动时，应该弹出麦克风权限请求。如果没有，检查权限配置。

#### 7. 模拟器 vs 真机

**模拟器问题**：
- 某些模拟器可能无法正常建立 WebSocket 连接
- 音频功能可能不完整

**建议**：
- 优先使用真实设备测试
- 如果使用模拟器，推荐 Google 官方模拟器（API 30+）

#### 8. 代理/VPN 设置

如果使用代理，需要在 `OkHttpClient` 中配置：

```kotlin
private val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(AppConfig.WEBSOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(AppConfig.WEBSOCKET_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    // 添加代理配置
    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.example.com", 8080)))
    .build()
```

### 完整诊断流程

```
1. 检查配置文件
   ├─ AppID ✓
   ├─ Access Key ✓
   └─ URL ✓
   
2. 检查网络
   ├─ 互联网连接 ✓
   ├─ DNS 解析 ✓
   └─ 防火墙 ✓
   
3. 检查权限
   ├─ INTERNET ✓
   ├─ RECORD_AUDIO ✓
   └─ 运行时权限 ✓
   
4. 启动应用
   ├─ 点击 + 按钮
   ├─ 选择"实时语音对话"
   └─ 点击"开始对话"
   
5. 查看日志
   ├─ 连接日志 ✓
   ├─ 错误信息 ✓
   └─ 响应码 ✓
```

### 获取帮助

如果按照以上步骤仍无法解决，请提供：

1. **完整日志**（从 Logcat 复制）
2. **错误信息**（包括错误码）
3. **配置信息**（隐藏敏感信息）
4. **设备信息**（Android 版本、设备型号）
5. **网络环境**（WiFi/4G、是否使用代理）

**提交 Issue**：
- GitHub: [项目 Issues 页面]
- 或参考官方文档：https://www.volcengine.com/docs/6561/

### 快速测试脚本

你可以在 `startRealtimeConversation()` 开始处添加测试代码：

```kotlin
Log.d(TAG, "=== 连接测试开始 ===")
Log.d(TAG, "URL: ${AppConfig.DOUBAO_WEBSOCKET_URL}")
Log.d(TAG, "AppID: ${AppConfig.DOUBAO_APP_ID}")
Log.d(TAG, "AccessKey: ${AppConfig.DOUBAO_ACCESS_KEY.take(10)}...") // 只显示前10个字符
Log.d(TAG, "网络状态: ${if (isNetworkAvailable()) "已连接" else "未连接"}")
Log.d(TAG, "===================")
```

添加网络检测方法：

```kotlin
private fun isNetworkAvailable(): Boolean {
    val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
```

---

**祝你成功连接！** 🎉
