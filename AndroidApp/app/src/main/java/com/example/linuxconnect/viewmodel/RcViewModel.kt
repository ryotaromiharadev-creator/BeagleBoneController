package com.example.linuxconnect.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.network.UdpSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RcViewModel : ViewModel() {

    companion object {
        const val UDP_PORT    = 9000
        const val SEND_HZ     = 500L
        const val INTERVAL_MS = 1000L / SEND_HZ   // 2 ms
    }

    var throttle     by mutableStateOf(0f)
    var steering     by mutableStateOf(0f)
    var isConnected  by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    private val senderRef = AtomicReference<UdpSender?>(null)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var sendTask: ScheduledFuture<*>? = null
    private var heartbeatJob: Job? = null

    fun connect(server: ServerInfo) {
        disconnect()
        errorMessage = null
        isConnected  = false   // heartbeat が届くまで未接続扱い

        try {
            val sender = UdpSender(server.host, UDP_PORT)
            senderRef.set(sender)

            // 500 Hz 送信ループ
            sendTask = scheduler.scheduleAtFixedRate(
                {
                    val t = (throttle * 127f).toInt()
                    val s = (steering  * 127f).toInt()
                    try { senderRef.get()?.send(t, s) } catch (_: Exception) {}
                },
                0L, INTERVAL_MS, TimeUnit.MILLISECONDS,
            )

            // Heartbeat 監視コルーチン
            // サーバーが 1 Hz で 0x01 を返す。3 秒届かなければ isConnected = false。
            // サーバーが復帰すれば自動で isConnected = true に戻る。
            heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val alive = senderRef.get()?.awaitHeartbeat(3000) ?: break
                    withContext(Dispatchers.Main) { isConnected = alive }
                }
                withContext(Dispatchers.Main) { isConnected = false }
            }
        } catch (e: Exception) {
            errorMessage = e.message
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sendTask?.cancel(false)
        sendTask = null
        senderRef.getAndSet(null)?.close()
        isConnected = false
    }

    override fun onCleared() {
        disconnect()
        scheduler.shutdownNow()
    }
}
