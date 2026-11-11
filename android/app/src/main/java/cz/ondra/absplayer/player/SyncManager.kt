package cz.ondra.absplayer.player

import android.os.SystemClock
import android.util.Log
import cz.ondra.absplayer.App
import cz.ondra.absplayer.data.ApiException
import cz.ondra.absplayer.data.ProgressEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.abs

/**
 * Keeps the server's position for the open book correct.
 *
 * The ABS server stores whatever position a client syncs last, without comparing times. The
 * official app can therefore resume from an old position after another device moved on, and then
 * overwrite the newer one. Rules used here:
 *
 *  1. Before playback (re)starts, read the server's position. If it differs from what we last
 *     wrote, another device moved it; take it over (unless we have unsynced local listening).
 *  2. While paused, follow `user_item_progress_updated` events from other sessions immediately.
 *  3. Sync every 10 s while playing and right away on pause, seek, stop and book end.
 *  4. When a sync fails (no signal), keep the position locally and retry; it wins when the book
 *     is opened again if it is newer than the server's.
 */
class SyncManager(
    private val app: App,
    private val player: ChapterPlayer,
    private val scope: CoroutineScope,
    private val onInfo: (String) -> Unit,
) {
    private val api get() = app.api
    private val settings get() = app.settings

    private var sessionId: String? = null
    var itemId: String? = null
        private set

    /** Position the server holds as far as we know (what we wrote or adopted). */
    private var serverPosSec: Double? = null
    /** We have a position the server doesn't have yet. */
    private var pendingLocal = false
    private var listenedMs = 0L
    private var lastTickAt = SystemClock.elapsedRealtime()
    private var lastSyncAt = 0L
    private var lastLocalSaveAt = 0L
    private var adoptingRemote = false
    private var seekSyncJob: Job? = null
    private var lastOtherDevice: String? = null
    private val mutex = Mutex()

    private fun posSec(): Double = player.globalMs() / 1000.0
    private fun durSec(): Double = player.durationMs() / 1000.0

    /** Opens a playback session. Returns the book and the position to start at (seconds). */
    suspend fun open(id: String, explicitStartSec: Double?): Pair<LoadedBook, Double> {
        closeCurrent()
        val s = api.startPlay(id)
        val book = LoadedBook.from(s, api)
        val server = runCatching { api.progress(id) }.getOrNull()
        val serverTime = server?.currentTime ?: s.currentTime
        val local = settings.localProgress(id)
        val start = when {
            explicitStartSec != null -> explicitStartSec
            local != null && !local.synced && local.updatedAt > (server?.lastUpdate ?: 0L) -> local.positionSec
            server?.isFinished == true -> 0.0
            else -> serverTime
        }.coerceIn(0.0, maxOf(0.0, book.durationSec - 1))

        sessionId = s.id
        itemId = id
        serverPosSec = serverTime
        pendingLocal = abs(start - serverTime) > 1.0
        listenedMs = 0
        lastSyncAt = SystemClock.elapsedRealtime()
        settings.lastItemId = id
        return book to start
    }

    /** Rule 1: called just before playback starts or resumes. */
    suspend fun beforePlay() {
        val id = itemId ?: return
        if (pendingLocal) return
        val p = withTimeoutOrNull(2_500) { runCatching { api.progress(id) }.getOrNull() } ?: return
        val known = serverPosSec ?: posSec()
        if (abs(p.currentTime - known) > 3.0 && abs(p.currentTime - posSec()) > 3.0) {
            adopt(p.currentTime, lastOtherDevice)
        }
    }

    /** Rule 2. */
    fun onRemoteProgress(ev: ProgressEvent) {
        if (ev.libraryItemId != itemId) return
        if (ev.sessionId != null && ev.sessionId == sessionId) return
        lastOtherDevice = ev.deviceDescription
        if (player.isPlaying || player.isLoadingBook || pendingLocal) return
        if (abs(ev.currentTime - posSec()) > 2.0) adopt(ev.currentTime, ev.deviceDescription)
        else serverPosSec = ev.currentTime
    }

    private fun adopt(sec: Double, device: String?) {
        adoptingRemote = true
        try {
            player.seekToBook((sec * 1000).toLong())
        } finally {
            adoptingRemote = false
        }
        serverPosSec = sec
        itemId?.let { settings.saveLocalProgress(it, sec, synced = true) }
        onInfo("Pozice převzata z: " + (device ?: "jiného zařízení"))
    }

    fun onUserSeek() {
        if (adoptingRemote || itemId == null || player.isLoadingBook) return
        pendingLocal = true
        seekSyncJob?.cancel()
        seekSyncJob = scope.launch {
            delay(1_500)
            syncNow()
        }
    }

    fun onPlayingChanged(playing: Boolean) {
        lastTickAt = SystemClock.elapsedRealtime()
        if (!playing && itemId != null) scope.launch { syncNow() }
    }

    /** Called every 500 ms. */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (player.isPlaying) listenedMs += (now - lastTickAt).coerceIn(0, 2_000)
        lastTickAt = now
        val id = itemId ?: return
        if (player.isPlaying) {
            pendingLocal = true
            if (now - lastLocalSaveAt > 5_000) {
                lastLocalSaveAt = now
                settings.saveLocalProgress(id, posSec(), synced = false)
            }
        }
        val interval = when {
            player.isPlaying -> 10_000L
            pendingLocal -> 30_000L
            else -> return
        }
        if (now - lastSyncAt >= interval) {
            lastSyncAt = now
            scope.launch { syncNow() }
        }
    }

    /** Rule 3/4. Returns true when the server has our position. */
    suspend fun syncNow(force: Boolean = false): Boolean = mutex.withLock {
        val id = itemId ?: return@withLock false
        var sid = sessionId ?: return@withLock false
        if (player.isLoadingBook && !force) return@withLock false
        lastSyncAt = SystemClock.elapsedRealtime()
        val pos = posSec()
        val dur = durSec()
        val listened = listenedMs
        try {
            try {
                api.syncSession(sid, pos, listened / 1000.0, dur)
            } catch (e: ApiException) {
                if (e.code != 404) throw e
                // Session expired on the server (restart, cleanup): open a new one and retry.
                sid = api.startPlay(id).id
                sessionId = sid
                api.syncSession(sid, pos, listened / 1000.0, dur)
            }
            listenedMs = (listenedMs - listened).coerceAtLeast(0)
            serverPosSec = pos
            if (!player.isPlaying) pendingLocal = false
            settings.saveLocalProgress(id, pos, synced = true)
            true
        } catch (e: IOException) {
            pendingLocal = true
            settings.saveLocalProgress(id, pos, synced = false)
            false
        } catch (e: ApiException) {
            Log.w(TAG, "Sync failed: ${e.code} ${e.message}")
            pendingLocal = true
            settings.saveLocalProgress(id, pos, synced = false)
            false
        }
    }

    fun onBookEnded() {
        val id = itemId ?: return
        scope.launch {
            syncNow()
            runCatching { api.setFinished(id, true) }
        }
    }

    /** Syncs and closes the current session (before another book is opened). */
    suspend fun closeCurrent() {
        if (sessionId == null) return
        syncNow(force = true)
        val sid = sessionId
        sessionId = null
        itemId = null
        serverPosSec = null
        pendingLocal = false
        if (sid != null) runCatching { api.closeSession(sid) }
    }

    /** Service is going away: send the last position from the app scope, don't block. */
    fun closeDetached() {
        val sid = sessionId ?: return
        val pos = posSec()
        val dur = durSec()
        val listened = listenedMs / 1000.0
        val id = itemId
        sessionId = null
        itemId = null
        id?.let { settings.saveLocalProgress(it, pos, synced = false) }
        app.appScope.launch {
            val ok = runCatching { api.syncSession(sid, pos, listened, dur) }.isSuccess
            if (ok && id != null) settings.saveLocalProgress(id, pos, synced = true)
            runCatching { api.closeSession(sid) }
        }
    }

    companion object {
        private const val TAG = "AbsSync"
    }
}
