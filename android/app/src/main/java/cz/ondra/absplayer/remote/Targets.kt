package cz.ondra.absplayer.remote

import cz.ondra.absplayer.tr
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import cz.ondra.absplayer.App
import cz.ondra.absplayer.data.ApiException
import cz.ondra.absplayer.data.PairedDevice
import cz.ondra.absplayer.player.NowPlaying
import cz.ondra.absplayer.player.PlaybackHub
import cz.ondra.absplayer.player.PlaybackService
import cz.ondra.absplayer.player.PlayerEngine
import cz.ondra.absplayer.tv.ReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where playback happens: this device, or a paired TV controlled over the LAN. */
interface PlaybackTarget {
    val id: String
    val name: String
    val isRemote: Boolean
    val state: StateFlow<NowPlaying>

    fun playItem(itemId: String, startSec: Double? = null)
    fun play()
    fun pause()
    fun toggle()
    fun seekTo(sec: Double)
    fun skip(deltaSec: Double)
    fun chapter(index: Int)
    fun next()
    fun prev()
    fun setSpeed(speed: Float)
    fun setSleep(minutes: Int)
    fun setVolume(volume: Float)
    fun toggleMute()
    fun stop()

    /** Pauses, makes sure the server has the position, and returns it (null = nothing loaded). */
    suspend fun pauseForHandoff(): Double?

    fun activate() {}
    fun deactivate() {}
}

class LocalTarget(private val app: App) : PlaybackTarget {
    override val id = "local"
    override val name: String get() = app.settings.localName ?: cz.ondra.absplayer.tv.ReceiverService.systemName(app)
    override val isRemote = false
    override val state: StateFlow<NowPlaying> = PlaybackHub.state

    private var controller: ListenableFuture<MediaController>? = null

    /** Starts the playback service of this device (the engine registers itself in PlaybackHub). */
    fun ensureService() {
        if (app.isTv) {
            if (PlaybackHub.engine == null) {
                ContextCompat.startForegroundService(app, Intent(app, ReceiverService::class.java))
            }
            return
        }
        if (controller == null) {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controller = MediaController.Builder(app, token).buildAsync()
        }
    }

    private fun withEngine(block: (PlayerEngine) -> Unit) {
        ensureService()
        PlaybackHub.whenReady(block)
    }

    override fun playItem(itemId: String, startSec: Double?) = withEngine { it.playItem(itemId, startSec) }
    override fun play() = withEngine { it.play() }
    override fun pause() = withEngine { it.pause() }
    override fun toggle() = withEngine { it.toggle() }
    override fun seekTo(sec: Double) = withEngine { it.seekTo(sec) }
    override fun skip(deltaSec: Double) = withEngine { it.skip(deltaSec) }
    override fun chapter(index: Int) = withEngine { it.chapter(index) }
    override fun next() = withEngine { it.nextChapter() }
    override fun prev() = withEngine { it.prevChapter() }
    override fun setSpeed(speed: Float) = withEngine { it.setSpeed(speed) }
    override fun setSleep(minutes: Int) = withEngine { it.setSleep(minutes) }
    override fun setVolume(volume: Float) = withEngine { it.setVolume(volume) }
    override fun toggleMute() = withEngine { it.toggleMute() }
    override fun stop() {
        PlaybackHub.engine?.stop()
    }

    override suspend fun pauseForHandoff(): Double? {
        val e = PlaybackHub.engine ?: return null
        if (e.player.book == null) return null
        return e.pauseNow()
    }
}

class RemoteTarget(private val app: App, device: PairedDevice) : PlaybackTarget {
    @Volatile
    var device: PairedDevice = device

    override val id: String get() = device.deviceId
    override val name: String get() = device.name
    override val isRemote = true

    private val _state = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = _state

    /** null = unknown yet. */
    val online = MutableStateFlow<Boolean?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    /** What we know before the device answers (the book just handed over) – no empty player meanwhile. */
    fun seed(np: NowPlaying) {
        _state.value = np
    }

