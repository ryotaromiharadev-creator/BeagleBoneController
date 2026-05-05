package com.example.linuxconnect.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.network.ServiceDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = ServiceDiscovery(application)

    private val _servers = MutableStateFlow<List<ServerInfo>>(emptyList())
    val servers: StateFlow<List<ServerInfo>> = _servers

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private var discoveryJob: Job? = null

    init {
        startDiscovery()
    }

    fun rescan() {
        startDiscovery()
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _isScanning.value = true
            // 旧 NSD が完全停止するまで待つ
            delay(300)
            try {
                discovery.discoverServices().collect { list ->
                    _servers.value = list
                    // 最初のイベント(emptyList)が来たらスキャン中表示を解除
                    if (_isScanning.value) _isScanning.value = false
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun removeServer(server: ServerInfo) {
        _servers.value = _servers.value.filter { it != server }
    }
}
