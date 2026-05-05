package com.example.linuxconnect.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.network.ServiceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = ServiceDiscovery(application)

    private val _servers = MutableStateFlow<List<ServerInfo>>(emptyList())
    val servers: StateFlow<List<ServerInfo>> = _servers

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private var scanJob: Job? = null

    fun startDiscovery() {
        if (_isScanning.value) return   // 二重タップ防止
        scanJob?.cancel()
        _isScanning.value = true
        // _servers はクリアしない — スキャン完了まで前回の結果を表示し続ける

        scanJob = viewModelScope.launch {
            // mDNS を 3 秒間収集（キャッシュ含む全サービスが応答する時間）
            val found = mutableListOf<ServerInfo>()
            val mDnsJob = launch {
                discovery.discoverServices().collect { rawList ->
                    synchronized(found) {
                        found.clear()
                        found.addAll(rawList)
                    }
                }
            }
            delay(3000)
            mDnsJob.cancel()    // mDNS 停止（次回再スキャン時にキャッシュなし再起動）

            // 見つかったサーバーを並列 TCP プローブ（最大 2 秒タイムアウト）
            val snapshot = synchronized(found) { found.toList() }
            _servers.value = if (snapshot.isEmpty()) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        snapshot.map { server ->
                            async { server.takeIf { isReachable(it.host, it.port) } }
                        }.awaitAll().filterNotNull()
                    }
                }
            }

            _isScanning.value = false
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        _isScanning.value = false
    }

    private fun isReachable(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            true
        }.getOrDefault(false)

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}
