package cz.ondra.absplayer.ui

import cz.ondra.absplayer.tr
import cz.ondra.absplayer.data.Updater
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.ondra.absplayer.App
import cz.ondra.absplayer.data.AbsApi
import cz.ondra.absplayer.data.ApiException
import cz.ondra.absplayer.data.Library
import cz.ondra.absplayer.data.LibraryItem
import cz.ondra.absplayer.data.MediaProgress
import cz.ondra.absplayer.data.Series
import cz.ondra.absplayer.player.NowPlaying
import cz.ondra.absplayer.remote.PlaybackTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface Screen {
    data object Home : Screen
    data class Book(val id: String, val title: String) : Screen
    data class SeriesBooks(val libraryId: String, val seriesId: String, val name: String) : Screen
    data object Player : Screen
}

enum class Sheet { Chapters, Devices, Pair, Sleep, Speed, Volume }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as App
    private val api get() = app.api

    val loggedIn: StateFlow<Boolean> = app.loggedIn
    val target: StateFlow<PlaybackTarget> = app.targets.current
    val playing: StateFlow<NowPlaying> = app.targets.current
        .flatMapLatest { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NowPlaying())

    // navigation
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var sheet by mutableStateOf<Sheet?>(null)

    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // home
    var tab by mutableIntStateOf(0)
    var continueItems by mutableStateOf<List<LibraryItem>>(emptyList())
    var progress by mutableStateOf<Map<String, MediaProgress>>(emptyMap())
    var libraries by mutableStateOf<List<Library>>(emptyList())
    var libraryId by mutableStateOf<String?>(null)
    var libItems by mutableStateOf<List<LibraryItem>>(emptyList())
    var libTotal by mutableIntStateOf(0)
    private var libPage = 0
    var libLoading by mutableStateOf(false)
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<LibraryItem>?>(null)
    var series by mutableStateOf<List<Series>>(emptyList())
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // detail screens
    var book by mutableStateOf<LibraryItem?>(null)
    var seriesBooks by mutableStateOf<List<LibraryItem>>(emptyList())

    private var searchJob: Job? = null
    private var continueRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            loggedIn.collect { if (it) refreshAll() }
        }
        viewModelScope.launch { Updater.check(app, manual = false) }
        viewModelScope.launch {
            app.socket.progressEvents.collect { ev ->
                val old = progress[ev.libraryItemId]
                progress = progress + (ev.libraryItemId to (old ?: MediaProgress(libraryItemId = ev.libraryItemId)).copy(
                    currentTime = ev.currentTime,
                    progress = ev.progress,
                    isFinished = ev.isFinished,
                    lastUpdate = ev.lastUpdate,
                ))
                // Move the item to the front of "Pokračovat" (debounced).
                continueRefreshJob?.cancel()
                continueRefreshJob = launch {
                    delay(3_000)
                    runCatching { continueItems = api.itemsInProgress() }
                }
            }
        }
    }

    private fun message(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: tr("serverError", "n" to e.code)
        is IOException -> tr("serverUnavailable", "t" to (e.message ?: e.javaClass.simpleName))
        else -> e.message ?: e.javaClass.simpleName
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshing = true
            error = null
            try {
                progress = api.me().mediaProgress.filter { it.episodeId == null }.associateBy { it.libraryItemId }
                continueItems = api.itemsInProgress()
                libraries = api.libraries().filter { it.mediaType == "book" }
                val keep = libraryId?.takeIf { id -> libraries.any { it.id == id } }
                    ?: app.settings.lastLibraryId?.takeIf { id -> libraries.any { it.id == id } }
                    ?: libraries.firstOrNull()?.id
                if (keep != libraryId || libItems.isEmpty()) {
                    libraryId = keep
                    loadLibrary(reset = true)
                    series = emptyList()
                }
            } catch (e: Exception) {
                error = message(e)
            } finally {
                refreshing = false
            }
        }
    }

    fun selectLibrary(id: String) {
        if (id == libraryId) return
        libraryId = id
        app.settings.lastLibraryId = id
        series = emptyList()
        query = ""
        searchResults = null
        loadLibrary(reset = true)
        if (tab == 2) loadSeries()
    }

    fun loadLibrary(reset: Boolean = false) {
        val lib = libraryId ?: return
        if (libLoading && !reset) return
        if (!reset && libItems.size >= libTotal && libTotal > 0) return
        viewModelScope.launch {
            libLoading = true
            try {
                val page = if (reset) 0 else libPage + 1
                val r = api.libraryItems(lib, page, PAGE)
                libPage = page
                libTotal = r.total
                libItems = if (reset) r.results else libItems + r.results
            } catch (e: Exception) {
                error = message(e)
            } finally {
                libLoading = false
            }
        }
    }

    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) {
            searchResults = null
            return
        }
        val lib = libraryId ?: return
        searchJob = viewModelScope.launch {
            delay(400)
            try {
                searchResults = api.search(lib, q.trim())
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    fun loadSeries() {
        val lib = libraryId ?: return
        if (series.isNotEmpty()) return
        viewModelScope.launch {
            try {
                series = api.series(lib, 0, 500).results
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    fun openBook(id: String, title: String) {
        push(Screen.Book(id, title))
        book = null
        viewModelScope.launch {
            try {
                book = api.item(id)
                runCatching { api.progress(id) }.getOrNull()?.let { progress = progress + (id to it) }
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    fun openSeries(s: Series) {
        val lib = libraryId ?: return
        push(Screen.SeriesBooks(lib, s.id, s.name))
        seriesBooks = s.books
        viewModelScope.launch {
            runCatching { api.seriesBooks(lib, s.id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { seriesBooks = it }
        }
    }

    fun setFinished(id: String, finished: Boolean) {
        viewModelScope.launch {
            try {
                api.setFinished(id, finished)
                runCatching { api.progress(id) }.getOrNull()?.let { progress = progress + (id to it) }
                continueItems = api.itemsInProgress()
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    // ------------------------------------------------------------------ login

    var loginBusy by mutableStateOf(false)
    var loginError by mutableStateOf<String?>(null)

    fun login(serverInput: String, username: String, password: String) {
        val server = AbsApi.normalizeServer(serverInput)
        viewModelScope.launch {
            loginBusy = true
            loginError = null
            try {
                val resp = api.login(server, username.trim(), password)
                app.onLoggedIn(server, resp)
            } catch (e: Exception) {
                loginError = message(e)
            } finally {
                loginBusy = false
            }
        }
    }

    fun checkUpdates() {
        viewModelScope.launch { Updater.check(app, manual = true) }
    }

    fun logout() {
        app.targets.local.stop()
        app.logout()
        stack.clear()
        stack.add(Screen.Home)
        continueItems = emptyList()
        libItems = emptyList()
        libraries = emptyList()
        series = emptyList()
    }

    // ------------------------------------------------------------------ playback

    fun play(itemId: String, startSec: Double? = null) {
        target.value.playItem(itemId, startSec)
    }

    /** "local", "tv" or "pc". */
    fun targetKind(): String = (target.value as? cz.ondra.absplayer.remote.RemoteTarget)?.device?.kind ?: "local"

    fun switchTarget(t: PlaybackTarget) {
        viewModelScope.launch { app.targets.switchTo(t) }
    }

    var pairBusy by mutableStateOf(false)
    var pairError by mutableStateOf<String?>(null)

    fun pair(host: String, port: Int, code: String, password: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            pairBusy = true
            pairError = null
            try {
                val d = app.targets.pair(host, port, code, password)
                onDone()
                app.targets.switchTo(app.targets.remote(d))
            } catch (e: Exception) {
                pairError = message(e)
            } finally {
                pairBusy = false
            }
        }
    }

    companion object {
        const val PAGE = 60
    }
}
