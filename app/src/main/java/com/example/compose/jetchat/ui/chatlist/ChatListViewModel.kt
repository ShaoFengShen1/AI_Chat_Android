package com.example.compose.jetchat.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.compose.jetchat.data.database.ChatDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对话列表 ViewModel
 */
class ChatListViewModel(
    private val chatDao: ChatDao
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadSessions()
    }

    /**
     * 加载所有会话
     */
    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val latestMessages = withContext(Dispatchers.IO) {
                    chatDao.getLatestMessagePerSession()
                }
                // 先按置顶状态分组，再按时间排序
                // 置顶的在最上面（按时间降序），未置顶的在下面（按时间升序）
                val sessions = latestMessages.map { it.toSession() }
                val pinned = sessions.filter { it.isPinned }.sortedByDescending { it.timestamp }
                val unpinned = sessions.filter { !it.isPinned }.sortedBy { it.timestamp }
                _sessions.value = pinned + unpinned
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 重命名会话
     */
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    chatDao.updateSessionTitle(sessionId, newTitle)
                }
                loadSessions()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 置顶/取消置顶会话
     */
    fun togglePinSession(sessionId: String, isPinned: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    chatDao.togglePinSession(sessionId, !isPinned)
                }
                loadSessions()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    chatDao.deleteMessagesBySessionId(sessionId)
                }
                loadSessions()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/**
 * ViewModel 工厂
 */
class ChatListViewModelFactory(
    private val chatDao: ChatDao
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatListViewModel::class.java)) {
            return ChatListViewModel(chatDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
