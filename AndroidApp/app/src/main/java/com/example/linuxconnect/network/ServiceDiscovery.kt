package com.example.linuxconnect.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.linuxconnect.model.ServerInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "ServiceDiscovery"
private const val SERVICE_TYPE = "_linuxconnect._tcp"

class ServiceDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * mDNS スキャンを開始し、サーバーリストを Flow で返す。
     * Flow がキャンセルされると NSD を停止し、Android の mDNS キャッシュをクリアする。
     * 再スキャン時は ViewModel 側でこの Flow を cancel → 再収集することで
     * NSD を完全に停止 → 再起動し、キャッシュに依存しない新規クエリを発行する。
     */
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
                        if (host == null) {
                            Log.w(TAG, "Resolved ${si.serviceName} but host is null — skipping")
                        } else {
                            Log.d(TAG, "Resolved: ${si.serviceName} @ $host:${si.port}")
                            val server = ServerInfo(si.serviceName, host, si.port)
                            if (servers.none { it.host == host && it.port == si.port }) {
                                servers.add(server)
                                trySend(servers.toList())
                            }
                        }
                        isResolving = false
                        processQueue()
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
            /* Flow キャンセル時に NSD を完全停止 → 次の discoverServices() 呼び出しで
             * Android が新規 mDNS クエリを発行し、キャッシュに依存しない結果が得られる */
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            Log.d(TAG, "Discovery stopped and cache cleared")
        }
    }
}
