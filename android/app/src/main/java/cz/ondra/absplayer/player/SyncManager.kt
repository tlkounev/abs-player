package cz.ondra.absplayer.player

import cz.ondra.absplayer.tr
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

/**
 * Keeps the server's position for the open book correct.
 *
 * The ABS server stores whatever position a client syncs last, without comparing times. The
 * official app can therefore resume from an old position after another device moved on, and then
 * overwrite the newer one. Rules used here:
 *
 *  1. Before playback (re)starts and when the app comes back to the front, read the server's position.
 *     If another device moved it after our last own change, take it over – also when our last sync
 *     failed (a TV going to standby loses the network while it saves the pause).
 *  2. While paused, follow `user_item_progress_updated` events from other sessions immediately.
 *  3. Sync every 10 s while playing and right away on pause, seek, stop and book end.
 *  4. When a sync fails (no signal), keep the position locally and retry. Before the retry writes it,
 *     the server is read again (rule 1), so a newer position from another device is never overwritten.
 *     When the server cannot be read before play, the first sync while playing does that check.
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
    /** Wall-clock ms of our last own position change (listening, seek, adoption). */
    private var ownAt = System.currentTimeMillis()
    /** Read the server before the next write: its updates after this time (ms) beat ours (rule 4). */
    private var verifyAfter: Long? = null
    /** Positions we sent without seeing the answer since the last good sync (they may be on the server). */
    private val attempted = ArrayList<Double>()
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
        val start = SyncRules.startPosition(explicitStartSec, local, server, s.currentTime, book.durationSec)

        sessionId = s.id
        itemId = id
        serverPosSec = serverTime
        pendingLocal = SyncRules.pendingAfterOpen(start, serverTime)
        ownAt = if (pendingLocal && local != null) local.updatedAt else System.currentTimeMillis()
        verifyAfter = null
        attempted.clear()
        listenedMs = 0
        lastSyncAt = SystemClock.elapsedRealtime()
        settings.lastItemId = id
        return book to start
    }

    /** Rule 1: called just before playback starts or resumes. */
    suspend fun beforePlay() {
        checkServer(2_500)
    }

    /** Rule 1: the app came back to the front (TV woken up, phone unlocked) – nothing plays, look at the server. */
    fun refreshOnResume() {
        if (itemId == null || player.isPlaying || player.isLoadingBook) return
        scope.launch { mutex.withLock { if (!player.isPlaying) checkServer(8_000) } }
    }

    private val pendingSince: Long? get() = if (pendingLocal) verifyAfter ?: ownAt else null

    /**
     * Reads the server and takes its position over when another device moved it. False = the server could not
     * be read; then the next write checks first (verifyAfter).
     */
    private suspend fun checkServer(timeoutMs: Long): Boolean {
        val id = itemId ?: return true
        val result = withTimeoutOrNull(timeoutMs) { runCatching { api.progress(id) } }
        if (result == null || result.isFailure) {
            if (verifyAfter == null) verifyAfter = ownAt
            return false
        }
        val p = result.getOrNull() ?: run { verifyAfter = null; return true } // no progress on the server yet
        if (id != itemId) return true
        when (SyncRules.checkServer(p.currentTime, p.lastUpdate, serverPosSec, posSec(), pendingSince, attempted)) {
            SyncRules.Check.ADOPT -> adopt(p.currentTime, lastOtherDevice)
            SyncRules.Check.SAME -> {
                serverPosSec = p.currentTime
                verifyAfter = null
                attempted.clear()
                if (!player.isPlaying && pendingLocal) {
                    pendingLocal = false
                    settings.saveLocalProgress(id, posSec(), synced = true, at = ownAt)
                }
            }
            SyncRules.Check.KEEP -> verifyAfter = null
        }
        return true
    }

    /** Rule 2. */
    fun onRemoteProgress(ev: ProgressEvent) {
        if (ev.libraryItemId != itemId) return
        if (ev.sessionId != null && ev.sessionId == sessionId) return
        lastOtherDevice = ev.deviceDescription
        when (SyncRules.onRemote(ev.libraryItemId, ev.sessionId, ev.currentTime, itemId, sessionId, posSec(),
            player.isPlaying || player.isLoadingBook, SyncRules.pendingIsNewer(pendingSince, ev.lastUpdate))) {
            SyncRules.Remote.ADOPT -> adopt(ev.currentTime, ev.deviceDescription)
            SyncRules.Remote.NOTE -> serverPosSec = ev.currentTime
            SyncRules.Remote.IGNORE -> {}
        }
    }

    private fun adopt(sec: Double, device: String?) {
        adoptingRemote = true
        try {
            player.seekToBook((sec * 1000).toLong())
        } finally {
            adoptingRemote = false
        }
        serverPosSec = sec
        pendingLocal = false
        verifyAfter = null
        attempted.clear()
        ownAt = System.currentTimeMillis()
        itemId?.let { settings.saveLocalProgress(it, sec, synced = true) }
        onInfo(tr("positionFrom", "t" to (device ?: tr("otherDevice"))))
    }

    fun onUserSeek() {
        if (adoptingRemote || itemId == null || player.isLoadingBook) return
        pendingLocal = true
        ownAt = System.currentTimeMillis()
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
            ownAt = System.currentTimeMillis()
            if (now - lastLocalSaveAt > 5_000) {
                lastLocalSaveAt = now
                settings.saveLocalProgress(id, posSec(), synced = false, at = ownAt)
            }
        }
        val interval = SyncRules.syncInterval(player.isPlaying, pendingLocal) ?: return
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
        // Rule 4: after a failed sync (or an unreadable server before play) look before writing.
        if (verifyAfter != null && !checkServer(10_000)) {
            pendingLocal = true
            settings.saveLocalProgress(id, posSec(), synced = false, at = ownAt)
            return@withLock false
        }
        if (id != itemId) return@withLock false
        if (!pendingLocal && !player.isPlaying && serverPosSec?.let { kotlin.math.abs(it - posSec()) < 0.5 } == true) {
            return@withLock true // just adopted / already there: nothing to write
        }
        val pos = posSec()
        val dur = durSec()
        val listened = listenedMs
        attempted.add(pos)
        if (attempted.size > 20) attempted.removeAt(0)
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
            attempted.clear()
            verifyAfter = null
            if (!player.isPlaying) pendingLocal = false
            settings.saveLocalProgress(id, pos, synced = true, at = ownAt)
            true
        } catch (e: IOException) {
            failed(id, pos)
        } catch (e: ApiException) {
            Log.w(TAG, "Sync failed: ${e.code} ${e.message}")
            failed(id, pos)
        }
    }

    private fun failed(id: String, pos: Double): Boolean {
        pendingLocal = true
        if (verifyAfter == null) verifyAfter = ownAt
        settings.saveLocalProgress(id, pos, synced = false, at = ownAt)
        return false
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
        val at = ownAt
        val check = verifyAfter != null
        id?.let { settings.saveLocalProgress(it, pos, synced = false, at = at) }
        app.appScope.launch {
            if (check && id != null) {
                // Unsynced: write only when no other device moved the book since (rule 4).
                val p = runCatching { api.progress(id) }.getOrElse { null }
                if (p != null && p.lastUpdate > at && kotlin.math.abs(p.currentTime - pos) > SyncRules.SAME_POS_BEFORE_PLAY) {
                    runCatching { api.closeSession(sid) }
                    return@launch
                }
            }
            val ok = runCatching { api.syncSession(sid, pos, listened, dur) }.isSuccess
            if (ok && id != null) settings.saveLocalProgress(id, pos, synced = true, at = at)
            runCatching { api.closeSession(sid) }
        }
    }

    companion object {
        private const val TAG = "AbsSync"
    }
}
