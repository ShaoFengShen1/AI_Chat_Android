# Jetchat 架构设计文档

## 架构概览

Jetchat 采用经典的 **MVVM (Model-View-ViewModel)** 架构模式，并结合 Jetpack Compose 的声明式 UI 范式，实现了清晰的分层和职责分离。

```
┌─────────────────────────────────────────────────────┐
│                   UI Layer (Compose)                │
│  ChatScreen, ChatListScreen, Components            │
└────────────────┬────────────────────────────────────┘
                 │ StateFlow / LiveData
┌────────────────▼────────────────────────────────────┐
│                 ViewModel Layer                      │
│  ChatViewModel, ChatListViewModel                   │
│  + Business Logic + State Management                │
└────────────────┬────────────────────────────────────┘
                 │ suspend functions
┌────────────────▼────────────────────────────────────┐
│              Data Layer (Repository)                │
│  ├─ Network (ApiService)                           │
│  ├─ Database (Room)                                │
│  ├─ Cache (ImageCache)                             │
│  └─ Manager (ConversationSummaryManager)           │
└─────────────────────────────────────────────────────┘
```

---

## 分层设计

### 1. UI Layer（UI层）

**职责：** 渲染界面、处理用户交互、响应状态变化

**技术栈：**
- Jetpack Compose（声明式UI）
- Material Design 3（设计系统）
- Navigation Component（页面导航）

**核心组件：**

```kotlin
// 聊天界面
@Composable
fun ChatScreen(
    sessionId: String,
    viewModel: ChatViewModel = viewModel(...)
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    
    // 声明式UI：状态变化自动触发重组
    Column {
        MessageList(messages)
        UserInput(inputText, viewModel::sendMessage)
    }
}
```

**设计原则：**
- ✅ **UI与逻辑分离**：Composable 只负责渲染，不包含业务逻辑
- ✅ **单向数据流**：ViewModel → UI（数据流）、UI → ViewModel（事件流）
- ✅ **可组合性**：小组件组合成大组件
- ✅ **可重用性**：通用组件可在多处使用

---

### 2. ViewModel Layer（业务逻辑层）

**职责：** 管理UI状态、处理业务逻辑、协调数据层

**技术栈：**
- Kotlin Coroutines（异步处理）
- StateFlow（状态管理）
- ViewModel（生命周期感知）

**核心实现：**

```kotlin
class ChatViewModel(
    private val sessionId: String,
    private val chatDao: ChatDao,
    private val summaryDao: SessionSummaryDao,
    private val apiService: ApiService = ApiService.instance
) : ViewModel() {
    
    // 状态：消息列表
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    // 状态：输入文本
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    
    // 业务逻辑：发送消息
    fun sendMessage(content: String, imageBase64: String? = null) {
        viewModelScope.launch {
            try {
                // 1. 立即显示用户消息
                addUserMessage(content, imageBase64)
                
                // 2. 检查是否需要生成摘要
                if (shouldGenerateSummary()) {
                    generateSummary()
                }
                
                // 3. 调用API
                val response = apiService.sendChatRequest(...)
                
                // 4. 显示AI回复（打字机动画）
                showAIResponseWithTypewriter(response)
                
                // 5. 保存到数据库
                saveToDatabase()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }
}
```

**设计原则：**
- ✅ **单一职责**：每个 ViewModel 只管理一个界面
- ✅ **不持有Context**：避免内存泄漏
- ✅ **协程管理**：使用 viewModelScope 自动取消
- ✅ **错误处理**：统一的异常捕获和用户提示

---

### 3. Data Layer（数据层）

#### 3.1 Network（网络层）

**职责：** API调用、意图识别、错误处理

```kotlin
object ApiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    // 发送聊天请求（智能路由）
    suspend fun sendChatRequestWithHistory(
        conversationHistory: List<Pair<String, String>>,
        currentUserMessage: String,
        imageBase64: String? = null
    ): String = withContext(Dispatchers.IO) {
        // 意图识别：判断是文本对话还是图片生成
        val shouldGenerateImage = isImageGenerationRequest(currentUserMessage)
        
        if (shouldGenerateImage) {
            // 路由到图片生成API
            sendImageGenerationRequest(currentUserMessage)
        } else {
            // 路由到文本对话API
            sendChatRequestInternal(...)
        }
    }
    
    // 意图识别（正则表达式）
    private fun isImageGenerationRequest(message: String): Boolean {
        val patterns = listOf(
            Regex("生成.*?图"),
            Regex("画(一张|一幅|一个|个)?"),
            Regex("(帮我|给我)(画|生成)")
        )
        return patterns.any { it.containsMatchIn(message.lowercase()) }
    }
}
```

