package cz.ondra.absplayer.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

data class Discovered(
    val serviceName: String,
    val deviceId: String?,
    val name: String,
    val host: String,
    val port: Int,
)

/** Finds ABS Player receivers (TVs) on the local network via mDNS / DNS-SD. */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val _found = MutableStateFlow<Map<String, Discovered>>(emptyMap())
    val found: StateFlow<Map<String, Discovered>> = _found

    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    queue.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { _found.value = _found.value - info.serviceName }
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    main.post {
                        val host = hostOf(si)
                        if (host != null) {
                            val attrs = si.attributes
                            val id = attrs["id"]?.let { String(it, Charsets.UTF_8) }
                            val name = attrs["name"]?.let { String(it, Charsets.UTF_8) } ?: si.serviceName
                            _found.value = _found.value + (si.serviceName to Discovered(si.serviceName, id, name, host, si.port))
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(si: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            val v4 = si.hostAddresses.firstOrNull { it is Inet4Address }
            if (v4 != null) return v4.hostAddress
        }
        return si.host?.hostAddress?.substringBefore('%')
    }
}
