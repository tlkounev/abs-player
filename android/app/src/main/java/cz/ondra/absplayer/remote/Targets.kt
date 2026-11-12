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
    override val name: String get() = if (app.isTv) tr("thisTv") else tr("thisPhone")
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
        return e.pauseAndSync()
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

    private fun base() = "http://${device.host}:${device.port}"

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

    suspend fun refresh() {
        try {
            _state.value = request("/state", null)
            online.value = true
        } catch (e: Exception) {
            online.value = false
        }
    }

    private suspend fun request(path: String, cmd: Command?): NowPlaying = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base() + path).header("X-Secret", device.secret)
        if (cmd != null) b.post(RemoteHttp.json.encodeToString(cmd).toRequestBody(RemoteHttp.JSON))
        RemoteHttp.client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) throw ApiException(401, tr("repairNeeded"))
            if (!resp.isSuccessful) throw ApiException(resp.code, text.take(200))
            RemoteHttp.json.decodeFromString(NowPlaying.serializer(), text)
        }
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

internal object RemoteHttp {
    val JSON = "application/json; charset=utf-8".toMediaType()
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
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

    init {
        scope.launch {
            discovery.found.collect { found -> updateHosts(found.values) }
        }
    }

    fun remote(d: PairedDevice): RemoteTarget =
        remotes.getOrPut(d.deviceId) { RemoteTarget(app, d) }.also { it.device = d }

    private fun updateHosts(found: Collection<Discovered>) {
        var changed = false
        for (f in found) {
            val id = f.deviceId ?: continue
            val p = app.settings.pairedDevices.find { it.deviceId == id } ?: continue
            if (p.host != f.host || p.port != f.port) {
                val np = p.copy(host = f.host, port = f.port)
                app.settings.upsertPaired(np)
                remotes[id]?.device = np
                changed = true
            }
        }
        if (changed) _paired.value = app.settings.pairedDevices
    }

    /**
     * Moves playback to [target]. If something is playing, it is paused and synced where it is,
     * then started on the target from exactly that position.
     */
    suspend fun switchTo(target: PlaybackTarget) {
        val cur = _current.value
        if (cur.id == target.id) return
        val st = cur.state.value
        val itemId = st.itemId
        val wasPlaying = st.isPlaying
        val pos = if (itemId != null) cur.pauseForHandoff() else null
        cur.deactivate()
        _current.value = target
        target.activate()
        if (itemId != null && pos != null && wasPlaying) {
            target.playItem(itemId, pos)
            if (cur is LocalTarget) cur.stop()
            if (target is RemoteTarget) watchHandoff(target, cur, itemId, pos)
        }
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
        val r = Request.Builder().url("http://$host:$port/info").get().build()
        RemoteHttp.client.newCall(r).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
            RemoteHttp.json.decodeFromString(InfoResponse.serializer(), text)
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
        val info = info(host, port)
        var req = PairRequest(code.trim(), server, user, app.settings.userId, phoneName = Build.MODEL)
        if (needsPassword(info)) {
            if (password.isNullOrEmpty()) throw IOException(tr("needPassword"))
            val login = app.api.login(server, user, password)
            val token = login.user.accessToken ?: login.user.token ?: throw IOException(tr("noToken"))
            req = req.copy(userId = login.user.id, accessToken = token, refreshToken = login.user.refreshToken)
        }
        val resp = withContext(Dispatchers.IO) {
            val r = Request.Builder()
                .url("http://$host:$port/pair")
                .post(RemoteHttp.json.encodeToString(req).toRequestBody(RemoteHttp.JSON))
                .build()
            RemoteHttp.client.newCall(r).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = runCatching { RemoteHttp.json.decodeFromString(ErrorResponse.serializer(), text).error }
                        .getOrNull() ?: "HTTP ${resp.code}"
                    throw ApiException(resp.code, msg)
                }
                RemoteHttp.json.decodeFromString(PairResponse.serializer(), text)
            }
        }
        val d = PairedDevice(resp.deviceId, resp.name, resp.secret, host, port, info.kind)
        app.settings.upsertPaired(d)
        _paired.value = app.settings.pairedDevices
        return d
    }

    fun unpair(deviceId: String) {
        if (_current.value.id == deviceId) {
            _current.value.deactivate()
            _current.value = local
        }
        remotes.remove(deviceId)?.deactivate()
        app.settings.removePaired(deviceId)
        _paired.value = app.settings.pairedDevices
    }
}
