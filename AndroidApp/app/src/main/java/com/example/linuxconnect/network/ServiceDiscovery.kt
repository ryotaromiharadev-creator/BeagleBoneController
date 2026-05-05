package com.example.linuxconnect.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.linuxconnect.model.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "ServiceDiscovery"
private const val SERVICE_TYPE = "_linuxconnect._tcp"
private const val PROBE_TIMEOUT_MS = 500

class ServiceDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private suspend fun isReachable(host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
                true
            }.getOrDefault(false)
        }

    fun discoverServices(): Flow<List<ServerInfo>> = callbackFlow {
        val servers = mutableListOf<ServerInfo>()
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var isResolving = false

        fun processQueue() {
            if (resolveQueue.isNotEmpty() && !isResolving) {
                isResolving = true
                @Suppress("DEPRECATION")
                nsdManager.resolveService(resolveQueue.removeFirst(), object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${si.serviceName}: $errorCode")
                        isResolving = false
                        processQueue()
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host?.hostAddress
                        if (host != null && servers.none { it.host == host && it.port == si.port }) {
                            launch {
                                if (isReachable(host, si.port)) {
                                    servers.add(ServerInfo(si.serviceName, host, si.port))
                                    trySend(servers.toList())
                                    Log.d(TAG, "Server added: ${si.serviceName} $host:${si.port}")
                                } else {
                                    Log.d(TAG, "Server unreachable, skipped: ${si.serviceName} $host:${si.port}")
                                }
                                isResolving = false
                                processQueue()
                            }
                        } else {
                            isResolving = false
                            processQueue()
                        }
                    }
                })
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveQueue.add(serviceInfo)
                processQueue()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                servers.removeIf { it.name == serviceInfo.serviceName }
                trySend(servers.toList())
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        trySend(emptyList())

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }
}
