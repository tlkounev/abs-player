package cz.ondra.absplayer.player

import cz.ondra.absplayer.data.LocalProgress
import cz.ondra.absplayer.data.MediaProgress
import kotlin.math.abs

/**
 * The decisions of [SyncManager] as pure functions (no Android, no network), so they are unit-tested.
 * See SyncManager for the rules they implement.
 */
object SyncRules {
    /** Differences up to this many seconds are the same position (seek granularity, rounding). */
    const val SAME_POS_BEFORE_PLAY = 3.0
    const val SAME_POS_REMOTE = 2.0
    const val SAME_POS_START = 1.0

    /**
     * Where a book starts when opened: an explicit start wins; then unsynced local listening newer than the
     * server's position (no signal last time); a finished book from the beginning; else the server.
     */
    fun startPosition(
        explicitSec: Double?,
        local: LocalProgress?,
        server: MediaProgress?,
        sessionTimeSec: Double,
        durationSec: Double,
    ): Double = when {
        explicitSec != null -> explicitSec
        local != null && !local.synced && local.updatedAt > (server?.lastUpdate ?: 0L) -> local.positionSec
        server?.isFinished == true -> 0.0
        else -> server?.currentTime ?: sessionTimeSec
    }.coerceIn(0.0, maxOf(0.0, durationSec - 1))

    /** The start differs from the server: we hold a position the server does not have yet. */
    fun pendingAfterOpen(startSec: Double, serverSec: Double): Boolean = abs(startSec - serverSec) > SAME_POS_START

    /**
     * Rule 1, before play: take the server's position over when another device moved it – it differs both
     * from what we last wrote / adopted and from where we are. Never with unsynced local listening.
     */
    fun adoptBeforePlay(serverSec: Double, knownSec: Double?, currentSec: Double, pendingLocal: Boolean): Boolean =
        !pendingLocal &&
            abs(serverSec - (knownSec ?: currentSec)) > SAME_POS_BEFORE_PLAY &&
            abs(serverSec - currentSec) > SAME_POS_BEFORE_PLAY

    enum class Remote { IGNORE, ADOPT, NOTE }

    /**
     * Rule 2, a progress event from the server: our own session or another book → ignore; while playing,
     * loading or with unsynced local listening → ignore (we will write ours); a different position → adopt;
     * the same → only note it as the server's.
     */
    fun onRemote(
        eventItemId: String, eventSessionId: String?, eventSec: Double,
        itemId: String?, sessionId: String?, currentSec: Double,
        busy: Boolean, pendingLocal: Boolean,
    ): Remote = when {
        eventItemId != itemId -> Remote.IGNORE
        eventSessionId != null && eventSessionId == sessionId -> Remote.IGNORE
        busy || pendingLocal -> Remote.IGNORE
        abs(eventSec - currentSec) > SAME_POS_REMOTE -> Remote.ADOPT
        else -> Remote.NOTE
    }

    /** Rule 3/4: how often to sync (ms), or null = not now. */
    fun syncInterval(playing: Boolean, pendingLocal: Boolean): Long? = when {
        playing -> 10_000L
        pendingLocal -> 30_000L
        else -> null
    }
}
