package cz.ondra.absplayer.data

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.client.SocketOptionBuilder
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.URI

/**
 * Live connection to the ABS server. The server pushes `user_item_progress_updated` whenever any
 * client of this user syncs progress, which lets a paused player follow another device at once.
 */
class SocketManager(
    private val settings: Settings,
    private val api: AbsApi,
    private val ioScope: CoroutineScope,
) {
    private var socket: Socket? = null
    private val _events = MutableSharedFlow<ProgressEvent>(extraBufferCapacity = 64)
    val progressEvents: SharedFlow<ProgressEvent> = _events

    @Synchronized
    fun connect() {
        disconnect()
        val server = settings.serverUrl ?: return
        val url = server.toHttpUrlOrNull() ?: return
        val basePath = url.encodedPath.trimEnd('/')
        val origin = server.removeSuffix(basePath).trimEnd('/')
        val opts = SocketOptionBuilder.builder()
            .setPath("$basePath/socket.io")
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionDelay(2_000)
            .setReconnectionDelayMax(30_000)
            .build()
        val s = IO.socket(URI.create(origin), opts)
        s.on(Socket.EVENT_CONNECT) { authenticate(s) }
        s.on("auth_failed") {
            // Access token probably expired: refresh and try again.
            ioScope.launch {
                val ok = runCatching { api.refreshBlocking() }.getOrDefault(false)
                if (ok) authenticate(s)
            }
        }
        s.on("user_item_progress_updated") { args -> handleProgress(args) }
        socket = s
        s.connect()
    }

    private fun authenticate(s: Socket) {
        val token = settings.accessToken ?: return
        s.emit("auth", token)
    }

    private fun handleProgress(args: Array<out Any?>) {
        try {
            val o = args.firstOrNull() as? JSONObject ?: return
            val data = o.optJSONObject("data") ?: return
            val itemId = data.optString("libraryItemId").ifBlank { return }
            _events.tryEmit(
                ProgressEvent(
                    sessionId = o.optString("sessionId").ifBlank { null },
                    deviceDescription = o.optString("deviceDescription").ifBlank { null },
                    libraryItemId = itemId,
                    episodeId = data.optString("episodeId").ifBlank { null },
                    currentTime = data.optDouble("currentTime", 0.0),
                    isFinished = data.optBoolean("isFinished", false),
                    lastUpdate = data.optLong("lastUpdate", 0),
                    progress = data.optDouble("progress", 0.0),
                )
            )
        } catch (e: Exception) {
            Log.w("AbsSocket", "Bad progress event", e)
        }
    }

    @Synchronized
    fun disconnect() {
        socket?.let {
            it.off()
            it.disconnect()
        }
        socket = null
    }
}
