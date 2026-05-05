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
            delay(200)
            _isScanning.value = true
            launch { delay(3_000); _isScanning.value = false }

            discovery.discoverServices().collect { rawList ->
                // onServiceLost による削除はそのまま反映
                val removed = _servers.value.filter { known ->
                    rawList.none { it.host == known.host && it.port == known.port }
                }

                // 未検証のサーバーだけ TCP プローブ（並列）
                val toProbe = rawList.filter { candidate ->
                    _servers.value.none { it.host == candidate.host && it.port == candidate.port }
                }

                val verified = if (toProbe.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        toProbe.map { server ->
                            async { server.takeIf { isReachable(it.host, it.port) } }
                        }.awaitAll().filterNotNull()
                    }
                } else emptyList()

                _servers.value = (_servers.value - removed.toSet()) + verified
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        _isScanning.value = false
    }

    private fun isReachable(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), 1000) }
            true
        }.getOrDefault(false)

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}
