# 项目配置指南

## API 密钥配置

### 1. 获取 API 密钥

访问 [VectorEngine](https://vectorengine.ai) 注册并获取免费 API 密钥。

### 2. 配置方法

编辑文件：`app/src/main/java/com/example/compose/jetchat/config/AppConfig.kt`

```kotlin
const val API_KEY = "your-api-key-here"  // 替换为你的真实密钥
```

### 3. 安全提示

⚠️ **重要：不要将真实的 API Key 提交到 Git！**

如果不小心提交了，请立即：
1. 在 VectorEngine 控制台删除并重新生成密钥
2. 使用 `git filter-branch` 或 `BFG Repo-Cleaner` 清除历史记录

---

## 模型配置
4
### 可用模型

| 模型 | 用途 | 特点 |
|------|------|------|
| **gemini-2.5-pro** | 文本对话 | 上下文理解强，响应快 |
| **gemini-2.5-flash** | 意图识别 | 轻量快速，低延迟 🆕 |
| **dall-e-3** | 图片生成 | 高质量图像，创意表达 |
| **gpt-4** | 文本对话 | 高级推理能力（可选） |

### 切换模型

在 `AppConfig.kt` 中修改：

```kotlin
const val CHAT_MODEL = "gemini-2.5-pro"       // 或 "gpt-4"
const val IMAGE_MODEL = "dall-e-3"
const val INTENT_MODEL = "gemini-2.5-flash"   // 意图识别模型 🆕
```

### AI意图识别配置 🆕

**启用/禁用AI意图识别**

```kotlin
// 在 AppConfig.kt 中
const val USE_AI_INTENT_DETECTION = true   // true=AI识别, false=正则表达式
const val OPTIMIZE_IMAGE_PROMPT = true     // 是否优化图片生成Prompt
```

**功能对比：**

| 特性 | AI意图识别 | 正则表达式 |
|------|-----------|-----------|
| 准确率 | 95%+ | 80%左右 |
| 自然语言 | ✅ 支持复杂表达 | ⚠️ 仅固定模式 |
| Prompt优化 | ✅ 自动优化 | ❌ 不支持 |
| 响应速度 | ~200-500ms | <10ms |
| Token消耗 | ~100 tokens/次 | 0 |
| 降级保护 | ✅ 失败自动降级 | - |

**推荐配置：**
- 生产环境：`USE_AI_INTENT_DETECTION = true`（用户体验更好）
- 测试/开发：`USE_AI_INTENT_DETECTION = false`（节省API调用）

---

## 性能配置

### 摘要系统参数

```kotlin
// 在 ConversationSummaryManager.kt 中
const val SUMMARY_INTERVAL = 10        // 触发间隔：每10轮对话
const val RECENT_MESSAGES_COUNT = 6    // 保留消息：最近6轮完整对话
```

**调优建议：**
- 更频繁摘要（如5轮）→ 更省Token，但API调用更多
- 更少摘要（如15轮）→ 节省API调用，但Token消耗更大

### 图片优化参数 ⚡

```kotlin
// 在 AppConfig.kt 中
const val IMAGE_GENERATION_SIZE = "512x512"  // 生成图片尺寸
const val IMAGE_DISPLAY_SIZE = 200           // 显示尺寸（像素）
const val IMAGE_DOWNLOAD_TIMEOUT_SECONDS = 30L  // 下载超时
```

**DALL-E 3 支持的尺寸：**

| 尺寸 | 文件大小 | 下载时间 | 质量 | 用途 |
|------|---------|---------|------|------|
| **1024x1024** | ~1.5-2MB | 10-15秒 | ⭐⭐⭐⭐⭐ | **方形图片（默认）** |
| 1024x1792 | ~2-2.5MB | 15-20秒 | ⭐⭐⭐⭐⭐ | 竖版图片 |
| 1792x1024 | ~2-2.5MB | 15-20秒 | ⭐⭐⭐⭐⭐ | 横版图片 |

**重要说明：**
- ⚠️ DALL-E 3 只支持上述3种尺寸，不支持512x512等小尺寸
- ✅ 虽然生成1024x1024，但会自动压缩到220px显示（最终15-25KB）
- 🚀 通过激进压缩策略，下载后处理时间<1秒
- 💾 压缩率：1500KB → 20KB（压缩到1.3%）

**显示尺寸优化：**
- `IMAGE_DISPLAY_SIZE = 220`：移动端显示尺寸
- 更小尺寸（150-180px）：更快，但可能模糊
- 更大尺寸（256-300px）：更清晰，但文件更大

**推荐配置：**
```kotlin
const val IMAGE_GENERATION_SIZE = "1024x1024"  // DALL-E 3要求
const val IMAGE_DISPLAY_SIZE = 220             // 平衡质量和速度
```

---

## 网络配置

### 超时设置

```kotlin
const val TIMEOUT_SECONDS = 60L        // 网络超时（秒）
```

**调优建议：**
- WiFi环境：30-45秒足够
- 弱网环境：60-90秒
- 图片生成：建议60秒+

### 代理配置（可选）

如果需要使用代理：

```kotlin
const val USE_PROXY = true
const val PROXY_HOST = "10.0.2.2"      // 模拟器访问宿主机
const val PROXY_PORT = 7890            // Clash默认端口
```

---

## 调试配置

### 启用日志

```kotlin
const val ENABLE_LOGGING = true        // 开发时启用
```

**日志输出示例：**
```
D/ApiService: 发送图片生成请求 - prompt: 生成一只猫, model: dall-e-3
D/ApiService: 请求成功 - 耗时: 3200ms, 图片大小: 25KB
D/ImageCache: 从缓存获取图片
D/SummaryManager: 触发摘要生成 - sessionId: xxx, 消息数: 10
```

### 生产环境配置

发布前务必：
1. 设置 `ENABLE_LOGGING = false`
2. 移除或混淆所有日志输出
3. 配置 ProGuard 规则

---

## 数据库配置

### 当前版本

```kotlin
@Database(
    entities = [ChatMessageEntity::class, SessionSummaryEntity::class],
    version = 5  // 当前版本
)
```

### 添加新表/字段

1. 修改 Entity 类
2. 增加 version 号（如 5 → 6）
3. 创建 Migration：

```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 执行迁移SQL
        database.execSQL("ALTER TABLE chat_messages ADD COLUMN new_field TEXT")
    }
}
```

4. 添加到 AppDatabase：

```kotlin
.addMigrations(MIGRATION_5_6)
```

---

## 环境变量配置（推荐）

### 使用 local.properties

为了安全，推荐将 API Key 存储在 `local.properties` 中：

1. 在项目根目录创建/编辑 `local.properties`：
   ```properties
   api.key=your-real-api-key-here
   ```

2. 修改 `app/build.gradle.kts`：
   ```kotlin
   android {
       defaultConfig {
           val properties = Properties()
           properties.load(project.rootProject.file("local.properties").inputStream())
           buildConfigField("String", "API_KEY", "\"${properties.getProperty("api.key")}\"")
       }
   }
   ```

3. 在代码中使用：
   ```kotlin
   const val API_KEY = BuildConfig.API_KEY
   ```

4. 确保 `local.properties` 在 `.gitignore` 中（已配置）

---

## 常见问题

### Q: API 请求失败 403

A: 检查 API Key 是否正确，是否过期

### Q: 图片生成返回 500

A: 服务器偶尔不稳定，代码已实现自动降级

### Q: 摘要不生效

A: 检查消息数是否达到 SUMMARY_INTERVAL（默认10轮）

### Q: 内存占用过高

A: 检查图片缓存大小，调整 `cacheSize = maxMemory / 8`

---

## 性能基准

确保你的配置达到以下性能标准：

| 指标 | 目标 | 调优方向 |
|------|------|----------|
| 启动时间 | < 2秒 | 减少初始化操作 |
| 首屏渲染 | < 1秒 | 优化布局层级 |
| 图片首次渲染 | < 0.5秒 | 调整压缩参数 |
| 图片缓存命中 | < 10ms | 增加缓存大小 |
| 内存占用 | < 100MB | 调整缓存策略 |
| Token节省率 | > 80% | 优化摘要参数 |

---

## 更多帮助

- 查看 [开发报告](../开发报告-AI辅助开发实践与人工价值体现.md)
- 提交 Issue：[GitHub Issues](https://github.com/yourusername/Jetchat/issues)
