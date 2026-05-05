package com.example.linuxconnect.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.linuxconnect.model.ChatMessage
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.network.WebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

class ChatViewModel : ViewModel() {

    private val wsClient = WebSocketClient()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    val isConnected: StateFlow<Boolean> = wsClient.isConnected

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    fun connect(server: ServerInfo) {
        _messages.value = emptyList()
        _connectionError.value = null
        viewModelScope.launch {
            try {
                wsClient.connect(server.host, server.port).collect { message ->
                    _messages.value = _messages.value + message
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionError.value = e.message ?: "接続に失敗しました"
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        _messages.value = _messages.value + ChatMessage(content, isFromMe = true)
        wsClient.sendMessage(content)
    }

    fun disconnect() {
        wsClient.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
    }
}
