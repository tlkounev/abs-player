package cz.ondra.absplayer.tv

import cz.ondra.absplayer.ui.MainActivity
import cz.ondra.absplayer.tr
import android.os.SystemClock
import cz.ondra.absplayer.App
import cz.ondra.absplayer.player.NowPlaying
import cz.ondra.absplayer.player.PlaybackHub
import cz.ondra.absplayer.player.PlayerEngine
import cz.ondra.absplayer.remote.Command
import cz.ondra.absplayer.remote.ErrorResponse
import cz.ondra.absplayer.remote.InfoResponse
import cz.ondra.absplayer.data.ReceiverLink
import cz.ondra.absplayer.remote.LinkCrypto
import cz.ondra.absplayer.remote.MAX_SKEW_MS
import cz.ondra.absplayer.remote.PROTOCOL_VERSION
import cz.ondra.absplayer.remote.PairFinish
import cz.ondra.absplayer.remote.PairFinishResponse
import cz.ondra.absplayer.remote.PairPayload
import cz.ondra.absplayer.remote.PairResult
import cz.ondra.absplayer.remote.PairStart
import cz.ondra.absplayer.remote.PairStartResponse
import cz.ondra.absplayer.remote.sameServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.KeyPair

/** HTTP API of the TV receiver (see remote/Protocol.kt). */
class ReceiverServer(
    private val app: App,
    private val engine: PlayerEngine,
    private val name: () -> String,
    private val code: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var httpd: NanoHTTPD? = null
    private var failedPairs = 0
    private var lockedUntil = 0L

    private class Pending(val kp: KeyPair, val clientPub: String, val expires: Long)
    private val pending = LinkedHashMap<String, Pending>()
    /** Nonces of accepted requests (a recorded request cannot be sent again). */
    private val nonces = HashMap<String, Long>()

    /** A verified request: whose key signed it, and the nonce the answer is signed with. */
    private class Signed(val link: ReceiverLink, val nonce: String)

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
        val delete = s.method == NanoHTTPD.Method.DELETE
        when {
            get && s.uri == "/info" -> ok(InfoResponse.serializer(), info())
            // a controller with the old protocol (v2)
            post && s.uri == "/pair" -> error(UPGRADE_REQUIRED, tr("peerUpdate"), "updateNeeded")
            post && s.uri == "/pair/start" -> pairStart(body(s))
            post && s.uri == "/pair/finish" -> pairFinish(body(s))
            else -> {
                val text = if (post) body(s) else ""
                val auth = verify(s, text)
                if (auth !is Signed) auth as NanoHTTPD.Response
                else try {
                    when {
                        // a StateFlow value – no need to wait for the main thread (that made taking over control slow)
                        get && s.uri == "/state" -> ok(NowPlaying.serializer(), withoutKnownChapters(PlaybackHub.state.value, s.parameters["chapters"]?.firstOrNull()), auth)
                        post && s.uri == "/cmd" -> ok(NowPlaying.serializer(), command(json.decodeFromString(Command.serializer(), text)), auth)
                        delete && s.uri == "/pair" -> {
                            app.settings.updateReceiverLinks { l -> l.filter { it.clientId != auth.link.clientId } }
                            newJson(Status.OK, "{\"ok\":true}", auth)
                        }
                        else -> error(Status.NOT_FOUND, tr("unknownRequest"), sign = auth)
                    }
                } catch (e: Exception) {
                    error(Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName, sign = auth)
                }
            }
        }
    } catch (e: Exception) {
        error(Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
    }

    private fun info() = InfoResponse(
        deviceId = app.settings.deviceId,
        name = name(),
        loggedIn = app.settings.isLoggedIn,
        server = app.settings.serverUrl,
        username = app.settings.username,
        kind = "tv",
        acceptsTokens = true,
        version = PROTOCOL_VERSION,
        time = System.currentTimeMillis(),
    )

    private var keyed: Pair<List<cz.ondra.absplayer.player.ChapterInfo>, String>? = null

    /** /state is asked every second: the chapter list goes only when the client does not have it yet. */
    @Synchronized
    private fun withoutKnownChapters(np: NowPlaying, known: String?): NowPlaying {
        val key = keyed?.takeIf { it.first === np.chapters }?.second
            ?: chaptersKey(np).also { keyed = np.chapters to it }
        return if (known == key) np.copy(chapters = emptyList(), chaptersKey = key, chaptersOmitted = true)
        else np.copy(chaptersKey = key)
    }

    private fun chaptersKey(np: NowPlaying): String =
        LinkCrypto.sha256Hex((np.itemId ?: "") + "|" + json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(cz.ondra.absplayer.player.ChapterInfo.serializer()), np.chapters,
        )).take(12)

    /** Checks the request signature: [Signed], or the refusal to send. */
    @Synchronized
    private fun verify(s: NanoHTTPD.IHTTPSession, body: String): Any {
        val h = s.headers
        if (h["x-secret"] != null && h["x-client"] == null) return error(UPGRADE_REQUIRED, tr("peerUpdate"), "updateNeeded")
        val link = app.settings.receiverLinks.find { it.clientId == h["x-client"] }
            ?: return error(Status.UNAUTHORIZED, tr("notPaired"))
        val now = System.currentTimeMillis()
        val time = h["x-time"]?.toLongOrNull()
        if (time == null || kotlin.math.abs(time - now) > MAX_SKEW_MS) {
            return newJson(Status.UNAUTHORIZED, json.encodeToString(ErrorResponse.serializer(), ErrorResponse("clock", time = now)))
        }
        val nonce = h["x-nonce"].orEmpty()
        nonces.entries.removeAll { it.value < now }
        if (nonce.length < 16 || nonce in nonces) return error(Status.UNAUTHORIZED, tr("notPaired"))
        val path = s.uri + (s.queryParameterString?.let { "?$it" } ?: "")
        val want = LinkCrypto.requestSig(LinkCrypto.unb64(link.key), s.method.name, path, time, nonce, body)
        if (!LinkCrypto.sameSig(h["x-sig"], want)) return error(Status.UNAUTHORIZED, tr("notPaired"))
        nonces[nonce] = now + 2 * MAX_SKEW_MS
        // "last used" in the list of controllers (saved at most every 10 minutes)
        if (now - link.lastSeen > 600_000) {
            app.settings.updateReceiverLinks { l -> l.map { if (it.clientId == link.clientId) it.copy(lastSeen = now) else it } }
        }
        return Signed(link, nonce)
    }

    @Synchronized
    private fun pairStart(text: String): NanoHTTPD.Response {
        val req = json.decodeFromString(PairStart.serializer(), text)
        val now = System.currentTimeMillis()
        pending.entries.removeAll { it.value.expires < now }
        while (pending.size >= 16) pending.remove(pending.keys.first())
        val kp = LinkCrypto.newKeyPair()
        LinkCrypto.sharedSecret(kp, req.clientPub) // validates the key
        val pairId = LinkCrypto.randomHex(12)
        pending[pairId] = Pending(kp, req.clientPub, now + 120_000)
        return ok(PairStartResponse.serializer(), PairStartResponse(pairId, LinkCrypto.publicRaw(kp)))
    }

    @Synchronized
    private fun pairFinish(text: String): NanoHTTPD.Response {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) return error(Status.FORBIDDEN, tr("tooManyTries"))
        val req = json.decodeFromString(PairFinish.serializer(), text)
        val p = pending.remove(req.pairId)
        if (p == null || p.expires < System.currentTimeMillis()) return error(Status.BAD_REQUEST, tr("pairExpired"), "expired")
        val deviceId = app.settings.deviceId
        val keys = LinkCrypto.pairKeys(LinkCrypto.sharedSecret(p.kp, p.clientPub), req.pairId, p.clientPub, LinkCrypto.publicRaw(p.kp), deviceId)
        if (!LinkCrypto.sameSig(req.proof, LinkCrypto.proof(keys, "client", code))) {
            failedPairs++
            if (failedPairs >= 5) {
                failedPairs = 0
                lockedUntil = now + 60_000
            }
            return error(Status.FORBIDDEN, tr("badCode"))
        }
        failedPairs = 0
        val pay = json.decodeFromString(PairPayload.serializer(), LinkCrypto.open(keys.enc, "client", req.box))
        val st = app.settings
        val sameAccount = st.isLoggedIn && sameServer(st.serverUrl, pay.server) && st.username == pay.username
        if (pay.accessToken == null && !sameAccount) return error(Status.BAD_REQUEST, tr("tvNeedsPassword"), "needPassword")
        val clientId = LinkCrypto.randomHex(8)
        onMain {
            if (pay.accessToken != null) {
                st.serverUrl = pay.server
                st.username = pay.username
                st.userId = pay.userId
                st.accessToken = pay.accessToken
                st.refreshToken = pay.refreshToken
            }
            // another account: the controllers of the previous one lose access
            st.updateReceiverLinks { l ->
                (if (sameAccount) l else emptyList()) +
                    ReceiverLink(clientId, LinkCrypto.b64(keys.link), pay.name, System.currentTimeMillis(), kind = pay.kind,
                        deviceId = pay.deviceId)
            }
            app.onCredentialsChanged()
        }
        val result = json.encodeToString(PairResult.serializer(), PairResult(clientId, deviceId, name()))
        return ok(
            PairFinishResponse.serializer(),
            PairFinishResponse(LinkCrypto.proof(keys, "receiver", code), LinkCrypto.seal(keys.enc, "receiver", result)),
        )
    }

    private fun command(cmd: Command): NowPlaying = onMain {
        val v = cmd.value
        when (cmd.action) {
            "play" -> { engine.play(); MainActivity.bringToFront(app) }
            "pause" -> engine.pauseNow() // the server sync must not hold up a handoff to another device
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
            "playItem" -> cmd.itemId?.let { engine.playItem(it, v); MainActivity.bringToFront(app) }
            "stop" -> engine.stop()
            else -> throw IOException(tr("unknownCmd", "t" to cmd.action))
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

    private fun newJson(status: NanoHTTPD.Response.IStatus, body: String, sign: Signed? = null): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body).also { r ->
            if (sign != null) r.addHeader("X-Sig", LinkCrypto.responseSig(LinkCrypto.unb64(sign.link.key), sign.nonce, status.requestStatus, body))
        }

    private fun <T> ok(serializer: KSerializer<T>, value: T, sign: Signed? = null): NanoHTTPD.Response =
        newJson(Status.OK, json.encodeToString(serializer, value), sign)

    private fun error(status: NanoHTTPD.Response.IStatus, message: String, code: String? = null, sign: Signed? = null): NanoHTTPD.Response =
        newJson(status, json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message, code)), sign)
}

private typealias Status = NanoHTTPD.Response.Status

/** 426: the other side speaks an older protocol (NanoHTTPD has no constant for it). */
private object UPGRADE_REQUIRED : NanoHTTPD.Response.IStatus {
    override fun getDescription() = "426 Upgrade Required"
    override fun getRequestStatus() = 426
}
