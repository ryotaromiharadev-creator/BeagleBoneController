package com.example.linuxconnect.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.linuxconnect.model.ServerInfo
import com.example.linuxconnect.network.ServiceDiscovery
import kotlinx.coroutines.Dispatchers
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

    private val _probingServer = MutableStateFlow<ServerInfo?>(null)
    val probingServer: StateFlow<ServerInfo?> = _probingServer

    init {
        viewModelScope.launch {
            discovery.discoverServices().collect { list ->
                _servers.value = list
            }
        }
    }

    fun connectToServer(server: ServerInfo, onSuccess: (ServerInfo) -> Unit) {
        if (_probingServer.value != null) return
        viewModelScope.launch {
            _probingServer.value = server
            val reachable = withContext(Dispatchers.IO) { isReachable(server.host, server.port) }
            _probingServer.value = null
            if (reachable) {
                onSuccess(server)
            } else {
                _servers.value = _servers.value.filter { it != server }
            }
        }
    }

    private fun isReachable(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            true
        }.getOrDefault(false)
}
