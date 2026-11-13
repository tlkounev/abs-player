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
import cz.ondra.absplayer.data.Author
import cz.ondra.absplayer.data.Recent
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
    data class AuthorBooks(val libraryId: String, val authorId: String, val name: String) : Screen

    /**
     * E-book reader. [mode]: "saved" (where reading ended), "fromAudio" (where listening ended,
     * [audioTime]) or "toAudio" (find the time of the reading position and play from there).
     */
    data class Reader(val itemId: String, val title: String, val mode: String = "saved", val audioTime: Double? = null) : Screen
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
    var seriesQuery by mutableStateOf("")
    var authors by mutableStateOf<List<Author>?>(null)
    var authorQuery by mutableStateOf("")
    var recent by mutableStateOf<Recent?>(null)

    /**
     * Remote control: Down from the tabs / a search field goes to the first visible item of the list
     * below (set by that list), not to the item nearest the middle.
     */
    var listEntry: (() -> Boolean)? = null

    /** Local network permission granted (or not needed on this Android). */
    var localNetworkOk by mutableStateOf(true)

    /** The remote is on the home tabs (lists then don't pull the focus to themselves). */
    var tabsFocused = false
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // detail screens
    /** Per screen (screens can now nest: book → author → book). */
    var books by mutableStateOf<Map<String, LibraryItem>>(emptyMap())
    var seriesBooks by mutableStateOf<Map<String, List<LibraryItem>>>(emptyMap())
    var authorBooks by mutableStateOf<Map<String, List<LibraryItem>>>(emptyMap())

    private var searchJob: Job? = null
    private var continueRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            loggedIn.collect { if (it) refreshAll() }
        }
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
                    authors = null
                }
                recent = null
                when (tab) {
                    TAB_ADDED -> loadRecent()
                    TAB_AUTHORS -> loadAuthors()
                }
                retries = 0
            } catch (e: Exception) {
                error = message(e)
                // e.g. right after an update the network is not up yet: try again by itself
                if (retries < 6) {
                    retries++
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(2_000L * retries)
                        if (error != null) refreshAll()
                    }
                }
            } finally {
                refreshing = false
            }
        }
    }
    private var retries = 0

    fun selectLibrary(id: String) {
        if (id == libraryId) return
        libraryId = id
        app.settings.lastLibraryId = id
        series = emptyList()
        authors = null
        recent = null
        query = ""
        searchResults = null
        loadLibrary(reset = true)
        onTab(tab)
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

    fun onTab(i: Int) {
        tab = i
        when (i) {
            TAB_ADDED -> loadRecent()
            TAB_SERIES -> loadSeries()
            TAB_AUTHORS -> loadAuthors()
        }
    }

    fun loadRecent() {
        val lib = libraryId ?: return
        if (recent != null) return
        viewModelScope.launch {
            try {
                recent = api.recent(lib)
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    fun loadAuthors() {
        val lib = libraryId ?: return
        if (authors != null) return
        viewModelScope.launch {
            try {
                val c = java.text.Collator.getInstance()
                authors = api.authors(lib).sortedWith { a, b -> c.compare(a.name, b.name) }
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    /** Book details (authors / series with ids) – cached, also used by the player. */
    suspend fun details(itemId: String): LibraryItem? =
        books[itemId] ?: runCatching { api.item(itemId) }.getOrNull()?.also { books = books + (itemId to it) }

    /** Last opened book – the TV home screen puts the remote's focus back on it. */
    var lastOpenedBook: String? = null
        private set

    fun openBook(id: String, title: String) {
        lastOpenedBook = id
        push(Screen.Book(id, title))
        viewModelScope.launch {
            try {
                api.item(id).let { books = books + (id to it) }
                runCatching { api.progress(id) }.getOrNull()?.let { progress = progress + (id to it) }
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    fun openSeries(s: Series) {
        val lib = libraryId ?: return
        if (s.books.isNotEmpty()) seriesBooks = seriesBooks + (s.id to s.books)
        openSeries(lib, s.id, s.name)
    }

    fun openSeries(libId: String?, id: String, name: String) {
        val lib = libId ?: libraryId ?: return
        if (stack.lastOrNull() == Screen.Player) pop()
        push(Screen.SeriesBooks(lib, id, name))
        viewModelScope.launch {
            try {
                api.seriesBooks(lib, id).takeIf { it.isNotEmpty() }?.let { seriesBooks = seriesBooks + (id to it) }
            } catch (e: Exception) {
                error = message(e)
            }
        }
    }

    fun openAuthor(libId: String?, id: String, name: String) {
        val lib = libId ?: libraryId ?: return
        if (stack.lastOrNull() == Screen.Player) pop()
        push(Screen.AuthorBooks(lib, id, name))
        viewModelScope.launch {
            try {
                authorBooks = authorBooks + (id to api.authorBooks(lib, id))
            } catch (e: Exception) {
                error = message(e)
            }
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

    fun autoCheckUpdates() {
        viewModelScope.launch { Updater.check(app, manual = false) }
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
        authors = null
        recent = null
        books = emptyMap()
        seriesBooks = emptyMap()
        authorBooks = emptyMap()
    }

    // ------------------------------------------------------------------ playback

    fun play(itemId: String, startSec: Double? = null) {
        target.value.playItem(itemId, startSec)
    }

    // ------------------------------------------------------------------ e-book

    /** The book's EPUB in the cache (downloaded once). */
    suspend fun ebookFile(itemId: String): java.io.File {
        val dir = java.io.File(app.cacheDir, "ebooks").apply { mkdirs() }
        val f = java.io.File(dir, "$itemId.epub")
        if (!f.exists() || f.length() == 0L) api.downloadEbook(itemId, f)
        return f
    }

    fun ebookLocationsFile(itemId: String) = java.io.File(java.io.File(app.cacheDir, "ebooks").apply { mkdirs() }, "$itemId.loc.json")

    private var ebookSaveJob: kotlinx.coroutines.Job? = null

    /** Reading position: at once into the local state, to the server 2 s after the last page turn. */
    fun saveEbookPosition(itemId: String, cfi: String, prog: Double?) {
        val old = progress[itemId]
        if (old?.ebookLocation == cfi) return
        progress = progress + (itemId to (old ?: MediaProgress(libraryItemId = itemId)).copy(ebookLocation = cfi, ebookProgress = prog ?: old?.ebookProgress))
        ebookSaveJob?.cancel()
        ebookSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(2_000)
            runCatching { api.saveEbookProgress(itemId, cfi, prog) }
        }
    }

    /** From the reader: listen from [seconds] – the player replaces the reader. */
    fun listenFrom(itemId: String, seconds: Double) {
        play(itemId, seconds)
        if (stack.lastOrNull() is Screen.Reader) pop()
        if (stack.lastOrNull() != Screen.Player) push(Screen.Player)
    }

    /** "local", "tv" or "pc". */
    fun targetKind(): String = (target.value as? cz.ondra.absplayer.remote.RemoteTarget)?.device?.kind ?: "local"

    /** Book being handed to the chosen device: the player shows "loading", not "nothing playing". */
    var handoffItem by mutableStateOf<String?>(null)

    fun switchTarget(t: PlaybackTarget) {
        viewModelScope.launch {
            // set before switching: the player must not show "nothing playing" for a moment
            val moving = app.targets.current.value.state.value.itemId
            if (moving != null) handoffItem = moving
            val item = app.targets.switchTo(t)
            if (item == null) {
                handoffItem = null
                return@launch
            }
            // the book continues there: show the player
            handoffItem = item
            if (stack.lastOrNull() != Screen.Player) push(Screen.Player)
            kotlinx.coroutines.delay(15_000)
            if (handoffItem == item) handoffItem = null
        }
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
                // the receiver's own message (bad code, no response…), not "server unavailable"
                pairError = e.message ?: e.javaClass.simpleName
            } finally {
                pairBusy = false
            }
        }
    }

    companion object {
        const val PAGE = 60
        const val TAB_CONTINUE = 0
        const val TAB_ADDED = 1
        const val TAB_LIBRARY = 2
        const val TAB_SERIES = 3
        const val TAB_AUTHORS = 4
    }
}