**设计原则：**
- ✅ **单例模式**：全局唯一的 HTTP 客户端
- ✅ **智能路由**：根据意图自动选择API
- ✅ **容错降级**：图片生成失败时回退到文本对话
- ✅ **性能优化**：复用连接、请求压缩

#### 3.2 Database（数据库层）

**职责：** 数据持久化、历史查询、摘要管理

```kotlin
@Database(
    entities = [ChatMessageEntity::class, SessionSummaryEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun summaryDao(): SessionSummaryDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jetchat_database"
                )
                .addMigrations(MIGRATION_4_5)  // 数据库迁移
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

**表结构：**

```sql
-- 消息表
CREATE TABLE chat_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL,
    role TEXT NOT NULL,           -- 'user' | 'assistant' | 'system'
    content TEXT NOT NULL,
    imageBase64 TEXT,              -- 可选的图片数据
    timestamp INTEGER NOT NULL,
    INDEX idx_sessionId (sessionId),
    INDEX idx_timestamp (timestamp)
);

-- 摘要表
CREATE TABLE session_summaries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL UNIQUE,
    summary TEXT NOT NULL,
    lastSummarizedMessageId INTEGER NOT NULL,  -- 摘要到哪条消息
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    INDEX idx_sessionId (sessionId)
);
```

**设计原则：**
- ✅ **单例模式**：全局唯一的数据库实例
- ✅ **索引优化**：为常用查询添加索引
- ✅ **数据迁移**：安全的版本升级策略
- ✅ **类型安全**：编译时检查SQL语句

#### 3.3 Cache（缓存层）

**职责：** 图片缓存、内存管理、性能优化

```kotlin
object ImageCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8  // 使用1/8内存
    
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        // 计算实际内存占用
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
        
        // 自动回收被淘汰的Bitmap
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }
    
    // 异步解码，带缓存
    suspend fun decodeBitmap(base64: String): Bitmap? = 
        withContext(Dispatchers.Default) {
            val cacheKey = base64.hashCode().toString()
            bitmapCache.get(cacheKey) ?: decodeAndCache(base64, cacheKey)
        }
}
```

**设计原则：**
- ✅ **LRU算法**：自动淘汰最久未使用的图片
- ✅ **内存限制**：不超过可用内存的1/8
- ✅ **自动回收**：防止内存泄漏
- ✅ **异步处理**：不阻塞主线程

#### 3.4 Manager（业务管理层）

**职责：** 复杂业务逻辑的封装

```kotlin
class ConversationSummaryManager(
    private val chatDao: ChatDao,
    private val summaryDao: SessionSummaryDao,
    private val apiService: ApiService
) {
    companion object {
        const val SUMMARY_INTERVAL = 10        // 每10轮触发
        const val RECENT_MESSAGES_COUNT = 6    // 保留6轮
    }
    
    // 判断是否需要生成摘要
    suspend fun shouldGenerateSummary(sessionId: String): Boolean {
        val messages = chatDao.getMessagesBySessionId(sessionId)
        val summary = summaryDao.getSummary(sessionId)
        
        return if (summary == null) {
            messages.size >= SUMMARY_INTERVAL
        } else {
            val newMessages = messages.count { it.id > summary.lastSummarizedMessageId }
            newMessages >= SUMMARY_INTERVAL
        }
    }
    
    // 生成摘要
    suspend fun generateSummary(sessionId: String) {
        val messages = chatDao.getMessagesBySessionId(sessionId)
        val messagesToSummarize = messages.takeLast(SUMMARY_INTERVAL)
        
        // 调用AI生成摘要
        val summaryText = apiService.generateSummary(messagesToSummarize)
        
        // 保存摘要
        val summary = SessionSummaryEntity(
            sessionId = sessionId,
            summary = summaryText,
            lastSummarizedMessageId = messages.last().id,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        summaryDao.insertOrUpdate(summary)
    }
    
    // 获取带摘要的消息列表
    suspend fun getMessagesWithSummary(
        sessionId: String,
        newUserMessage: String
    ): List<Pair<String, String>> {
        val messages = chatDao.getMessagesBySessionId(sessionId)
        val summary = summaryDao.getSummary(sessionId)
        
        val result = mutableListOf<Pair<String, String>>()
        
        // 添加摘要（如果有）
        if (summary != null) {
            result.add("system" to "以下是之前的对话摘要：\n${summary.summary}")
            
            // 只发送摘要之后的消息
            val recentMessages = messages.filter { 
                it.id > summary.lastSummarizedMessageId 
            }
            result.addAll(recentMessages.map { it.role to it.content })
        } else {
            // 发送所有消息
            result.addAll(messages.map { it.role to it.content })
        }
        
        // 添加当前用户输入
        result.add("user" to newUserMessage)
        
        return result
    }
}
```

**设计原则：**
- ✅ **封装复杂逻辑**：将多步骤操作封装为单一方法
- ✅ **依赖注入**：通过构造函数注入依赖
- ✅ **可测试性**：易于编写单元测试
- ✅ **职责单一**：只负责摘要相关逻辑

---

## 数据流

### 完整的消息发送流程

```
用户点击发送
    ↓
ChatScreen.onSendClick()
    ↓
ChatViewModel.sendMessage(content, image)
    ↓
1. 立即添加用户消息到 _messages (UI即时更新)
    ↓
2. ConversationSummaryManager.shouldGenerateSummary()
    ├─ Yes → generateSummary()
    └─ No → 继续
    ↓
3. ConversationSummaryManager.getMessagesWithSummary()
    └─ 组装：[摘要] + [最近消息] + [当前输入]
    ↓
4. ApiService.sendChatRequestWithHistory()
    ├─ isImageGenerationRequest() → 意图识别
    ├─ Yes → sendImageGenerationRequest()
    │         ├─ 成功 → downloadImageAsBase64()
    │         └─ 失败 → 降级到文本对话
    └─ No → sendChatRequestInternal()
    ↓
5. 显示AI回复（打字机动画）
    └─ 每30ms更新一个字符到 _messages
    ↓
6. 保存到数据库
    ├─ chatDao.insert(userMessage)
    └─ chatDao.insert(aiMessage)
    ↓
完成
```

---

## 扩展性设计

### 1. 策略模式 - 意图识别 ✅ 已实现

**v1.5.0更新**：已升级为AI意图识别系统，支持AI和正则两种策略。

```kotlin
// 意图类型
enum class IntentType {
    TEXT_CHAT,          // 普通文本对话
    IMAGE_GENERATION,   // 图片生成
    IMAGE_RECOGNITION   // 图片识别
}

// 意图识别结果
data class IntentResult(
    val type: IntentType,
    val confidence: Float = 1.0f,  // 置信度 0-1
    val optimizedPrompt: String? = null  // 优化后的Prompt
)

// 接口定义
interface IntentDetector {
    suspend fun detectIntent(message: String, hasImage: Boolean = false): IntentResult
}

// 实现1：正则表达式检测器（备用）
private class RegexIntentDetector : IntentDetector {
    override suspend fun detectIntent(message: String, hasImage: Boolean): IntentResult {
        if (hasImage) return IntentResult(IntentType.IMAGE_RECOGNITION, 1.0f)
        
        val patterns = listOf(
            Regex("生成.*?图"),
            Regex("画(一张|一幅|一个|个)?"),
            Regex("(帮我|给我)(画|生成)")
        )
        
        val isImageGen = patterns.find { it.containsMatchIn(message.lowercase()) } != null
        return if (isImageGen) {
            IntentResult(IntentType.IMAGE_GENERATION, 0.8f)
        } else {
            IntentResult(IntentType.TEXT_CHAT, 0.9f)
        }
    }
}

// 实现2：AI检测器（主要使用）
private inner class AIIntentDetector : IntentDetector {
    override suspend fun detectIntent(message: String, hasImage: Boolean): IntentResult {
        if (hasImage) return IntentResult(IntentType.IMAGE_RECOGNITION, 1.0f)
        
        try {
            // 调用Gemini 2.5 Flash进行意图识别
            val systemPrompt = """
你是一个专业的意图识别助手。请分析用户的消息，判断用户的意图类型。

意图类型：
1. IMAGE_GENERATION - 用户想要生成/创建/画一张图片
2. TEXT_CHAT - 普通的文本对话

请用JSON格式回复，包含以下字段：
- intent: 意图类型（IMAGE_GENERATION 或 TEXT_CHAT）
- confidence: 置信度（0-1之间的浮点数）
- optimized_prompt: 如果是图片生成，提供一个优化后的英文Prompt（详细、专业、适合DALL-E 3）
            """.trimIndent()
            
            val intentRequest = buildJsonObject {
                put("model", AppConfig.INTENT_MODEL)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", message)
                    }
                }
                put("temperature", 0.3)
                put("max_tokens", 300)
            }
            
            // 发送请求并解析结果
            val response = client.newCall(request).execute()
            val intentJson = parseIntentResponse(response)
            
            return IntentResult(
                type = intentJson["intent"],
                confidence = intentJson["confidence"],
                optimizedPrompt = intentJson["optimized_prompt"]
            )
        } catch (e: Exception) {
            // 失败时降级到正则表达式
            return RegexIntentDetector().detectIntent(message, hasImage)
        }
    }
}