    override fun activate() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(1_000)
            }
        }
    }

    override fun deactivate() {
        pollJob?.cancel()
        pollJob = null
    }

    private var lastLocate = 0L

    suspend fun refresh() {
        try {
            _state.value = request("/state", null)
            online.value = true
        } catch (e: Exception) {
            // not on its port any more? (the receiver moved to the next port) – look, at most every 20 s
            if (e !is ApiException && System.currentTimeMillis() - lastLocate > 20_000) {
                lastLocate = System.currentTimeMillis()
                val hit = app.targets.discovery.locate(device.host, device.port, device.deviceId)
                if (hit != null && hit.first != device.port) {
                    // the deviceId is public: believe the new port only when the device signs its answer
                    val moved = device.copy(port = hit.first)
                    val ok = withContext(Dispatchers.IO) { runCatching { RemoteHttp.state(moved) }.getOrNull() }
                    if (ok != null) {
                        app.targets.moved(moved)
                        _state.value = ok
                        online.value = true
                        return
                    }
                }
            }
            online.value = false
        }
    }

    private suspend fun request(path: String, cmd: Command?): NowPlaying = withContext(Dispatchers.IO) {
        val text = RemoteHttp.signed(device, if (cmd != null) "POST" else "GET", path, cmd?.let { RemoteHttp.json.encodeToString(it) })
        RemoteHttp.json.decodeFromString(NowPlaying.serializer(), text)
    }

    private fun send(cmd: Command) {
        scope.launch {
            try {
                _state.value = request("/cmd", cmd)
                online.value = true
            } catch (e: Exception) {
                online.value = false
                _state.value = _state.value.copy(error = e.message ?: tr("deviceNoResponse"))
            }
        }
    }

    override fun playItem(itemId: String, startSec: Double?) = send(Command("playItem", startSec, itemId))
    override fun play() = send(Command("play"))
    override fun pause() = send(Command("pause"))
    override fun toggle() = send(Command("toggle"))
    override fun seekTo(sec: Double) {
        _state.value = _state.value.copy(positionSec = sec)
        send(Command("seek", sec))
    }
    override fun skip(deltaSec: Double) = send(Command("skip", deltaSec))
    override fun chapter(index: Int) = send(Command("chapter", index.toDouble()))
    override fun next() = send(Command("next"))
    override fun prev() = send(Command("prev"))
    override fun setSpeed(speed: Float) = send(Command("speed", speed.toDouble()))
    override fun setSleep(minutes: Int) = send(Command("sleep", minutes.toDouble()))
    override fun setVolume(volume: Float) {
        _state.value = _state.value.copy(volume = volume, muted = volume <= 0f)
        send(Command("volume", volume.toDouble()))
    }
    override fun toggleMute() = send(Command("mute"))
    override fun stop() = send(Command("stop"))

    override suspend fun pauseForHandoff(): Double? {
        val s = runCatching { request("/cmd", Command("pause")) }.getOrNull() ?: return null
        _state.value = s
        return if (s.itemId != null) s.positionSec else null
    }
}

/** The answer is not signed with our key: whoever answers is not the paired device. */
class IdentityException(message: String) : IOException(message)

