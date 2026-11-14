package cz.ondra.absplayer.player

import cz.ondra.absplayer.data.LocalProgress
import cz.ondra.absplayer.data.MediaProgress
import cz.ondra.absplayer.player.SyncRules.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {
    private fun server(sec: Double, lastUpdate: Long = 1_000, finished: Boolean = false) =
        MediaProgress(libraryItemId = "li", currentTime = sec, lastUpdate = lastUpdate, isFinished = finished)

    // ---------------------------------------------------------------- opening a book

    @Test
    fun startsAtTheServerPosition() {
        assertEquals(500.0, SyncRules.startPosition(null, null, server(500.0), 0.0, 3600.0), 0.0)
    }

    @Test
    fun explicitStartWins() {
        val local = LocalProgress(900.0, 5_000, synced = false)
        assertEquals(42.0, SyncRules.startPosition(42.0, local, server(500.0), 0.0, 3600.0), 0.0)
    }

    @Test
    fun unsyncedLocalListeningNewerThanTheServerWins() {
        // listened without signal after the server's last update
        val local = LocalProgress(900.0, updatedAt = 5_000, synced = false)
        assertEquals(900.0, SyncRules.startPosition(null, local, server(500.0, lastUpdate = 1_000), 0.0, 3600.0), 0.0)
    }

    @Test
    fun olderOrSyncedLocalPositionLoses() {
        val older = LocalProgress(900.0, updatedAt = 500, synced = false)
        assertEquals(500.0, SyncRules.startPosition(null, older, server(500.0, lastUpdate = 1_000), 0.0, 3600.0), 0.0)
        val synced = LocalProgress(900.0, updatedAt = 5_000, synced = true)
        assertEquals(500.0, SyncRules.startPosition(null, synced, server(500.0, lastUpdate = 1_000), 0.0, 3600.0), 0.0)
    }

    @Test
    fun finishedBookStartsFromTheBeginning() {
        assertEquals(0.0, SyncRules.startPosition(null, null, server(3599.0, finished = true), 0.0, 3600.0), 0.0)
    }

    @Test
    fun withoutServerProgressTheSessionTimeIsUsed() {
        assertEquals(120.0, SyncRules.startPosition(null, null, null, 120.0, 3600.0), 0.0)
    }

    @Test
    fun startIsKeptInsideTheBook() {
        assertEquals(3599.0, SyncRules.startPosition(5000.0, null, null, 0.0, 3600.0), 0.0)
        assertEquals(0.0, SyncRules.startPosition(-3.0, null, null, 0.0, 3600.0), 0.0)
        assertEquals(0.0, SyncRules.startPosition(10.0, null, null, 0.0, 0.0), 0.0)
    }

    @Test
    fun pendingWhenTheStartDiffersFromTheServer() {
        assertTrue(SyncRules.pendingAfterOpen(900.0, 500.0))
        assertFalse(SyncRules.pendingAfterOpen(500.5, 500.0))
    }

    // ---------------------------------------------------------------- before play

    @Test
    fun anotherDeviceMovedThePosition() {
        // we wrote 500 and stand at 500; the server says 1200 → someone listened elsewhere
        assertTrue(SyncRules.adoptBeforePlay(1200.0, knownSec = 500.0, currentSec = 500.0, pendingLocal = false))
    }

    @Test
    fun ownPositionIsNotAdopted() {
        assertFalse(SyncRules.adoptBeforePlay(501.0, knownSec = 500.0, currentSec = 500.0, pendingLocal = false))
        // the server already has where we stand (our seek was synced)
        assertFalse(SyncRules.adoptBeforePlay(800.0, knownSec = 500.0, currentSec = 800.0, pendingLocal = false))
    }

    @Test
    fun unsyncedLocalListeningIsNeverOverwritten() {
        assertFalse(SyncRules.adoptBeforePlay(1200.0, knownSec = 500.0, currentSec = 500.0, pendingLocal = true))
    }

    @Test
    fun unknownServerPositionComparesWithTheCurrentOne() {
        assertTrue(SyncRules.adoptBeforePlay(1200.0, knownSec = null, currentSec = 500.0, pendingLocal = false))
        assertFalse(SyncRules.adoptBeforePlay(502.0, knownSec = null, currentSec = 500.0, pendingLocal = false))
    }

    // ---------------------------------------------------------------- events from other devices

    private fun remote(itemId: String = "li", session: String? = "other", sec: Double = 1200.0, busy: Boolean = false, pending: Boolean = false) =
        SyncRules.onRemote(itemId, session, sec, itemId = "li", sessionId = "mine", currentSec = 500.0, busy = busy, pendingLocal = pending)

    @Test
    fun pausedTakesAnotherDevicesPositionAtOnce() {
        assertEquals(Remote.ADOPT, remote())
    }

    @Test
    fun ownSessionAndOtherBooksAreIgnored() {
        assertEquals(Remote.IGNORE, remote(session = "mine"))
        assertEquals(Remote.IGNORE, remote(itemId = "other-book"))
    }

    @Test
    fun whilePlayingOrWithUnsyncedListeningOursWins() {
        assertEquals(Remote.IGNORE, remote(busy = true))
        assertEquals(Remote.IGNORE, remote(pending = true))
    }

    @Test
    fun samePositionIsOnlyNoted() {
        assertEquals(Remote.NOTE, remote(sec = 501.0))
    }

    // ---------------------------------------------------------------- sync interval

    @Test
    fun syncEvery10sWhilePlaying30sWithPendingElseNot() {
        assertEquals(10_000L, SyncRules.syncInterval(playing = true, pendingLocal = false))
        assertEquals(10_000L, SyncRules.syncInterval(playing = true, pendingLocal = true))
        assertEquals(30_000L, SyncRules.syncInterval(playing = false, pendingLocal = true))
        assertNull(SyncRules.syncInterval(playing = false, pendingLocal = false))
    }
}
