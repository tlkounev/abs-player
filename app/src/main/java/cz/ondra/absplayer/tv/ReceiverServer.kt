package cz.ondra.absplayer.tv

import android.os.SystemClock
import cz.ondra.absplayer.App
import cz.ondra.absplayer.player.NowPlaying
import cz.ondra.absplayer.player.PlaybackHub
import cz.ondra.absplayer.player.PlayerEngine
import cz.ondra.absplayer.remote.Command
import cz.ondra.absplayer.remote.ErrorResponse
import cz.ondra.absplayer.remote.InfoResponse
import cz.ondra.absplayer.remote.PairRequest
import cz.ondra.absplayer.remote.PairResponse
import cz.ondra.absplayer.remote.sameServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.SecureRandom

/** HTTP API of the TV receiver (see remote/Protocol.kt). */
class ReceiverServer(
    private val app: App,
    private val engine: PlayerEngine,
    private val name: String,
    private val code: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var httpd: NanoHTTPD? = null
    private var failedPairs = 0
    private var lockedUntil = 0L

    /** Starts on [first] or the next free port; returns the port or -1. */
    fun startOnFreePort(first: Int): Int {
        for (p in first until first + 10) {
            try {
                val h = Httpd(p)
                h.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                httpd = h
                return p
            } catch (e: IOException) {
                // port busy, try the next one
            }
        }
        return -1
    }

    fun stop() {
        httpd?.stop()
        httpd = null
    }

    private inner class Httpd(port: Int) : NanoHTTPD(port) {
        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = handle(session)
    }

    private fun handle(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
        val get = s.method == NanoHTTPD.Method.GET
        val post = s.method == NanoHTTPD.Method.POST
        when {
            get && s.uri == "/info" -> ok(InfoResponse.serializer(), info())
            post && s.uri == "/pair" -> pair(body(s))
            !authorized(s) -> error(NanoHTTPD.Response.Status.UNAUTHORIZED, "Zařízení není spárované")
            get && s.uri == "/state" -> ok(NowPlaying.serializer(), onMain { PlaybackHub.state.value })
            post && s.uri == "/cmd" -> ok(NowPlaying.serializer(), command(json.decodeFromString(Command.serializer(), body(s))))
            else -> error(NanoHTTPD.Response.Status.NOT_FOUND, "Neznámý požadavek")
        }
    } catch (e: Exception) {
        error(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
    }

    private fun info() = InfoResponse(
        deviceId = app.settings.deviceId,
        name = name,
        loggedIn = app.settings.isLoggedIn,
        server = app.settings.serverUrl,
        username = app.settings.username,
        kind = "tv",
        acceptsTokens = true,
    )

    private fun authorized(s: NanoHTTPD.IHTTPSession): Boolean {
        val secret = s.headers["x-secret"] ?: return false
        return secret in app.settings.receiverSecrets
    }

    @Synchronized
    private fun pair(text: String): NanoHTTPD.Response {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) {
            return error(NanoHTTPD.Response.Status.FORBIDDEN, "Příliš mnoho pokusů, zkus to za minutu")
        }
        val req = json.decodeFromString(PairRequest.serializer(), text)
        if (req.code.trim() != code) {
            failedPairs++
            if (failedPairs >= 5) {
                failedPairs = 0
                lockedUntil = now + 60_000
            }
            return error(NanoHTTPD.Response.Status.FORBIDDEN, "Špatný párovací kód")
        }
        failedPairs = 0
        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val st = app.settings
        val sameAccount = st.isLoggedIn && sameServer(st.serverUrl, req.server) && st.username == req.username
        if (req.accessToken == null && !sameAccount) {
            return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Televize není přihlášená k tomuto účtu – zadej heslo")
        }
        onMain {
            if (req.accessToken != null) {
                st.serverUrl = req.server
                st.username = req.username
                st.userId = req.userId
                st.accessToken = req.accessToken
                st.refreshToken = req.refreshToken
            }
            st.receiverSecrets = (if (sameAccount) st.receiverSecrets else emptySet()) + secret
            app.onCredentialsChanged()
        }
        return ok(PairResponse.serializer(), PairResponse(secret, app.settings.deviceId, name))
    }

    private fun command(cmd: Command): NowPlaying = onMain {
        val v = cmd.value
        when (cmd.action) {
            "play" -> engine.play()
            "pause" -> engine.pauseAndSync()
            "toggle" -> engine.toggle()
            "seek" -> if (v != null) engine.seekTo(v)
            "skip" -> if (v != null) engine.skip(v)
            "chapter" -> if (v != null) engine.chapter(v.toInt())
            "next" -> engine.nextChapter()
            "prev" -> engine.prevChapter()
            "speed" -> if (v != null) engine.setSpeed(v.toFloat())
            "sleep" -> engine.setSleep(v?.toInt() ?: 0)
            "volume" -> if (v != null) engine.setVolume(v.toFloat())
            "mute" -> engine.toggleMute()
            "playItem" -> cmd.itemId?.let { engine.playItem(it, v) }
            "stop" -> engine.stop()
            else -> throw IOException("Neznámý příkaz ${cmd.action}")
        }
        engine.publish()
        PlaybackHub.state.value
    }

    private fun body(s: NanoHTTPD.IHTTPSession): String {
        val files = HashMap<String, String>()
        s.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking {
        withTimeout(10_000) { withContext(Dispatchers.Main) { block() } }
    }

    private fun <T> ok(serializer: KSerializer<T>, value: T): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.encodeToString(serializer, value))

    private fun error(status: NanoHTTPD.Response.Status, message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)))
}