internal object RemoteHttp {
    val JSON = "application/json; charset=utf-8".toMediaType()
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Receiver clock minus ours, per device (signed requests carry the receiver's time). */
    private val offsets = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun learnClock(deviceId: String, receiverTime: Long?) {
        if (receiverTime != null) offsets[deviceId] = receiverTime - System.currentTimeMillis()
    }

    fun infoBlocking(host: String, port: Int): InfoResponse =
        client.newCall(Request.Builder().url("http://$host:$port/info").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
            json.decodeFromString(InfoResponse.serializer(), resp.body?.string() ?: "")
        }

    fun errorText(text: String, code: Int): String =
        runCatching { json.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull() ?: "HTTP $code"

    /** Signed GET /state – also the check that [d] really answers at its host:port. Blocking. */
    fun state(d: PairedDevice): NowPlaying = json.decodeFromString(NowPlaying.serializer(), signed(d, "GET", "/state", null))

    /**
     * Signed request to a paired device; the answer must carry the device's signature, otherwise
     * [IdentityException]. A clock difference is learned from the refusal and the request repeated. Blocking.
     */
    fun signed(d: PairedDevice, method: String, path: String, body: String?, retry: Boolean = true): String {
        val (clientId, key) = d.link() ?: throw ApiException(401, tr("repairNeeded"))
        val k = LinkCrypto.unb64(key)
        val text = body ?: ""
        val time = System.currentTimeMillis() + (offsets[d.deviceId] ?: 0L)
        val nonce = LinkCrypto.randomHex(16)
        val b = Request.Builder().url("http://${d.host}:${d.port}$path")
            .header("X-Client", clientId).header("X-Time", time.toString()).header("X-Nonce", nonce)
            .header("X-Sig", LinkCrypto.requestSig(k, method, path, time, nonce, text))
        when (method) {
            "POST" -> b.post(text.toRequestBody(JSON))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        client.newCall(b.build()).execute().use { resp ->
            val answer = resp.body?.string() ?: ""
            if (resp.code == 426) throw ApiException(426, tr("peerUpdate"))
            if (resp.code == 401) {
                val err = runCatching { json.decodeFromString(ErrorResponse.serializer(), answer) }.getOrNull()
                if (err?.error == "clock" && err.time != null && retry) {
                    learnClock(d.deviceId, err.time)
                    return signed(d, method, path, body, retry = false)
                }
                // an old app version on the other device does not know signed requests
                val info = runCatching { infoBlocking(d.host, d.port) }.getOrNull()
                if (info != null && info.version < PROTOCOL_VERSION) throw ApiException(426, tr("peerUpdate"))
                throw ApiException(401, tr("repairNeeded"))
            }
            if (!LinkCrypto.sameSig(resp.header("X-Sig"), LinkCrypto.responseSig(k, nonce, resp.code, answer))) {
                throw IdentityException(tr("identity"))
            }
            if (!resp.isSuccessful) throw ApiException(resp.code, errorText(answer, resp.code))
            return answer
        }
    }
}

class TargetManager(private val app: App) {
    val local = LocalTarget(app)
    private val remotes = mutableMapOf<String, RemoteTarget>()
    private val _current = MutableStateFlow<PlaybackTarget>(local)
    val current: StateFlow<PlaybackTarget> = _current
    val discovery = Discovery(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _paired = MutableStateFlow(app.settings.pairedDevices)
    val paired: StateFlow<List<PairedDevice>> = _paired

    /** Addresses being checked right now (one check per address). */
    private val checking = mutableSetOf<String>()

    init {
        scope.launch {
            discovery.found.collect { found -> updateHosts(found.values) }
        }
    }

    fun remote(d: PairedDevice): RemoteTarget =
        remotes.getOrPut(d.deviceId) { RemoteTarget(app, d) }.also { it.device = d }

    /**
     * IP addresses change (DHCP): a paired device found elsewhere moves there – but only when it signs
     * its answer with our key there (its deviceId is public, anyone could announce it).
     */
    private fun updateHosts(found: Collection<Discovered>) {
        for (f in found) {
            val id = f.deviceId ?: continue
            val p = app.settings.pairedDevices.find { it.deviceId == id } ?: continue
            if (p.host == f.host && p.port == f.port) continue
            val np = p.copy(host = f.host, port = f.port)
            val key = "$id@${f.host}:${f.port}"
            if (!checking.add(key)) continue
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { RemoteHttp.state(np) }.isSuccess }
                checking.remove(key)
                val now = app.settings.pairedDevices.find { it.deviceId == id } ?: return@launch
                if (ok) moved(now.copy(host = f.host, port = f.port))
            }
        }
    }

    /**
     * Moves playback to [target]. If something is playing, it is paused and synced where it is,
     * then started on the target from exactly that position.
     */
    /**
     * Switches playback to [target] and moves the book there: it continues on the new device right
     * away (also when it was paused). Returns the book id when one was handed over.
     */
    suspend fun switchTo(target: PlaybackTarget): String? {
        val cur = _current.value
        if (cur.id == target.id) return null
        val st = cur.state.value
        val itemId = st.itemId
        // do not wait long for the old device (off / slow): then the last known position is used
        val pos = if (itemId != null) {
            kotlinx.coroutines.withTimeoutOrNull(2_500) { cur.pauseForHandoff() } ?: st.positionSec
        } else null
        cur.deactivate()
        // the new device shows the handed book at once (it answers with its real state a moment later)
        if (itemId != null && pos != null && target is RemoteTarget) {
            target.seed(st.copy(positionSec = pos, isPlaying = true, isLoading = true, error = null, info = null))
        }
        _current.value = target
        target.activate()
        if (itemId != null && pos != null) {
            target.playItem(itemId, pos)
            if (cur is LocalTarget) cur.stop()
            if (target is RemoteTarget) watchHandoff(target, cur, itemId, pos)
            return itemId
        }
        return null
    }

    private var handoffJob: kotlinx.coroutines.Job? = null

    /**
     * The TV may be off or still waking up: keep sending the book while it comes up. If it does not
     * play it within 30 s, go back to the device it came from and continue there.
     */
    private fun watchHandoff(target: RemoteTarget, from: PlaybackTarget, itemId: String, pos: Double) {
        handoffJob?.cancel()
        handoffJob = scope.launch {
            val started = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                var lastSend = System.currentTimeMillis()
                while (true) {
                    kotlinx.coroutines.delay(2_000)
                    if (_current.value.id != target.id) return@withTimeoutOrNull true // user chose something else
                    target.refresh()
                    val st = target.state.value
                    if (target.online.value == true && st.itemId == itemId) return@withTimeoutOrNull true
                    if (target.online.value == true && System.currentTimeMillis() - lastSend > 6_000) {
                        target.playItem(itemId, pos)
                        lastSend = System.currentTimeMillis()
                    }
                }
                true
            }
            if (started == null && _current.value.id == target.id) {
                target.deactivate()
                _current.value = from
                from.activate()
                from.playItem(itemId, pos)
            }
        }
    }

    suspend fun info(host: String, port: Int): InfoResponse = withContext(Dispatchers.IO) {
        try {
            RemoteHttp.infoBlocking(host.trim(), port)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw IOException(tr("noResponseAt", "h" to "$host:$port"), e)
        }
    }

    /** Whether pairing [info]'s device needs the ABS password (to hand it its own login). */
    fun needsPassword(info: InfoResponse): Boolean =
        info.acceptsTokens && !(info.loggedIn && sameServer(info.server, app.settings.serverUrl) && info.username == app.settings.username)

    /**
     * Pairs a receiver. A TV that is not signed in to this account gets its own token pair: the
     * phone logs in once more with the user's password (refresh tokens rotate, so devices must not
     * share one). A PC, or a TV already on this account, only needs the code it shows.
     */
    suspend fun pair(host: String, port: Int, code: String, password: String?): PairedDevice {
        val server = app.settings.serverUrl ?: throw IOException(tr("notSignedIn"))
        val user = app.settings.username ?: throw IOException(tr("notSignedIn"))
        // the typed / found port may be old: take the port the receiver really answers on
        val (realPort, info) = discovery.locate(host.trim(), port) ?: throw IOException(tr("noResponseAt", "h" to "$host:$port"))
        @Suppress("NAME_SHADOWING") val port = realPort
        @Suppress("NAME_SHADOWING") val host = host.trim()
        if (info.version < PROTOCOL_VERSION) throw IOException(tr("peerUpdate"))
        RemoteHttp.learnClock(info.deviceId, info.time)
        var pay = PairPayload(server, user, app.settings.userId, name = local.name)
        if (needsPassword(info)) {
            if (password.isNullOrEmpty()) throw IOException(tr("needPassword"))
            val login = app.api.login(server, user, password)
            val token = login.user.accessToken ?: login.user.token ?: throw IOException(tr("noToken"))
            pay = pay.copy(userId = login.user.id, accessToken = token, refreshToken = login.user.refreshToken)
        }
        val (result, link) = withContext(Dispatchers.IO) {
            fun post(path: String, body: String): String {
                val r = Request.Builder().url("http://$host:$port$path").post(body.toRequestBody(RemoteHttp.JSON)).build()
                return RemoteHttp.client.newCall(r).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw ApiException(resp.code, RemoteHttp.errorText(text, resp.code))
                    text
                }
            }
            val kp = LinkCrypto.newKeyPair()
            val myPub = LinkCrypto.publicRaw(kp)
            val start = RemoteHttp.json.decodeFromString(
                PairStartResponse.serializer(), post("/pair/start", RemoteHttp.json.encodeToString(PairStart(myPub))),
            )
            val keys = LinkCrypto.pairKeys(LinkCrypto.sharedSecret(kp, start.receiverPub), start.pairId, myPub, start.receiverPub, info.deviceId)
            val finish = PairFinish(
                start.pairId, LinkCrypto.proof(keys, "client", code),
                LinkCrypto.seal(keys.enc, "client", RemoteHttp.json.encodeToString(pay)),
            )
            val resp = RemoteHttp.json.decodeFromString(
                PairFinishResponse.serializer(), post("/pair/finish", RemoteHttp.json.encodeToString(finish)),
            )
            // the receiver proves it knows the code too (so it is the device whose code was typed)
            if (!LinkCrypto.sameSig(resp.proof, LinkCrypto.proof(keys, "receiver", code))) throw IdentityException(tr("identity"))
            val r = RemoteHttp.json.decodeFromString(PairResult.serializer(), LinkCrypto.open(keys.enc, "receiver", resp.box))
            if (r.deviceId != info.deviceId) throw IdentityException(tr("identity"))
            r to LinkCrypto.b64(keys.link)
        }
        val d = PairedDevice(result.deviceId, result.name, host = host, port = port, kind = info.kind, clientId = result.clientId, key = link)
        app.settings.upsertPaired(d)
        _paired.value = app.settings.pairedDevices
        return d
    }

    /** A paired receiver answers on another port now. */
    fun moved(d: PairedDevice) {
        app.settings.upsertPaired(d)
        remotes[d.deviceId]?.device = d
        _paired.value = app.settings.pairedDevices
    }

    /** Local name for a paired device (only on this device). */
    fun rename(deviceId: String, name: String) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        val p = app.settings.pairedDevices.find { it.deviceId == deviceId } ?: return
        val np = p.copy(name = n)
        app.settings.upsertPaired(np)
        remotes[deviceId]?.device = np
        _paired.value = app.settings.pairedDevices
    }

    fun unpair(deviceId: String) {
        // the receiver forgets this phone too (if it is on)
        app.settings.pairedDevices.find { it.deviceId == deviceId }?.let { d ->
            scope.launch(Dispatchers.IO) { runCatching { RemoteHttp.signed(d, "DELETE", "/pair", null) } }
        }
        if (_current.value.id == deviceId) {
            _current.value.deactivate()
            _current.value = local
        }
        remotes.remove(deviceId)?.deactivate()
        app.settings.removePaired(deviceId)
        _paired.value = app.settings.pairedDevices
    }
}
