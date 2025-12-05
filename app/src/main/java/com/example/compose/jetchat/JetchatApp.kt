package com.example.compose.jetchat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.compose.jetchat.data.database.AppDatabase
import com.example.compose.jetchat.data.database.ChatMessageEntity
import com.example.compose.jetchat.ui.chat.ChatScreen
import com.example.compose.jetchat.ui.chatlist.ChatListScreen
import com.example.compose.jetchat.ui.login.LoginScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主应用导航组件
 */
@Composable
fun JetchatApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "login"
    ) {
        // 登录页面
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("chat_list") {
                        // 清空登录页面，防止返回
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }
        // 对话列表页面
        composable("chat_list") {
            val context = LocalContext.current
            val database = remember { AppDatabase.getInstance(context) }
            
            ChatListScreen(
                onChatClick = { sessionIdWithParams ->
                    // sessionIdWithParams 可能包含 ?isRealtime=true 参数
                    navController.navigate("chat/$sessionIdWithParams")
                },
                onNewChatClick = {
                    val newSessionId = System.currentTimeMillis().toString()
                    // 立即在数据库中创建一个占位消息，标记这是一个新会话
                    CoroutineScope(Dispatchers.IO).launch {
                        database.chatDao().insertMessage(
                            ChatMessageEntity(
                                sessionId = newSessionId,
                                role = "system",
                                content = "会话已创建",
                                timestamp = System.currentTimeMillis(),
                                sessionTitle = "新对话"
                            )
                        )
                    }
                    navController.navigate("chat/$newSessionId")
                },
                onNewRealtimeChatClick = {
                    val newSessionId = System.currentTimeMillis().toString()
                    // 创建实时语音对话会话
                    CoroutineScope(Dispatchers.IO).launch {
                        database.chatDao().insertMessage(
                            ChatMessageEntity(
                                sessionId = newSessionId,
                                role = "system",
                                content = "实时语音对话会话",
                                timestamp = System.currentTimeMillis(),
                                sessionTitle = "🎙️ 实时语音对话"
                            )
                        )
                    }
                    navController.navigate("chat/$newSessionId?isRealtime=true")
                }
            )
        }

        // 对话详情页面
        composable(
            route = "chat/{sessionId}?isRealtime={isRealtime}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("isRealtime") { 
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val isRealtime = backStackEntry.arguments?.getBoolean("isRealtime") ?: false
            ChatScreen(
                sessionId = sessionId,
                isRealtimeMode = isRealtime,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