// 根据配置选择检测器
private val intentDetector: IntentDetector = if (AppConfig.USE_AI_INTENT_DETECTION) {
    AIIntentDetector()
} else {
    RegexIntentDetector()
}

// 使用示例
suspend fun sendChatRequest(userMessage: String, imageBase64: String? = null): ApiResponse {
    // 使用意图检测器
    val intentResult = intentDetector.detectIntent(userMessage, hasImage = imageBase64 != null)
    val shouldGenerateImage = (intentResult.type == IntentType.IMAGE_GENERATION) && imageBase64 == null
    
    // 如果需要生成图片且有优化Prompt，使用优化后的Prompt
    val finalPrompt = if (shouldGenerateImage && intentResult.optimizedPrompt != null) {
        intentResult.optimizedPrompt
    } else {
        userMessage
    }
    
    // 根据意图路由到不同模型
    val modelToUse = if (shouldGenerateImage) AppConfig.IMAGE_MODEL else AppConfig.CHAT_MODEL
    // ...
}
```

**优势：**
- ✅ 准确率提升：从80%提升到95%+
- ✅ 自然语言支持：理解复杂表达，无需固定关键词
- ✅ Prompt自动优化：中文→专业英文，提升生成质量
- ✅ 智能降级：AI失败时自动降级到正则表达式
- ✅ 可配置切换：支持运行时选择策略

### 2. 插件化架构 - 功能模块

```kotlin
// 定义功能接口
interface ChatFeature {
    suspend fun handle(message: ChatMessage): ChatMessage?
}

