package com.example.compose.jetchat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.window.Dialog
import android.widget.Toast
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.compose.jetchat.data.database.AppDatabase
import java.io.InputStream

/**
 * 对话详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getInstance(context) }
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(
            context.applicationContext as android.app.Application,
            sessionId,
            database.chatDao(),
            database.sessionSummaryDao()
        )
    )
    val messages by viewModel.messages.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current  // 用于控制键盘
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    
    // 文档相关状态
    var selectedDocumentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentName by remember { mutableStateOf<String?>(null) }
    var selectedDocumentContent by remember { mutableStateOf<String?>(null) }
    
    // 语音相关状态（使用 ViewModel 的状态）
    val isRecording by viewModel.isVoiceRecording.collectAsState()
    val isRecognizing by viewModel.isVoiceRecognizing.collectAsState()
    val voiceTranscription by viewModel.voiceTranscription.collectAsState()
    var showMicrophonePermissionDialog by remember { mutableStateOf(false) }
    
    // 当语音转录完成时，自动填充到输入框
    LaunchedEffect(voiceTranscription) {
        if (voiceTranscription.isNotEmpty()) {
            inputText = voiceTranscription
        }
    }
    
    // Snackbar 状态
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    
    // 显示 Snackbar 消息
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(
                message = snackbarMessage,
                duration = if (snackbarMessage.contains("解决方案")) {
                    SnackbarDuration.Long  // 错误提示显示更长时间
                } else {
                    SnackbarDuration.Short
                }
            )
            viewModel.clearSnackbarMessage()
        }
    }
    
    // LazyColumn 状态，用于控制滚动
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            selectedImageUri = it
            // 将图片转换为 base64
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(it)
                inputStream?.use { stream ->
                    val bytes = stream.readBytes()
                    selectedImageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    android.util.Log.d("ChatScreen", "图片已转换为 base64，大小: ${bytes.size} bytes")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "图片转换失败: ${e.message}", e)
            }
        }
    }
    
    // 文档选择器
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedDocumentUri = it
            // 获取文件名
            try {
                val cursor = context.contentResolver.query(it, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            selectedDocumentName = c.getString(nameIndex)
                        }
                    }
                }
                
                // 读取文档内容
                val inputStream: InputStream? = context.contentResolver.openInputStream(it)
                inputStream?.use { stream ->
                    val bytes = stream.readBytes()
                    // 对于文本文件，直接读取内容
                    // 对于其他文件，使用 base64 编码
                    val fileName = selectedDocumentName ?: ""
                    if (fileName.endsWith(".txt", ignoreCase = true)) {
                        selectedDocumentContent = String(bytes)
                    } else {
                        selectedDocumentContent = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                    android.util.Log.d("ChatScreen", "文档已读取: $fileName, 大小: ${bytes.size} bytes")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "文档读取失败: ${e.message}", e)
                selectedDocumentName = null
                selectedDocumentContent = null
            }
        }
    }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            imagePickerLauncher.launch("image/*")
        } else {
            showPermissionDialog = true
        }
    }
    
    // 麦克风权限请求
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 开始录音
            viewModel.startVoiceRecording()
        } else {
            showMicrophonePermissionDialog = true
        }
    }

    // 权限提示对话框
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("需要存储权限") },
            text = { Text("需要访问图片需要存储权限，请在设置中授予权限。") },
            confirmButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("确定")
                }
            }
        )
    }
    
    // 麦克风权限提示对话框
    if (showMicrophonePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMicrophonePermissionDialog = false },
            title = { Text("需要麦克风权限") },
            text = { Text("语音对话功能需要麦克风权限，请在设置中授予权限。") },
            confirmButton = {
                TextButton(onClick = { showMicrophonePermissionDialog = false }) {
                    Text("确定")
                }
            }
        )
    }
    
    // 获取最后一条消息的内容（用于监听打字机效果）
    val lastMessageContent = messages.lastOrNull()?.content ?: ""
    
    // 监听最后一条消息内容变化（打字机效果），自动滚动
    LaunchedEffect(lastMessageContent) {
        if (messages.isNotEmpty()) {
            // 延迟一小段时间，确保 UI 已更新
            kotlinx.coroutines.delay(50)
            coroutineScope.launch {
                // 平滑滚动到最后一条消息
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }
    
    // 监听键盘状态，键盘弹出或消失时自动调整滚动
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible, lastMessageContent) {
        if (imeVisible && messages.isNotEmpty()) {
            // 延迟一小段时间，确保键盘动画完成
            kotlinx.coroutines.delay(100)
            coroutineScope.launch {
                // 键盘弹出时，立即滚动到底部
                listState.scrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("即梦 AI 对话") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { 
            SnackbarHost(hostState = snackbarHostState) 
        },
        contentWindowInsets = ScaffoldDefaults
            .contentWindowInsets
            .exclude(WindowInsets.ime)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 消息列表（历史在上，最新在下）
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // 点击消息列表，隐藏键盘
                                focusManager.clearFocus()
                            }
                        )
                    },
                reverseLayout = false,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(
                        message = message,
                        onRetry = {
                            viewModel.retryMessage(message)
                        },
                        onToggleVoiceText = { messageId ->
                            viewModel.toggleVoiceText(messageId)
                        }
                    )
                }
            }

            // 输入框区域
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    // 附件预览区域（类似 Claude 的卡片样式）
                    if (selectedImageUri != null || selectedDocumentName != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 图片预览
                            if (selectedImageUri != null) {
                                Box(
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    AsyncImage(
                                        model = selectedImageUri,
                                        contentDescription = "选中的图片",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    
                                    // 关闭按钮
                                    IconButton(
                                        onClick = {
                                            selectedImageUri = null
                                            selectedImageBase64 = null
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(24.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                                RoundedCornerShape(12.dp)
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "取消选择",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                            
                            // 文档预览卡片
                            if (selectedDocumentName != null) {
                                Box(
                                    modifier = Modifier
                                        .height(120.dp)
                                        .widthIn(min = 200.dp, max = 300.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        // 文件图标
                                        Icon(
                                            imageVector = Icons.Default.AttachFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        // 文件名
                                        Text(
                                            text = selectedDocumentName ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        
                                        // 文件类型
                                        val fileExtension = selectedDocumentName?.substringAfterLast(".", "")?.uppercase()
                                        if (!fileExtension.isNullOrEmpty()) {
                                            Text(
                                                text = fileExtension,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    
                                    // 关闭按钮
                                    IconButton(
                                        onClick = {
                                            selectedDocumentUri = null
                                            selectedDocumentName = null
                                            selectedDocumentContent = null
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(24.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                                RoundedCornerShape(12.dp)
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "取消选择",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // 语音模式切换按钮
                    val voiceMode by viewModel.voiceMode.collectAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        FilterChip(
                            selected = voiceMode == com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME,
                            onClick = { viewModel.toggleVoiceMode() },
                            label = {
                                Text(
                                    text = when (voiceMode) {
                                        com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> 
                                            "🎤 简单模式"
                                        com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> 
                                            "🔊 实时对话"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (voiceMode) {
                                        com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> 
                                            Icons.Default.Mic
                                        com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> 
                                            Icons.Default.Image  // 用作音频波形的占位符
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Text(
                            text = when (voiceMode) {
                                com.example.compose.jetchat.config.AppConfig.VoiceMode.SIMPLE -> 
                                    "语音识别模式"
                                com.example.compose.jetchat.config.AppConfig.VoiceMode.REALTIME -> 
                                    "端到端语音对话"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // 文档上传按钮（回形针图标）
                        IconButton(
                            onClick = {
                                // 检查权限
                                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.READ_MEDIA_IMAGES
                                } else {
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                
                                when (PackageManager.PERMISSION_GRANTED) {
                                    ContextCompat.checkSelfPermission(context, permission) -> {
                                        // 有权限，直接打开文档选择器
                                        documentPickerLauncher.launch("*/*")
                                    }
                                    else -> {
                                        // 请求权限
                                        permissionLauncher.launch(permission)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "上传文档",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        // 图片选择按钮
                        IconButton(
                            onClick = {
                                // 检查权限
                                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.READ_MEDIA_IMAGES
                                } else {
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                
                                when (PackageManager.PERMISSION_GRANTED) {
                                    ContextCompat.checkSelfPermission(context, permission) -> {
                                        // 有权限，直接打开图片选择器
                                        imagePickerLauncher.launch("image/*")
                                    }
                                    else -> {
                                        // 请求权限
                                        permissionLauncher.launch(permission)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = "选择图片",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    
                        // 输入框
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { 
                                Text(
                                    when {
                                        isRecording -> "🎤 正在录音，说出你的问题..."
                                        isRecognizing -> "🔄 正在识别语音，请稍候..."
                                        selectedDocumentName != null -> "已选择文档: $selectedDocumentName"
                                        selectedImageBase64 != null -> "已选择图片，输入文字..."
                                        else -> "输入消息..."
                                    }
                                ) 
                            },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = when {
                                    isRecording -> MaterialTheme.colorScheme.error
                                    isRecognizing -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            readOnly = isRecording || isRecognizing  // 录音或识别时禁止输入
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // 麦克风按钮 / 发送按钮
                        if (inputText.isBlank() && selectedImageBase64 == null && selectedDocumentContent == null) {
                            // 显示麦克风按钮或识别中的加载图标
                            FilledIconButton(
                                onClick = {
                                    if (isRecording) {
                                        // 停止录音
                                        viewModel.stopVoiceRecording()
                                    } else if (!isRecognizing) {
                                        // 检查麦克风权限
                                        when (PackageManager.PERMISSION_GRANTED) {
                                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
                                                // 有权限，开始录音
                                                viewModel.startVoiceRecording()
                                            }
                                            else -> {
                                                // 请求麦克风权限
                                                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                },
                                enabled = !isRecognizing,  // 识别中禁用按钮
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = when {
                                        isRecording -> MaterialTheme.colorScheme.error
                                        isRecognizing -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.secondary
                                    }
                                )
                            ) {
                                if (isRecognizing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onTertiary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = if (isRecording) "停止录音" else "语音对话",
                                        tint = MaterialTheme.colorScheme.onSecondary
                                    )
                                }
                            }
                        } else {
                            // 显示发送按钮
                            FilledIconButton(
                                onClick = {
                                    if (inputText.isNotBlank() || selectedImageBase64 != null || selectedDocumentContent != null) {
                                        // 发送消息（包含文档或图片）
                                        if (selectedDocumentContent != null) {
                                            viewModel.sendMessageWithDocument(inputText, selectedDocumentName, selectedDocumentContent)
                                            selectedDocumentUri = null
                                            selectedDocumentName = null
                                            selectedDocumentContent = null
                                        } else {
                                            viewModel.sendMessage(inputText, selectedImageBase64)
                                            selectedImageUri = null
                                            selectedImageBase64 = null
                                        }
                                        inputText = ""
                                    }
                                },
                                enabled = inputText.isNotBlank() || selectedImageBase64 != null || selectedDocumentContent != null,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "发送",
                                    tint = if (inputText.isNotBlank() || selectedImageBase64 != null || selectedDocumentContent != null)
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 语音气泡组件（类似微信语音消息）
 */
@Composable
fun VoiceMessageBubble(
    message: ChatMessage,
    onToggleText: (Long) -> Unit
) {
    val audioFilePath = message.audioFilePath ?: return
    val audioDuration = message.audioDuration ?: return
    val isUser = message.role == MessageRole.USER
    
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    val context = LocalContext.current
    
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
    
    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 语音气泡
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) Color(0xFF2F2F2F) else Color(0xFFF5F5F5),
            modifier = Modifier
                .widthIn(min = 120.dp, max = 200.dp)
                .clickable {
                    if (isPlaying) {
                        mediaPlayer?.stop()
                        mediaPlayer?.release()
                        mediaPlayer = null
                        isPlaying = false
                    } else {
                        try {
                            mediaPlayer = android.media.MediaPlayer().apply {
                                setDataSource(audioFilePath)
                                prepare()
                                setOnCompletionListener {
                                    isPlaying = false
                                }
                                start()
                            }
                            isPlaying = true
                        } catch (e: Exception) {
                            android.util.Log.e("VoiceMessage", "播放失败: ${e.message}")
                            Toast.makeText(context, "播放失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = if (isUser) Color.White else Color(0xFF2F2F2F),
                    modifier = Modifier.size(24.dp)
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(20) { index ->
                        val animatedHeight by animateDpAsState(
                            targetValue = if (isPlaying && index % 3 == (System.currentTimeMillis() / 100 % 3).toInt()) 
                                12.dp else 4.dp,
                            animationSpec = tween(300)
                        )
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(animatedHeight)
                                .background(
                                    color = if (isUser) Color.White.copy(alpha = 0.7f) 
                                           else Color(0xFF2F2F2F).copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
                
                Text(
                    text = "${audioDuration}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) Color.White else Color(0xFF2F2F2F)
                )
            }
        }
        
        // "转文字"按钮（左下角）
        TextButton(
            onClick = { onToggleText(message.id) },
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                imageVector = if (message.isTextExpanded) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (message.isTextExpanded) "收起" else "转文字",
                style = MaterialTheme.typography.labelSmall
            )
        }
        
        // 展开的文字内容（打字机效果）
        if (message.isTextExpanded && message.content.isNotBlank()) {
            var displayedText by remember { mutableStateOf("") }
            
            LaunchedEffect(message.content) {
                displayedText = ""
                message.content.forEachIndexed { index, _ ->
                    delay(30) // 打字机速度
                    displayedText = message.content.substring(0, index + 1)
                }
            }
            
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isUser) Color(0xFF2F2F2F) else Color(0xFFF5F5F5),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = displayedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) Color.White else Color(0xFF2F2F2F),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onRetry: () -> Unit,
    onToggleVoiceText: (Long) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val context = LocalContext.current
    
    // 图片预览状态
    var showImagePreview by remember { mutableStateOf(false) }
    
    // 文本悬停状态
    var isTextHovered by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 语音消息气泡（如果有语音）
        message.audioFilePath?.let { audioPath ->
            message.audioDuration?.let { duration ->
                VoiceMessageBubble(
                    message = message,
                    onToggleText = onToggleVoiceText
                )
                // 如果有语音，且文字没有展开，就不显示下面的文字气泡
                if (message.isTextExpanded) {
                    return@Column
                }
            }
        }
        // 图片气泡（如果有图片）- 高性能缓存版本
        message.imageBase64?.let { base64 ->
            // 使用 ImageCache 异步解码并缓存，避免重复解码
            val bitmapState: State<android.graphics.Bitmap?> = produceState(initialValue = null, base64) {
                value = ImageCache.decodeBitmap(base64)
            }
            
            bitmapState.value?.let { bitmap ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(120.dp)
                        .clickable { showImagePreview = true }  // 点击预览
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "消息图片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                // 图片预览对话框
                if (showImagePreview) {
                    ImagePreviewDialog(
                        bitmap = bitmap,
                        imageBase64 = base64,
                        onDismiss = { showImagePreview = false }
                    )
                }
            }
        }
        
        // 文档卡片（如果有文档）- 方形卡片，类似图片
        message.documentName?.let { docName ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(120.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 文档图标
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "文档",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 文件名
                    Text(
                        text = docName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // 文件扩展名
                    val fileExtension = docName.substringAfterLast(".", "").uppercase()
                    if (fileExtension.isNotEmpty()) {
                        Text(
                            text = fileExtension,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        
        // 文字气泡（如果有文字或者是加载/错误状态）
        if (message.content.isNotBlank() || message.status == MessageStatus.LOADING || message.status == MessageStatus.ERROR) {
            Column(
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        isUser -> Color(0xFF2F2F2F)  // 深灰色背景（Claude 用户消息）
                        message.status == MessageStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                        else -> Color(0xFFF5F5F5)  // 浅灰色背景（Claude AI 消息）
                    },
                    modifier = Modifier.widthIn(max = 280.dp)  // 最大宽度，但自适应内容
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                    when (message.status) {
                        MessageStatus.LOADING -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "AI 正在输入...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        MessageStatus.ERROR -> {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "❗",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(
                                    onClick = onRetry,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "重试",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isUser) 
                                    Color(0xFFFFFFFF)  // 白色文字（用户消息）
                                else 
                                    Color(0xFF2F2F2F)  // 深色文字（AI 消息）
                            )
                        }
                    }
                }
            }
                
                // 复制按钮（在气泡下方，小巧设计）
                if (message.status == MessageStatus.SENT && message.content.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("消息内容", message.content)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制",
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF999999)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 图片预览对话框
 */
@Composable
fun ImagePreviewDialog(
    bitmap: android.graphics.Bitmap,
    imageBase64: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showSaveDialog by remember { mutableStateOf(false) }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable { onDismiss() }  // 点击背景关闭
        ) {
            // 图片
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "预览图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .clickable(enabled = false) { },  // 阻止点击事件传递
                contentScale = ContentScale.Fit
            )
            
            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }
            
            // 长按保存提示和按钮
            FloatingActionButton(
                onClick = {
                    // 保存图片
                    saveImageToGallery(context, bitmap, imageBase64)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = Color.White
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "保存图片",
                    tint = Color.Black
                )
            }
        }
    }
    
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存成功") },
            text = { Text("图片已保存到相册") },
            confirmButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("确定")
                }
            }
        )
    }
}

/**
 * 保存图片到相册
 */
private fun saveImageToGallery(
    context: android.content.Context,
    bitmap: android.graphics.Bitmap,
    imageBase64: String
) {
    try {
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "jetchat_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
            }
        }
        
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream)
            }
            android.widget.Toast.makeText(context, "图片已保存到相册", android.widget.Toast.LENGTH_SHORT).show()
        } ?: run {
            android.widget.Toast.makeText(context, "保存失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
