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
        startDestination = "chat_list"
    ) {
        // 对话列表页面
        composable("chat_list") {
            val context = LocalContext.current
            val database = remember { AppDatabase.getInstance(context) }
            
            ChatListScreen(
                onChatClick = { sessionId ->
                    navController.navigate("chat/$sessionId")
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
                }
            )
        }

        // 对话详情页面
        composable(
            route = "chat/{sessionId}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ChatScreen(
                sessionId = sessionId,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
