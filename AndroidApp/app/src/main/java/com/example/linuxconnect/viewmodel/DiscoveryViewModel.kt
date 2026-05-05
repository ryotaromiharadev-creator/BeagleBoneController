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
        scanJob?.cancel()
        _servers.value = emptyList()

        scanJob = viewModelScope.launch {
            delay(200)              // 旧 NSD が完全停止するのを待つ
            _isScanning.value = true

            // mDNS をバックグラウンドで監視し続ける（キャッシュ含む全結果を追加）
            launch {
                discovery.discoverServices().collect { rawList ->
                    val added = rawList.filter { s ->
                        _servers.value.none { it.host == s.host && it.port == s.port }
                    }
                    val removed = _servers.value.filter { k ->
                        rawList.none { it.host == k.host && it.port == k.port }
                    }
                    _servers.value = (_servers.value - removed.toSet()) + added
                }
            }

            // 2 秒待ってからプローブ（ネットワーク安定後に実行）
            delay(2000)
            pruneDeadServers()
            _isScanning.value = false
            // 以後も mDNS 監視は継続（新規サーバーを自動追加）
        }
    }

    // 現在のリストを並列 TCP プローブし、応答しないサーバーを除去する
    private suspend fun pruneDeadServers() {
        val snapshot = _servers.value
        if (snapshot.isEmpty()) return
        _servers.value = withContext(Dispatchers.IO) {
            coroutineScope {
                snapshot.map { server ->
                    async { server.takeIf { isReachable(it.host, it.port) } }
                }.awaitAll().filterNotNull()
            }
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
