package com.example.linuxconnect.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.network.ServiceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
    private var healthCheckJob: Job? = null

    fun startDiscovery() {
        scanJob?.cancel()
        healthCheckJob?.cancel()
        _servers.value = emptyList()

        scanJob = viewModelScope.launch {
            _isScanning.value = true
            discovery.discoverServices().collect { list ->
                _servers.value = list
            }
            _isScanning.value = false
        }

        healthCheckJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val current = _servers.value
                if (current.isNotEmpty()) {
                    val alive = current.filter { isReachable(it.host, it.port) }
                    if (alive.size != current.size) {
                        _servers.value = alive
                    }
                }
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        healthCheckJob?.cancel()
        _isScanning.value = false
    }

    private suspend fun isReachable(host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 1000) }
                true
            }.getOrDefault(false)
        }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}
