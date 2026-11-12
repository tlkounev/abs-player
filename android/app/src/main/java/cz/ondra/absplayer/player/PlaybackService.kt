package cz.ondra.absplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import cz.ondra.absplayer.App
import cz.ondra.absplayer.ui.MainActivity
import kotlinx.coroutines.guava.future

/** Phone playback service: media notification, Bluetooth/headset controls and Android Auto. */
class PlaybackService : MediaLibraryService() {

    private lateinit var engine: PlayerEngine
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        engine = PlayerEngine(this, app)
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        session = MediaLibrarySession.Builder(this, engine.player, LibraryCallback(app, engine))
            .setSessionActivity(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .build()
        session.setMediaButtonPreferences(engine.mediaButtons())
        // Speed button shows the current speed: refresh it whenever the speed changes.
        engine.exo.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                session.setMediaButtonPreferences(engine.mediaButtons())
            }
        })
        PlaybackHub.attach(engine)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = engine.player
        if (!p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        PlaybackHub.detach(engine)
        session.release()
        engine.release()
        super.onDestroy()
    }
}

class LibraryCallback(
    private val app: App,
    private val engine: PlayerEngine,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val tree = BrowseTree(app)
    private val scope get() = engine.scope

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val base = super.onConnect(session, controller)
        val commands = base.availableSessionCommands.buildUpon().apply {
            PlayerEngine.ALL_COMMANDS.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
        }.build()
        return MediaSession.ConnectionResult.accept(commands, base.availablePlayerCommands)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        engine.handleCustomCommand(customCommand.customAction, args)
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val extras = Bundle().apply {
            putBoolean(BrowseTree.STYLE_SUPPORTED, true)
            putBoolean(BrowseTree.SEARCH_SUPPORTED, true)
            putInt(BrowseTree.STYLE_BROWSABLE, BrowseTree.STYLE_GRID)
            putInt(BrowseTree.STYLE_PLAYABLE, BrowseTree.STYLE_GRID)
        }
        val rootParams = MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()
        return Futures.immediateFuture(LibraryResult.ofItem(tree.root(), rootParams))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        if (!app.settings.isLoggedIn) {
            LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        } else {
            try {
                LibraryResult.ofItemList(ImmutableList.copyOf(tree.children(parentId, page, pageSize)), params)
            } catch (e: Exception) {
                LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_IO)
            }
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        try {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE)
        } catch (e: Exception) {
            LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_IO)
        }
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        scope.future {
            val count = runCatching { tree.search(query).size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        try {
            val all = tree.search(query)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.drop(page * pageSize).take(pageSize)), params)
        } catch (e: Exception) {
            LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_IO)
        }
    }

    /** Items from Android Auto / Assistant carry only a mediaId; the player resolves them. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

    /** "Resume" button in the car / on the lock screen after the app was closed. */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val last = app.settings.lastItemId
            ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(listOf(ChapterPlayer.bookMediaItem(last)), 0, C.TIME_UNSET)
        )
    }
}