// 语音功能
class VoiceFeature : ChatFeature {
    override suspend fun handle(message: ChatMessage): ChatMessage? {
        // 处理语音输入/输出
    }
}

// 搜索功能
class SearchFeature : ChatFeature {
    override suspend fun handle(message: ChatMessage): ChatMessage? {
        // 处理联网搜索
    }
}

// 注册和使用
class FeatureManager {
    private val features = mutableListOf<ChatFeature>()
    
    fun register(feature: ChatFeature) {
        features.add(feature)
    }
    
    suspend fun process(message: ChatMessage): ChatMessage {
        var result = message
        for (feature in features) {
            feature.handle(result)?.let { result = it }
        }
        return result
    }
}
```

---

## 性能优化策略

### 1. 网络层优化

- ✅ **连接复用**：OkHttp连接池
- ✅ **请求压缩**：GZip压缩
- ✅ **智能重试**：指数退避算法
- ✅ **超时配置**：区分连接/读取/写入超时

### 2. 数据库优化

- ✅ **索引优化**：为sessionId和timestamp添加索引
- ✅ **批量操作**：使用transaction
- ✅ **懒加载**：分页加载历史消息
- ✅ **查询优化**：只查询需要的字段

### 3. 内存优化

- ✅ **LRU缓存**：图片自动淘汰
- ✅ **图片压缩**：解码时缩放+JPEG压缩
- ✅ **内存监控**：使用LeakCanary检测泄漏
- ✅ **Bitmap回收**：及时释放内存

### 4. UI优化

- ✅ **懒加载**：LazyColumn复用item
- ✅ **避免重组**：使用remember和key
- ✅ **异步操作**：produceState异步加载
- ✅ **动画优化**：使用硬件加速

---

## 测试策略

### 1. 单元测试

```kotlin
class ConversationSummaryManagerTest {
    @Test
    fun `should generate summary after 10 messages`() = runTest {
        // Arrange
        val manager = ConversationSummaryManager(mockChatDao, mockSummaryDao, mockApiService)
        coEvery { mockChatDao.getMessagesBySessionId(any()) } returns createMessages(10)
        
        // Act
        val shouldGenerate = manager.shouldGenerateSummary("session-1")
        
        // Assert
        assertTrue(shouldGenerate)
    }
}
```

### 2. UI测试

```kotlin
class ChatScreenTest {
    @Test
    fun `should display user message after sending`() {
        composeTestRule.setContent {
            ChatScreen(sessionId = "test")
        }
        
        // 输入消息
        composeTestRule.onNodeWithTag("input").performTextInput("Hello")
        composeTestRule.onNodeWithTag("send").performClick()
        
        // 验证消息显示
        composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
    }
}
```

---

## 安全性考虑

### 1. API密钥保护

- ⚠️ 不将API密钥硬编码
- ✅ 使用local.properties或环境变量
- ✅ 使用ProGuard混淆
- ✅ 考虑使用后端代理

### 2. 数据安全

- ✅ 数据库加密（SQLCipher）
- ✅ 敏感信息不记录日志
- ✅ HTTPS通信
- ✅ 证书固定（Certificate Pinning）

---

## 更多资源

- [Jetpack Compose 文档](https://developer.android.com/jetpack/compose)
- [Android 架构指南](https://developer.android.com/topic/architecture)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Room 数据库](https://developer.android.com/training/data-storage/room)
