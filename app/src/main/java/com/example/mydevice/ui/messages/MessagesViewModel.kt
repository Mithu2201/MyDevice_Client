package com.example.mydevice.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.local.database.entity.IncomingMessageEntity
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.repository.MessageRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Drives the Messages screen.
 *
 * Messages arrive via SignalR → saved to Room → observed here as Flow.
 * The UI shows a list sorted by receivedAt descending,
 * with unread indicators and mark-as-read actions.
 */
data class MessagesUiState(
    val messages: List<IncomingMessageEntity> = emptyList(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = true
)

class MessagesViewModel(
    private val messageRepo: MessageRepository,
    private val deviceRepo: DeviceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()

    init {
        observeMessages()
        refreshFromServer()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            messageRepo.getAllMessages().collect { messages ->
                _uiState.value = _uiState.value.copy(
                    messages = messages,
                    isLoading = false
                )
            }
        }
        viewModelScope.launch {
            messageRepo.getUnreadCount().collect { count ->
                _uiState.value = _uiState.value.copy(unreadCount = count)
            }
        }
    }

    fun markAsRead(messageId: String) {
        viewModelScope.launch {
            messageRepo.markAsRead(messageId)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            messageRepo.markAllAsRead()
        }
    }

    fun refreshFromServer() {
        viewModelScope.launch {
            val deviceId = deviceRepo.getStableDeviceId()
            messageRepo.syncMessages(deviceId)
        }
    }
}
