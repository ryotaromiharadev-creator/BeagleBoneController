package com.example.linuxconnect.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.network.UdpSender
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

    /** Normalised axis values: -1.0 (full reverse/left) … +1.0 (full forward/right). */
    var throttle by mutableStateOf(0f)
    var steering by mutableStateOf(0f)
    var isConnected by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    private val senderRef   = AtomicReference<UdpSender?>(null)
    private val scheduler   = Executors.newSingleThreadScheduledExecutor()
    private var sendTask: ScheduledFuture<*>? = null

    fun connect(server: ServerInfo) {
        disconnect()
        errorMessage = null
        try {
            val sender = UdpSender(server.host, UDP_PORT)
            senderRef.set(sender)
            isConnected = true

            sendTask = scheduler.scheduleAtFixedRate(
                {
                    val t = (throttle * 127f).toInt()
                    val s = (steering  * 127f).toInt()
                    try { senderRef.get()?.send(t, s) } catch (_: Exception) {}
                },
                0L, INTERVAL_MS, TimeUnit.MILLISECONDS
            )
        } catch (e: Exception) {
            errorMessage = e.message
        }
    }

    fun disconnect() {
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
