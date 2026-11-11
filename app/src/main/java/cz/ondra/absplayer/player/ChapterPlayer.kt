package cz.ondra.absplayer.player

import android.os.Bundle
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/**
 * The player that media controllers (Android Auto, notification, phone UI, TV remote) see.
 *
 * Internally an [ExoPlayer] plays the book's audio files one after another. Outside, the book is
 * presented as a playlist of its CHAPTERS: position and duration are those of the current chapter,
 * next/previous jump between chapters, and Android Auto shows the chapter list as the queue.
 *
 * Loading a book and resuming playback go through [Host], which is where server sync happens.
 */
class ChapterPlayer(
    private val exo: ExoPlayer,
    private val scope: CoroutineScope,
    private val host: Host,
) : SimpleBasePlayer(exo.applicationLooper) {

    interface Host {
        /** Opens the book on the server and calls [attach]. [startMs] is a book position or null. */
        suspend fun loadBook(itemId: String, startMs: Long?)
        /** Runs before playback (re)starts; may move the position to a newer one from the server. */
        suspend fun beforePlay()
        /** The user moved the position. */
        fun onUserSeek()
        fun onTick()
    }

    var book: LoadedBook? = null
        private set
    var isLoadingBook = false
        private set

    private var playlist: List<SimpleBasePlayer.MediaItemData> = emptyList()
    private var chStart = LongArray(0)
    private var chEnd = LongArray(0)
    private var trackStart = LongArray(0)
    private var lastChapter = -1
    private var pendingAutoTransition = false
    private var playAfterLoad = false

    private val handler = Handler(exo.applicationLooper)
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                invalidateState()
            }
        })
        handler.postDelayed(ticker, TICK_MS)
    }

    // ------------------------------------------------------------------ book handling

    fun attach(b: LoadedBook, startMs: Long) {
        book = b
        trackStart = LongArray(b.tracks.size) { (b.tracks[it].startSec * 1000).toLong() }
        chStart = LongArray(b.chapters.size) { (b.chapters[it].start * 1000).toLong() }
        chEnd = LongArray(b.chapters.size) { i ->
            val end = (b.chapters[i].end * 1000).toLong()
            if (i + 1 < b.chapters.size) maxOf(end, chStart[i] + 1) else maxOf(end, (b.durationSec * 1000).toLong())
        }
        playlist = b.chapters.indices.map { i ->
            SimpleBasePlayer.MediaItemData.Builder("${b.itemId}#$i")
                .setMediaItem(chapterMediaItem(b, i))
                .setDurationUs((chEnd[i] - chStart[i]) * 1000)
                .setIsSeekable(true)
                .build()
        }
        val target = startMs.coerceIn(0, maxOf(0, durationMs() - 1000))
        val (ti, off) = locate(target)
        exo.setMediaItems(
            b.tracks.mapIndexed { i, t -> MediaItem.Builder().setUri(t.url).setMediaId("track$i").build() },
            ti, off,
        )
        exo.prepare()
        exo.playWhenReady = playAfterLoad
        lastChapter = chapterAt(target)
        isLoadingBook = false
        invalidateState()
    }

    fun detach() {
        book = null
        playlist = emptyList()
        exo.stop()
        exo.clearMediaItems()
        invalidateState()
    }

    private fun chapterMediaItem(b: LoadedBook, i: Int): MediaItem {
        val c = b.chapters[i]
        val chTitle = c.title.ifBlank { "Kapitola ${i + 1}" }
        val extras = Bundle().apply { putString("itemId", b.itemId) }
        return MediaItem.Builder()
            .setMediaId("book:${b.itemId}#$i")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(chTitle)
                    .setDisplayTitle(chTitle)
                    .setArtist(b.title)
                    .setSubtitle(b.title)
                    .setAlbumTitle(b.title)
                    .setAlbumArtist(b.author)
                    .setTrackNumber(i + 1)
                    .setTotalTrackCount(b.chapters.size)
                    .setArtworkUri(ArtworkProvider.uriFor(b.itemId))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    // ------------------------------------------------------------------ positions (book time)

    fun durationMs(): Long = ((book?.durationSec ?: 0.0) * 1000).toLong()

    fun globalMs(): Long {
        if (book == null) return 0
        val i = exo.currentMediaItemIndex
        if (i !in trackStart.indices) return 0
        return trackStart[i] + exo.currentPosition
    }

    private fun bufferedGlobalMs(): Long {
        if (book == null) return 0
        val i = exo.currentMediaItemIndex
        if (i !in trackStart.indices) return 0
        return trackStart[i] + exo.bufferedPosition
    }

    fun chapterAt(ms: Long): Int {
        if (chStart.isEmpty()) return 0
        var lo = 0
        var hi = chStart.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chStart[mid] <= ms) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun chapterStartMs(i: Int): Long = chStart.getOrElse(i) { 0 }

    private fun locate(ms: Long): Pair<Int, Long> {
        if (trackStart.isEmpty()) return 0 to ms
        var i = trackStart.indexOfLast { it <= ms }
        if (i < 0) i = 0
        return i to (ms - trackStart[i])
    }

    /** Seek to an absolute book position (goes through [handleSeek] so listeners are informed). */
    fun seekToBook(ms: Long) {
        if (book == null) return
        val target = ms.coerceIn(0, maxOf(0, durationMs() - 500))
        val c = chapterAt(target)
        seekTo(c, target - chStart[c])
    }

    fun skipBy(deltaMs: Long) = seekToBook(globalMs() + deltaMs)

    private fun tick() {
        if (book != null) {
            val c = chapterAt(globalMs())
            if (c != lastChapter) {
                lastChapter = c
                pendingAutoTransition = true
                invalidateState()
            }
        }
        host.onTick()
    }

    // ------------------------------------------------------------------ SimpleBasePlayer

    override fun getState(): SimpleBasePlayer.State {
        val b = book
        val hasBook = b != null && playlist.isNotEmpty()
        // SimpleBasePlayer validates: empty playlist => IDLE/ENDED, error => IDLE, loading => not IDLE/ENDED.
        val ps = when {
            !hasBook -> Player.STATE_IDLE
            isLoadingBook -> Player.STATE_BUFFERING
            else -> exo.playbackState
        }
        val builder = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(
                if (isLoadingBook) playAfterLoad else exo.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackParameters(exo.playbackParameters)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .setAudioAttributes(exo.audioAttributes)
            .setPlaybackState(ps)
            .setIsLoading(ps != Player.STATE_IDLE && ps != Player.STATE_ENDED && exo.isLoading)
            .setPlayerError(if (ps == Player.STATE_IDLE) exo.playerError else null)
            .setPlaybackSuppressionReason(exo.playbackSuppressionReason)
        if (!hasBook) {
            return builder.setPlaylist(emptyList()).build()
        }
        val c = chapterAt(globalMs())
        lastChapter = c
        val start = chStart[c]
        val len = chEnd[c] - start
        builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(c)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier { (globalMs() - start).coerceIn(0, len) })
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier { (bufferedGlobalMs() - start).coerceIn(0, len) })
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier { exo.totalBufferedDuration })
        if (pendingAutoTransition) {
            pendingAutoTransition = false
            builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, (globalMs() - start).coerceIn(0, len))
        }
        return builder.build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val item = mediaItems.getOrNull(if (startIndex in mediaItems.indices) startIndex else 0)
            ?: return Futures.immediateVoidFuture()
        val itemId = itemIdOf(item.mediaId) ?: return Futures.immediateVoidFuture()
        // A chapter of the book that is already loaded: just seek to it.
        val chapter = item.mediaId.substringAfter('#', "").toIntOrNull()
        if (book?.itemId == itemId && chapter != null && chapter in chStart.indices) {
            val (ti, off) = locate(chStart[chapter])
            exo.seekTo(ti, off)
            host.onUserSeek()
            return Futures.immediateVoidFuture()
        }
        playAfterLoad = exo.playWhenReady
        isLoadingBook = true
        exo.pause()
        val start = if (startPositionMs == C.TIME_UNSET) null else startPositionMs
        return scope.future {
            try {
                host.loadBook(itemId, start)
            } finally {
                isLoadingBook = false
                invalidateState()
            }
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (isLoadingBook) {
            playAfterLoad = playWhenReady
            return Futures.immediateVoidFuture()
        }
        if (!playWhenReady) {
            exo.pause()
            return Futures.immediateVoidFuture()
        }
        if (book == null) return Futures.immediateVoidFuture()
        return scope.future {
            runCatching { host.beforePlay() }
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0, 0)
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            exo.play()
        }
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (book != null) exo.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        exo.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(ticker)
        exo.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        exo.playbackParameters = playbackParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (book == null || chStart.isEmpty()) return Futures.immediateVoidFuture()
        val idx = if (mediaItemIndex in chStart.indices) mediaItemIndex else chapterAt(globalMs())
        val within = if (positionMs == C.TIME_UNSET) 0 else positionMs
        val target = (chStart[idx] + within).coerceIn(0, maxOf(0, durationMs() - 500))
        val (ti, off) = locate(target)
        exo.seekTo(ti, off)
        lastChapter = chapterAt(target)
        host.onUserSeek()
        return Futures.immediateVoidFuture()
    }

    companion object {
        const val TICK_MS = 500L
        const val SKIP_MS = 30_000L

        fun itemIdOf(mediaId: String): String? =
            if (mediaId.startsWith("book:")) mediaId.removePrefix("book:").substringBefore('#') else null

        fun bookMediaItem(itemId: String, title: String? = null): MediaItem =
            MediaItem.Builder()
                .setMediaId("book:$itemId")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtworkUri(ArtworkProvider.uriFor(itemId))
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()

        private val COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
