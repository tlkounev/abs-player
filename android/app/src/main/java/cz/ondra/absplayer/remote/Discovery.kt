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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request

data class Discovered(
    val serviceName: String,
    val deviceId: String?,
    val name: String,
    val host: String,
    val port: Int,
    val kind: String = "tv",
)

/**
 * Finds ABS Player receivers (TVs, PCs) on the local network: via mDNS / DNS-SD and, because
 * mDNS is unreliable on some phones and routers, also by asking every address of the home
 * network (/24) on the receiver ports directly (GET /info).
 */
class Discovery(context: Context) {
    private val app = context.applicationContext as cz.ondra.absplayer.App
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val _found = MutableStateFlow<Map<String, Discovered>>(emptyMap())
    val found: StateFlow<Map<String, Discovered>> = _found

    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val attempts = mutableMapOf<String, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private val scanHttp = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(4, TimeUnit.SECONDS) // the PC answers /info after asking its ABS window
        .build()

    /** Scans the home network (/24 of this device) for receivers; results go to [found]. */
    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            val me = ownAddress() ?: return@launch
            val prefix = me.substringBeforeLast('.') + "."
            val gate = Semaphore(128)
            // all addresses on 8765 first (that is where a receiver usually is), then the next ports;
            // results show up as they come
            val hosts = (1..254).map { "$prefix$it" }.filter { it != me }
            SCAN_PORTS.flatMap { port -> hosts.map { it to port } }
                .map { (host, port) ->
                    async {
                        gate.withPermit {
                            if (!open(host, port)) return@withPermit
                            val info = fetchInfo(host, port) ?: return@withPermit
                            if (info.deviceId == app.settings.deviceId) return@withPermit
                            main.post {
                                // one entry per device: mDNS and scan may both find it
                                val key = "id:" + info.deviceId
                                val rest = _found.value.filterValues { it.deviceId != info.deviceId }
                                _found.value = rest + (key to Discovered(key, info.deviceId, info.name, host, port, info.kind))
                            }
                        }
                    }
                }.awaitAll()
        }
    }

    private fun fetchInfo(host: String, port: Int): InfoResponse? = runCatching {
        scanHttp.newCall(Request.Builder().url("http://$host:$port/info").build()).execute().use { r ->
            if (!r.isSuccessful) null
            else RemoteHttp.json.decodeFromString(InfoResponse.serializer(), r.body?.string() ?: "")
        }
    }.getOrNull()

    /**
     * Finds the receiver on [host]: [port] first, then the other receiver ports. Something else may
     * hold 8765 on a TV – then our receiver runs on 8766 and old addresses / mDNS records still say
     * 8765. With [deviceId] only that device counts. Returns the working port and its /info.
     */
    suspend fun locate(host: String, port: Int, deviceId: String? = null): Pair<Int, InfoResponse>? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            fun ok(i: InfoResponse?) = i != null && (deviceId == null || i.deviceId == deviceId)
            fetchInfo(host, port)?.takeIf { ok(it) }?.let { return@withContext port to it }
            SCAN_PORTS.filter { it != port }
                .map { q -> async { if (open(host, q)) fetchInfo(host, q)?.takeIf { ok(it) }?.let { q to it } else null } }
                .awaitAll()
                .firstOrNull { it != null }
        }

    private fun open(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 400); true }
    } catch (_: Exception) {
        false
    }

    fun start() {
        scan()
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    listener = null
                    // try once more a bit later (e.g. a previous search was still stopping)
                    main.postDelayed({ start() }, 2000)
                }
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
                        // busy / timeout: try the same service again (max. 3×), other ones first
                        val n = (attempts[info.serviceName] ?: 0) + 1
                        attempts[info.serviceName] = n
                        if (n < 3 && listener != null) main.postDelayed({ queue.addLast(info); resolveNext() }, 800L * n)
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
                            val kind = attrs["kind"]?.let { String(it, Charsets.UTF_8) } ?: "tv"
                            if (id != app.settings.deviceId) {
                                val sn = si.serviceName
                                scope.launch {
                                    // an mDNS record can be old (other port) – take only what really answers
                                    val (port, info) = locate(host, si.port, id) ?: return@launch
                                    main.post {
                                        val rest = _found.value.filterValues { it.deviceId != info.deviceId }
                                        _found.value = rest + (sn to Discovered(sn, info.deviceId, info.name.ifBlank { name }, host, port, info.kind.ifBlank { kind }))
                                    }
                                }
                            }
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

    companion object {
        /** The receiver takes the first free port from 8765 (8766 when something else holds 8765, …). */
        val SCAN_PORTS = (DEFAULT_PORT until DEFAULT_PORT + 10).toList()

        /** This device's IPv4 address in the home network. */
        fun ownAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                // Wi-Fi / Ethernet first (mobile data can have a private address too)
                .sortedBy { n -> if (n.name.startsWith("wlan") || n.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
