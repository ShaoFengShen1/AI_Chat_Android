# 贡献指南

感谢你对 Jetchat 项目的关注！我们欢迎各种形式的贡献。

---

## 🤝 如何贡献

### 报告Bug

如果你发现了Bug，请：

1. **检查是否已存在**：在 [Issues](https://github.com/yourusername/Jetchat/issues) 中搜索类似问题
2. **创建Issue**：如果没有找到，创建新Issue并包含：
   - 清晰的标题和描述
   - 复现步骤
   - 预期行为 vs 实际行为
   - 设备信息（Android版本、设备型号）
   - 日志输出（如果有）
   - 截图/录屏（如果适用）

**Bug Report模板：**

```markdown
**描述Bug**
简洁清晰地描述Bug

**复现步骤**
1. 打开应用
2. 点击'...'
3. 输入'...'
4. 观察到错误

**预期行为**
应该显示...

**实际行为**
实际显示...

**环境**
- 设备：小米11
- Android版本：13
- 应用版本：1.4.0

**日志**
```
粘贴相关日志
```

**截图**
如果适用，添加截图
```

### 建议新功能

如果你有新功能的想法：

1. **检查Roadmap**：查看 [未来计划](../README.md#未来计划)
2. **创建Feature Request**：详细描述功能需求
3. **讨论可行性**：等待维护者反馈

**Feature Request模板：**

```markdown
**功能描述**
清晰描述你想要的功能

**使用场景**
这个功能解决什么问题？

**建议实现方式**
如果有想法，描述如何实现

**替代方案**
考虑过哪些替代方案？

**补充信息**
其他相关信息
```

### 提交代码

我们欢迎Pull Request！请遵循以下流程：

1. **Fork项目**
   ```bash
   # 在GitHub上点击Fork按钮
   ```

2. **克隆到本地**
   ```bash
   git clone https://github.com/your-username/Jetchat.git
   cd Jetchat
   ```

3. **创建功能分支**
   ```bash
   git checkout develop
   git checkout -b feature/amazing-feature
   ```

4. **开发功能**
   - 遵循 [代码规范](#代码规范)
   - 编写测试用例
   - 更新文档

5. **提交代码**
   ```bash
   git add .
   git commit -m "feat: add amazing feature"
   ```

6. **推送到GitHub**
   ```bash
   git push origin feature/amazing-feature
   ```

7. **创建Pull Request**
   - 在GitHub上创建PR
   - 填写PR模板
   - 等待代码审查

---

## 📝 代码规范

### Kotlin风格

遵循 [Kotlin官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)：

```kotlin
// ✅ 好的命名
class ChatViewModel
fun sendMessage()
val messageList: List<ChatMessage>

// ❌ 不好的命名
class chatVM
fun send()
val list: List<ChatMessage>

// ✅ 好的格式
fun sendMessage(
    content: String,
    imageBase64: String? = null
) {
    viewModelScope.launch {
        // 逻辑
    }
}

// ❌ 不好的格式
fun sendMessage(content: String,imageBase64: String?=null){
    viewModelScope.launch{
        //逻辑
    }
}
```

### 项目约定

1. **包结构**
   ```
   com.example.compose.jetchat/
   ├── config/      # 配置
   ├── data/        # 数据层
   ├── ui/          # UI层
   └── utils/       # 工具类
   ```

2. **命名规则**
   - **Activity**: `MainActivity`
   - **Fragment**: `ChatFragment`
   - **ViewModel**: `ChatViewModel`
   - **Composable**: `ChatScreen`、`MessageBubble`
   - **Entity**: `ChatMessageEntity`
   - **Dao**: `ChatDao`
   - **常量**: `SUMMARY_INTERVAL`

3. **注释规范**
   ```kotlin
   /**
    * 发送聊天消息
    * 
    * @param content 消息内容
    * @param imageBase64 可选的图片数据（Base64编码）
    * @throws NetworkException 网络错误
    */
   fun sendMessage(content: String, imageBase64: String? = null)
   
   // 关键业务逻辑的注释
   // 每10轮对话触发摘要生成（权衡了Token消耗和上下文完整性）
   if (messages.size >= SUMMARY_INTERVAL) {
       generateSummary()
   }
   ```

4. **Composable规范**
   ```kotlin
   // ✅ 好的Composable
   @Composable
   fun MessageBubble(
       message: ChatMessage,
       modifier: Modifier = Modifier  // 总是提供modifier参数
   ) {
       // 使用remember避免不必要的重组
       val formattedTime = remember(message.timestamp) {
           formatTimestamp(message.timestamp)
       }
       
       Column(modifier = modifier) {
           Text(message.content)
           Text(formattedTime)
       }
   }
   
   // ❌ 不好的Composable
   @Composable
   fun MessageBubble(message: ChatMessage) {  // 缺少modifier
       // 没有使用remember，每次都重新计算
       val time = formatTimestamp(message.timestamp)
       
       Column {
           Text(message.content)
           Text(time)
       }
   }
   ```

---

## 🧪 测试要求

### 单元测试

为新功能编写单元测试：

```kotlin
class ConversationSummaryManagerTest {
    private lateinit var manager: ConversationSummaryManager
    private lateinit var mockChatDao: ChatDao
    private lateinit var mockSummaryDao: SessionSummaryDao
    private lateinit var mockApiService: ApiService
    
    @Before
    fun setup() {
        mockChatDao = mockk()
        mockSummaryDao = mockk()
        mockApiService = mockk()
        manager = ConversationSummaryManager(mockChatDao, mockSummaryDao, mockApiService)
    }
    
    @Test
    fun `should generate summary after 10 messages`() = runTest {
        // Arrange
        coEvery { mockChatDao.getMessagesBySessionId(any()) } returns createMessages(10)
        coEvery { mockSummaryDao.getSummary(any()) } returns null
        
        // Act
        val result = manager.shouldGenerateSummary("session-1")
        
        // Assert
        assertTrue(result)
    }
}
```

### UI测试

为UI组件编写测试：

```kotlin
class ChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun `should display message after sending`() {
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

### 运行测试

```bash
# 运行所有测试
./gradlew test

# 运行特定测试
./gradlew test --tests ConversationSummaryManagerTest

# 运行UI测试
./gradlew connectedAndroidTest
```

---

## 📦 Commit规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

### 格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Type类型

- `feat`: 新功能
- `fix`: Bug修复
- `perf`: 性能优化
- `refactor`: 重构（不改变功能）
- `style`: 代码格式（不影响逻辑）
- `test`: 测试相关
- `docs`: 文档更新
- `chore`: 构建/工具配置

### 示例

```bash
# 新功能
git commit -m "feat(chat): add voice input support"

# Bug修复
git commit -m "fix(image): resolve memory leak in ImageCache

- Add entryRemoved callback to recycle bitmaps
- Implement proper lifecycle management
- Add unit tests for cache behavior

Fixes #123"

# 性能优化
git commit -m "perf(database): add indexes to improve query performance

- Add index on sessionId column
- Add composite index on (sessionId, timestamp)
- Query time reduced from 120ms to 8ms"

# 重构
git commit -m "refactor(api): extract intent detection to separate class

- Create IntentDetector interface
- Implement RegexIntentDetector
- Prepare for AI-based intent detection upgrade"
```

---

## 🔍 代码审查

Pull Request会经过以下审查：

### 审查清单

- [ ] **功能完整**：实现了PR描述的所有功能
- [ ] **测试覆盖**：包含单元测试和UI测试
- [ ] **代码质量**：遵循编码规范，无明显坏味道
- [ ] **性能影响**：没有引入性能问题
- [ ] **兼容性**：支持Android 7.0+
- [ ] **文档更新**：更新了相关文档
- [ ] **无破坏性变更**：或已在PR中明确说明

### 审查流程

1. **自动检查**：CI/CD自动运行测试和lint
2. **代码审查**：至少1名维护者审查代码
3. **修改反馈**：根据审查意见修改代码
4. **批准合并**：审查通过后合并到develop分支

---

## 🎯 开发环境设置

### 必需工具

- Android Studio Hedgehog | 2023.1.1+
- JDK 17
- Android SDK (API 24+)
- Git

### 配置步骤

1. **安装Android Studio**
   ```bash
   # 下载地址：https://developer.android.com/studio
   ```

2. **克隆项目**
   ```bash
   git clone https://github.com/yourusername/Jetchat.git
   cd Jetchat
   ```

3. **配置API密钥**
   ```bash
   # 编辑 app/src/main/java/com/example/compose/jetchat/config/AppConfig.kt
   const val API_KEY = "your-api-key-here"
   ```

4. **同步依赖**
   ```bash
   ./gradlew build
   ```

5. **运行应用**
   ```bash
   ./gradlew installDebug
   ```

### 推荐插件

- **Kotlin Plugin**：Kotlin语言支持
- **Compose UI**：Compose预览和工具
- **Database Inspector**：Room数据库调试
- **Logcat**：日志查看

---

## 📚 学习资源

### 官方文档

- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Android架构组件](https://developer.android.com/topic/architecture)
- [Kotlin协程](https://kotlinlang.org/docs/coroutines-overview.html)
- [Room数据库](https://developer.android.com/training/data-storage/room)

### 项目文档

- [架构设计](ARCHITECTURE.md)
- [配置指南](CONFIG.md)
- [开发报告](../开发报告-AI辅助开发实践与人工价值体现.md)

---

## ❓ 常见问题

### Q: 如何添加新的AI模型？

A: 在 `AppConfig.kt` 中添加模型配置，在 `ApiService.kt` 中添加模型路由逻辑。

### Q: 如何优化图片渲染性能？

A: 调整 `MAX_IMAGE_SIZE` 和 `IMAGE_QUALITY` 参数，或增加LRU缓存大小。

### Q: 如何添加数据库迁移？

A: 参考 [配置指南-数据库配置](CONFIG.md#数据库配置)。

### Q: 如何调试网络请求？

A: 设置 `ENABLE_LOGGING = true`，查看Logcat中的 `ApiService` 标签日志。

---

## 📧 联系方式

- **提Issue**：[GitHub Issues](https://github.com/yourusername/Jetchat/issues)
- **讨论**：[GitHub Discussions](https://github.com/yourusername/Jetchat/discussions)
- **邮件**：your-email@example.com

---

## 🙏 致谢

感谢所有为Jetchat做出贡献的开发者！

---

<div align="center">
  <p>Happy Coding! 🚀</p>
</div>
