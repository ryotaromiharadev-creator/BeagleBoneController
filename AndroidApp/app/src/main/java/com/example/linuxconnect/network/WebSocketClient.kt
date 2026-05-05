package com.example.linuxconnect.network

import android.util.Log
import com.example.linuxconnect.model.ChatMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "WebSocketClient"

class WebSocketClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun connect(host: String, port: Int): Flow<ChatMessage> = callbackFlow {
        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                _isConnected.value = true
                Log.d(TAG, "Connected to $host:$port")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    if (json.getString("type") == "message") {
                        trySend(ChatMessage(json.getString("content"), isFromMe = false))
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                Log.e(TAG, "Connection failure: $t")
                close(t)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                Log.d(TAG, "Connection closed: $reason")
                close()
            }
        }

        client.newWebSocket(request, listener)

        awaitClose {
            webSocket?.close(1000, "Flow closed")
            webSocket = null
            _isConnected.value = false
        }
    }

    fun sendMessage(content: String) {
        val payload = JSONObject().apply {
            put("type", "message")
            put("content", content)
            put("timestamp", System.currentTimeMillis() / 1000.0)
        }
        webSocket?.send(payload.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _isConnected.value = false
    }
}
