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

    private var scanJob: Job? = null

    fun startDiscovery() {
        scanJob?.cancel()          // 旧 Flow をキャンセル → awaitClose で NSD 停止
        _servers.value = emptyList()
        scanJob = viewModelScope.launch {
            delay(200)             // NSD が完全停止するのを待ってから再起動
            _isScanning.value = true
            discovery.discoverServices().collect { list ->
                _servers.value = list
            }
            _isScanning.value = false
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        _isScanning.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}
