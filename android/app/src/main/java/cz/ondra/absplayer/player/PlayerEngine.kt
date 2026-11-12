package cz.ondra.absplayer.player

import cz.ondra.absplayer.tr
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import cz.ondra.absplayer.App
import cz.ondra.absplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * One playback engine per device: ExoPlayer + chapter facade + server sync + sleep timer.
 * Owned by [PlaybackService] on the phone and by the TV receiver service on Google TV.
 * All methods must be called on the main thread.
 */
class PlayerEngine(context: Context, private val app: App) : ChapterPlayer.Host {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(app.api.http)))
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLoadControl(
            // Large forward buffer: keeps playing through tunnels and dead spots while driving.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(60_000, 15 * 60_000, 2_500, 5_000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build()

    val player = ChapterPlayer(exo, scope, this)
    val sync = SyncManager(app, player, scope) { showInfo(it) }

    private var info: String? = null
    private var infoUntil = 0L
    private var error: String? = null
    private var sleepAt: Long? = null
    private var sleepEndOfChapter = false
    private var sleepChapter = -1
    private var retries = 0
    private var retryJob: Job? = null
    private var volume = app.settings.volume.coerceIn(0f, 1f)
    private var muted = false
    private var chapterInfos: List<ChapterInfo> = emptyList()
    private var chapterInfosFor: LoadedBook? = null

    init {
        exo.playbackParameters = PlaybackParameters(app.settings.speed)
        exo.volume = volume
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    retries = 0
                    error = null
                }
                sync.onPlayingChanged(isPlaying)
                publish()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player.book != null) sync.onBookEnded()
                publish()
            }

            override fun onPlayerError(e: PlaybackException) {
                // Network hiccup (car, tunnel): retry a few times from the same position.
                if (retries < 5 && player.book != null) {
                    retries++
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(3_000L * retries)
                        exo.prepare()
                    }
                    showInfo(tr("reconnecting"))
                } else {
                    error = tr("playbackError", "t" to e.errorCodeName)
                }
                publish()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                app.settings.speed = playbackParameters.speed
                publish()
            }
        })
        scope.launch {
            app.socket.progressEvents.collect { sync.onRemoteProgress(it) }
        }
    }

    // ------------------------------------------------------------------ ChapterPlayer.Host

    override suspend fun loadBook(itemId: String, startMs: Long?) {
        error = null
        publish()
        try {
            val (book, startSec) = sync.open(itemId, startMs?.let { it / 1000.0 })
            player.attach(book, (startSec * 1000).toLong())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr("openFailed", "t" to (e.message ?: e.javaClass.simpleName))
            player.detach()
        }
        publish()
    }

    override suspend fun beforePlay() = sync.beforePlay()

    override fun onUserSeek() {
        sync.onUserSeek()
        publish()
    }

    override fun onTick() {
        sync.tick()
        checkSleep()
        publish()
    }

    // ------------------------------------------------------------------ controls

    fun playItem(itemId: String, startSec: Double? = null) {
        if (player.book?.itemId == itemId && !player.isLoadingBook) {
            if (startSec != null) player.seekToBook((startSec * 1000).toLong())
            player.play()
            return
        }
        player.setMediaItem(ChapterPlayer.bookMediaItem(itemId), startSec?.let { (it * 1000).toLong() } ?: C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun toggle() = if (player.playWhenReady) player.pause() else player.play()
    fun seekTo(sec: Double) = player.seekToBook((sec * 1000).toLong())
    fun skip(deltaSec: Double) = player.skipBy((deltaSec * 1000).toLong())
    fun chapter(index: Int) = player.seekTo(index, 0)
    fun nextChapter() = player.seekToNextMediaItem()
    fun prevChapter() = player.seekToPrevious()
    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed.coerceIn(0.5f, 3.0f))

    fun cycleSpeed() {
        val cur = exo.playbackParameters.speed
        val next = SPEEDS.firstOrNull { it > cur + 0.01f } ?: SPEEDS.first()
        setSpeed(next)
        showInfo(tr("speedT", "t" to formatSpeed(next)))
    }

    /** In-app volume 0..1 (independent of the system volume, like the Windows app). */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (volume > 0f) muted = false
        app.settings.volume = volume
        applyVolume()
    }

    fun toggleMute() {
        if (muted || volume <= 0f) {
            muted = false
            if (volume <= 0f) volume = 0.5f
        } else {
            muted = true
        }
        applyVolume()
    }

    private fun applyVolume() {
        exo.volume = if (muted) 0f else volume
        publish()
    }

    /** minutes > 0: timer; -1: end of chapter; 0: off. */
    fun setSleep(minutes: Int) {
        sleepAt = null
        sleepEndOfChapter = false
        when {
            minutes > 0 -> sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            minutes < 0 -> {
                sleepEndOfChapter = true
                sleepChapter = player.chapterAt(player.globalMs())
            }
        }
        publish()
    }

    /** Pause, push the position to the server and wait for it (used before handing off to another device). */
    suspend fun pauseAndSync(): Double {
        player.pause()
        sync.syncNow()
        return player.globalMs() / 1000.0
    }

    fun stop() {
        scope.launch {
            player.pause()
            sync.closeCurrent()
            player.detach()
            publish()
        }
    }

    fun release() {
        sync.closeDetached()
        player.release()
        scope.cancel()
    }

    // ------------------------------------------------------------------ custom session commands

    fun handleCustomCommand(action: String, args: Bundle) {
        when (action) {
            CMD_REWIND -> skip(-SKIP_SEC)
            CMD_FORWARD -> skip(SKIP_SEC)
            CMD_SPEED -> cycleSpeed()
            CMD_SEEK -> seekTo(args.getDouble("sec"))
            CMD_SLEEP -> setSleep(args.getInt("min"))
            CMD_VOLUME -> setVolume(args.getFloat("v"))
            CMD_MUTE -> toggleMute()
            CMD_PLAY_ITEM -> args.getString("itemId")?.let {
                playItem(it, if (args.containsKey("sec")) args.getDouble("sec") else null)
            }
        }
    }

    fun customLayout(context: Context): List<CommandButton> = listOf(
        CommandButton.Builder()
            .setDisplayName(tr("back", "s" to SKIP_SEC.toInt()))
            .setIconResId(R.drawable.ic_replay)
            .setSessionCommand(SessionCommand(CMD_REWIND, Bundle.EMPTY))
            .build(),
        CommandButton.Builder()
            .setDisplayName(tr("fwd", "s" to SKIP_SEC.toInt()))
            .setIconResId(R.drawable.ic_forward)
            .setSessionCommand(SessionCommand(CMD_FORWARD, Bundle.EMPTY))
            .build(),
        // The icon is the current speed as text (like the official app); tap = next step 0.75–2×.
        CommandButton.Builder()
            .setDisplayName(tr("speedT", "t" to formatSpeed(exo.playbackParameters.speed)))
            .setIconResId(SpeedIcons.forSpeed(exo.playbackParameters.speed))
            .setSessionCommand(SessionCommand(CMD_SPEED, Bundle.EMPTY))
            .build(),
    )

    // ------------------------------------------------------------------ state

    private fun checkSleep() {
        val at = sleepAt
        if (at != null && SystemClock.elapsedRealtime() >= at) {
            sleepAt = null
            player.pause()
            showInfo(tr("sleepStopped"))
        }
        if (sleepEndOfChapter && player.isPlaying) {
            val c = player.chapterAt(player.globalMs())
            if (c != sleepChapter) {
                sleepEndOfChapter = false
                player.pause()
                player.seekTo(c, 0)
                showInfo(tr("chapterEndPaused"))
            }
        }
    }

    private fun showInfo(text: String) {
        info = text
        infoUntil = SystemClock.elapsedRealtime() + 6_000
        publish()
    }

    fun publish() {
        val b = player.book
        if (b !== chapterInfosFor) {
            chapterInfos = b?.chapters?.mapIndexed { i, c ->
                ChapterInfo(c.title.ifBlank { tr("chapterX", "n" to i + 1) }, c.start, c.end)
            } ?: emptyList()
            chapterInfosFor = b
        }
        val now = SystemClock.elapsedRealtime()
        val sa = sleepAt
        PlaybackHub.state.value = NowPlaying(
            itemId = b?.itemId,
            title = b?.title ?: "",
            author = b?.author ?: "",
            series = b?.series ?: "",
            chapters = chapterInfos,
            durationSec = b?.durationSec ?: 0.0,
            positionSec = player.globalMs() / 1000.0,
            isPlaying = player.playWhenReady,
            isBuffering = exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady,
            isLoading = player.isLoadingBook,
            speed = exo.playbackParameters.speed,
            volume = volume,
            muted = muted || volume <= 0f,
            sleepSec = when {
                sleepEndOfChapter -> -1
                sa != null -> ((sa - now) / 1000).toInt().coerceAtLeast(0)
                else -> null
            },
            info = info?.takeIf { now < infoUntil },
            error = error,
        )
    }

    companion object {
        /** Skip length of ↺ / ↻ (same as the Windows app). */
        const val SKIP_SEC = 10.0
        const val CMD_REWIND = "abs.REWIND"
        const val CMD_FORWARD = "abs.FORWARD"
        const val CMD_SPEED = "abs.SPEED"
        const val CMD_SEEK = "abs.SEEK"
        const val CMD_SLEEP = "abs.SLEEP"
        const val CMD_PLAY_ITEM = "abs.PLAY_ITEM"
        const val CMD_VOLUME = "abs.VOLUME"
        const val CMD_MUTE = "abs.MUTE"
        val ALL_COMMANDS = listOf(CMD_REWIND, CMD_FORWARD, CMD_SPEED, CMD_SEEK, CMD_SLEEP, CMD_PLAY_ITEM, CMD_VOLUME, CMD_MUTE)

        /** Same steps as the Windows app: tap = next step. */
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        fun formatSpeed(s: Float): String =
            (if (s == s.toInt().toFloat()) "${s.toInt()}.0" else "%.2f".format(java.util.Locale.US, s).trimEnd('0')) + "×"
    }
}

/** Process-wide access to the running engine and its state (main thread). */
object PlaybackHub {
    val state = MutableStateFlow(NowPlaying())
    var engine: PlayerEngine? = null
        private set
    private val waiting = mutableListOf<(PlayerEngine) -> Unit>()

    fun attach(e: PlayerEngine) {
        engine = e
        val w = waiting.toList()
        waiting.clear()
        w.forEach { it(e) }
        e.publish()
    }

    fun detach(e: PlayerEngine) {
        if (engine === e) {
            engine = null
            state.value = NowPlaying()
        }
    }

    fun whenReady(block: (PlayerEngine) -> Unit) {
        val e = engine
        if (e != null) block(e) else waiting.add(block)
    }
}
