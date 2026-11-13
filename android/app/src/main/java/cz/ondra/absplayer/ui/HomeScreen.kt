package cz.ondra.absplayer.ui

import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import cz.ondra.absplayer.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import cz.ondra.absplayer.L
import cz.ondra.absplayer.data.Updater
import cz.ondra.absplayer.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.data.LibraryItem

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val target by vm.target.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = C.Bg,
        topBar = {
            TopAppBar(
                title = { Image(painterResource(R.drawable.logo_small), "ABS Player", modifier = Modifier.size(28.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Bg),
                actions = {
                    LanguageButton()
                    IconButton(onClick = { vm.sheet = Sheet.Devices }) {
                        Icon(
                            Ic.WMonitor, tr("playOn"), modifier = Modifier.size(20.dp),
                            tint = if (target.isRemote) C.Accent else C.Text,
                        )
                    }
                    Box {
                        val upd by Updater.state.collectAsStateWithLifecycle()
                        LaunchedEffect(upd) { if (upd is Updater.State.Available) menu = false }
                        IconButton(onClick = { menu = true }) { Icon(Ic.More, tr("menu")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.Surface) {
                            DropdownMenuItem(
                                text = { Text(tr("refresh")) },
                                leadingIcon = { Icon(Ic.Refresh, null) },
                                onClick = { menu = false; vm.refreshAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("checkUpdates")) },
                                leadingIcon = { Icon(Ic.Download, null) },
                                onClick = { vm.checkUpdates() },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("logout", "u" to (vm.app.settings.username ?: ""))) },
                                onClick = { menu = false; vm.logout() },
                            )
                            HorizontalDivider(color = C.Line, modifier = Modifier.padding(vertical = 4.dp))
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(tr("appVersion", "v" to Updater.currentVersion), fontSize = 13.sp, color = C.Muted)
                                updateStatus()?.let { (t, c) -> Text(t, fontSize = 12.sp, color = c) }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(vm) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            UpdateBanner(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp))
            // remote: coming up from the list lands on the open tab; moving over the tabs switches the content
            val tabFocus = remember { List(5) { FocusRequester() } }
            TabRow(
                selectedTabIndex = vm.tab, containerColor = C.Bg, contentColor = C.Accent,
                divider = { HorizontalDivider(color = C.Line) },
                // without the slide animation: the line moves together with the remote's focus
                indicator = { positions ->
                    positions.getOrNull(vm.tab)?.let { pos ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .wrapContentSize(Alignment.BottomStart)
                                .offset(x = pos.left)
                                .width(pos.width)
                                .height(3.dp)
                                .background(C.Accent),
                        )
                    }
                },
                modifier = Modifier.onFocusChanged { vm.tabsFocused = it.hasFocus }.focusProperties { enter = { tabFocus[vm.tab] } }.focusGroup(),
            ) {
                listOf(tr("tabContinue"), tr("tabAdded"), tr("tabLibrary"), tr("tabSeries"), tr("tabAuthors")).forEachIndexed { i, t ->
                    Tab(selected = vm.tab == i, onClick = { vm.onTab(i) },
                        text = {
                            Text(
                                t, fontSize = if (vm.app.isTv) 15.sp else 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (vm.tab == i) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        },
                        selectedContentColor = C.Accent, unselectedContentColor = C.Muted,
                        modifier = Modifier
                            .focusRequester(tabFocus[i])
                            .onFocusChanged { if (it.isFocused && vm.app.isTv && vm.tab != i) vm.onTab(i) }
                            .downToList(vm))
                }
            }
            vm.error?.let {
                Text(
                    it, color = C.Error, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { vm.error = null }.padding(12.dp, 8.dp),
                )
            }
            if (vm.app.isTv) {
                TabContent(vm, vm.tab)
            } else {
                // phone: swipe left / right switches the tabs
                val pager = rememberPagerState(initialPage = vm.tab) { 5 }
                LaunchedEffect(vm.tab) { if (pager.currentPage != vm.tab) pager.animateScrollToPage(vm.tab) }
                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.collect { if (it != vm.tab) vm.onTab(it) }
                }
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
                    Box(Modifier.fillMaxSize()) { TabContent(vm, page) }
                }
            }
        }
    }
}

@Composable
private fun TabContent(vm: MainViewModel, tab: Int) {
    when (tab) {
        MainViewModel.TAB_CONTINUE -> ContinueTab(vm)
        MainViewModel.TAB_ADDED -> AddedTab(vm)
        MainViewModel.TAB_LIBRARY -> LibraryTab(vm)
        MainViewModel.TAB_SERIES -> SeriesTab(vm)
        else -> AuthorsTab(vm)
    }
}

@Composable
private fun ContinueTab(vm: MainViewModel) {
    if (vm.continueItems.isEmpty()) {
        Empty(if (vm.refreshing) tr("loading") else tr("nothingInProgress"))
        return
    }
    BookGrid(vm, vm.continueItems, showPlay = true, entry = true)
}

/** Remote: Down goes to the first visible item of the list below (see [MainViewModel.listEntry]). */
fun Modifier.downToList(vm: MainViewModel): Modifier = onPreviewKeyEvent { e ->
    vm.app.isTv && e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown && vm.listEntry?.invoke() == true
}

/** Registers [entry] as the list's Down target while it is shown. */
@Composable
private fun ListEntry(vm: MainViewModel, entry: () -> Boolean) {
    if (!vm.app.isTv) return
    val current by rememberUpdatedState(entry)
    DisposableEffect(Unit) {
        val f: () -> Boolean = { runCatching { current() }.getOrDefault(false) }
        vm.listEntry = f
        onDispose { if (vm.listEntry === f) vm.listEntry = null }
    }
}

/** TV search field: moving onto it must not open the keyboard – only OK does. Down goes to the list. */
@Composable
private fun SearchField(vm: MainViewModel, value: String, placeholder: String, onChange: (String) -> Unit) {
    AbsTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Ic.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Ic.Close, tr("clear")) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .downToList(vm),
    )
}

@Composable
private fun AddedTab(vm: MainViewModel) {
    LaunchedEffect(vm.libraryId) { vm.loadRecent() }
    val r = vm.recent
    if (r == null) {
        Empty(tr("loading"))
        return
    }
    if (r.books.isEmpty() && r.series.isEmpty()) {
        Empty(tr("nothingAdded"))
        return
    }
    val bookRow = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val onTop = vm.stack.lastOrNull() == Screen.Home
    ListEntry(vm) {
        val first = r.books.getOrNull(bookRow.firstVisibleItemIndex)?.id ?: r.series.firstOrNull()?.id
        first?.let { requesters[it]?.requestFocus() } != null
    }
    if (vm.app.isTv) {
        LaunchedEffect(r, onTop) {
            if (!onTop || vm.tabsFocused) return@LaunchedEffect
            val want = vm.lastOpenedBook?.takeIf { id -> r.books.any { it.id == id } }
            val idx = want?.let { id -> r.books.indexOfFirst { it.id == id } } ?: 0
            if (want != null) runCatching { bookRow.scrollToItem(idx) }
            delay(120)
            (want ?: r.books.firstOrNull()?.id ?: r.series.firstOrNull()?.id)?.let { id -> runCatching { requesters[id]?.requestFocus() } }
        }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.books.isNotEmpty()) {
            item { SectionTitle(tr("recentlyAdded")) }
            item {
                LazyRow(
                    state = bookRow,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(r.books, key = { it.id }) { li ->
                        BookCard(
                            vm, li, false,
                            if (vm.app.isTv) requesters.getOrPut(li.id) { FocusRequester() } else null,
                            Modifier.width(140.dp),
                        )
                    }
                }
            }
        }
        if (r.series.isNotEmpty()) {
            item { SectionTitle(tr("recentSeries")) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(r.series, key = { it.id }) { se ->
                        SeriesCard(vm, se, if (vm.app.isTv) requesters.getOrPut(se.id) { FocusRequester() } else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
}

/** Series tile: up to three covers fanned out, the name under them. */
@Composable
private fun SeriesCard(vm: MainViewModel, se: cz.ondra.absplayer.data.Series, focus: FocusRequester?) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Column(
        Modifier
            .width(184.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clickable(interactionSource = src, indication = null) { vm.openSeries(se) },
    ) {
        Box(
            Modifier
                .graphicsLayer { val k = if (focused) 1.04f else 1f; scaleX = k; scaleY = k }
                .indication(src, FocusRing(C.Accent, 6.dp))
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val books = se.books.take(3)
            if (books.isEmpty()) Box(Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)).background(C.Surface2))
            books.forEachIndexed { i, b ->
                Cover(
                    b.id, b.title,
                    Modifier
                        .padding(start = (32 * i).dp)
                        .size(120.dp)
                        .graphicsLayer { shadowElevation = if (i > 0) 8f else 0f; shape = RoundedCornerShape(6.dp); clip = true },
                    corner = 6.dp,
                )
            }
            Text(
                tr("nBooks", "n" to se.books.size), fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(
            se.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AuthorsTab(vm: MainViewModel) {
    LaunchedEffect(vm.libraryId) { vm.loadAuthors() }
    Column(Modifier.fillMaxSize()) {
        SearchField(vm, vm.authorQuery, tr("searchAuthor")) { vm.authorQuery = it }
        val all = vm.authors
        if (all == null) {
            Empty(tr("loading"))
            return@Column
        }
        val q = vm.authorQuery.trim()
        val list = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
        if (list.isEmpty()) {
            Empty(tr("notFound"))
            return@Column
        }
        val state = rememberLazyGridState()
        val requesters = remember { mutableMapOf<String, FocusRequester>() }
        ListEntry(vm) { list.getOrNull(state.firstVisibleItemIndex)?.let { requesters[it.id]?.requestFocus() } != null }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            state = state,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.id }) { a ->
                val src = remember { MutableInteractionSource() }
                val focused by src.collectIsFocusedAsState()
                Column(
                    Modifier
                        .then(if (vm.app.isTv) Modifier.focusRequester(requesters.getOrPut(a.id) { FocusRequester() }) else Modifier)
                        .clickable(interactionSource = src, indication = null) { vm.openAuthor(vm.libraryId, a.id, a.name) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .graphicsLayer { val k = if (focused) 1.06f else 1f; scaleX = k; scaleY = k }
                            .indication(src, FocusRing(C.Accent))
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(placeholderFor(a.id)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val ini = a.name.split(' ', '.').filter { it.isNotBlank() }.let {
                            ((it.firstOrNull()?.take(1) ?: "") + (if (it.size > 1) it.last().take(1) else "")).uppercase()
                        }
                        Text(ini, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        if (a.imagePath != null) {
                            coil.compose.AsyncImage(
                                model = cz.ondra.absplayer.player.ArtworkProvider.uriForAuthor(a.id),
                                contentDescription = a.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        a.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                    )
                    a.numBooks?.let { Text(tr("nBooks", "n" to it), color = C.Muted, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun LibraryTab(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (vm.libraries.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.libraries, key = { it.id }) { lib ->
                    FilterChip(
                        selected = lib.id == vm.libraryId,
                        onClick = { vm.selectLibrary(lib.id) },
                        label = { Text(lib.name) },
                    )
                }
            }
        }
        SearchField(vm, vm.query, tr("searchLibrary")) { vm.onQuery(it) }
        val results = vm.searchResults
        if (results != null) {
            if (results.isEmpty()) Empty(tr("notFound")) else BookGrid(vm, results, entry = true)
        } else {
            BookGrid(vm, vm.libItems, onNearEnd = { vm.loadLibrary() }, entry = true)
        }
    }
}

@Composable
private fun SeriesTab(vm: MainViewModel) {
    LaunchedEffect(vm.libraryId) { vm.loadSeries() }
    Column(Modifier.fillMaxSize()) {
    SearchField(vm, vm.seriesQuery, tr("searchSeries")) { vm.seriesQuery = it }
    if (vm.series.isEmpty()) {
        Empty(tr("loadingSeries"))
        return@Column
    }
    val q = vm.seriesQuery.trim()
    val list = if (q.isEmpty()) vm.series else vm.series.filter { it.name.contains(q, ignoreCase = true) }
    if (list.isEmpty()) {
        Empty(tr("notFound"))
        return@Column
    }
    val state = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    ListEntry(vm) { list.getOrNull(state.firstVisibleItemIndex)?.let { requesters[it.id]?.requestFocus() } != null }
    LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (vm.app.isTv) Modifier.focusRequester(requesters.getOrPut(s.id) { FocusRequester() }) else Modifier)
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.Surface)
                    .clickable { vm.openSeries(s) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val first = s.books.firstOrNull()
                if (first != null) Cover(first.id, first.title, Modifier.size(64.dp), corner = 8.dp)
                else Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(C.Surface2))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(tr("nBooks", "n" to s.books.size), color = C.Muted, fontSize = 14.sp)
                }
            }
        }
    }
    }
}

@Composable
fun BookGrid(
    vm: MainViewModel,
    items: List<LibraryItem>,
    showPlay: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    entry: Boolean = false,
) {
    val state = rememberLazyGridState()
    if (onNearEnd != null) {
        val near by remember(items) {
            derivedStateOf {
                val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                items.isNotEmpty() && last >= items.size - 12
            }
        }
        LaunchedEffect(near) { if (near) onNearEnd() }
    }
    // TV: start on the first book; after coming back from a book, on that book again
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val onTop = vm.stack.lastOrNull() == Screen.Home
    if (entry) ListEntry(vm) { items.getOrNull(state.firstVisibleItemIndex)?.let { requesters[it.id]?.requestFocus() } != null }
    if (vm.app.isTv) {
        LaunchedEffect(items.firstOrNull()?.id, onTop) {
            // not while the remote walks over the tabs (the content changes under it)
            if (!onTop || items.isEmpty() || vm.tabsFocused) return@LaunchedEffect
            val id = vm.lastOpenedBook?.takeIf { want -> items.any { it.id == want } } ?: items.first().id
            val idx = items.indexOfFirst { it.id == id }
            runCatching { state.scrollToItem(idx) }
            delay(120)
            runCatching { requesters[id]?.requestFocus() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { li ->
            BookCard(vm, li, showPlay, if (vm.app.isTv) requesters.getOrPut(li.id) { FocusRequester() } else null)
        }
        if (onNearEnd != null && vm.libLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) { Empty(tr("loading")) }
        }
    }
}

@Composable
private fun BookCard(vm: MainViewModel, li: LibraryItem, showPlay: Boolean, focus: FocusRequester? = null, modifier: Modifier = Modifier) {
    val p = vm.progress[li.id]
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Column(
        modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clickable(interactionSource = src, indication = null) { vm.openBook(li.id, li.title) },
    ) {
        // remote control: ring (same rounding as the cover) only around the cover, title stays readable
        Box(
            Modifier
                .graphicsLayer { val k = if (focused) 1.04f else 1f; scaleX = k; scaleY = k }
                .indication(src, FocusRing(C.Accent, 6.dp)),
        ) {
            Cover(li.id, li.title, Modifier.fillMaxWidth().aspectRatio(1f), corner = 6.dp, progress = p?.progress)
            if (showPlay && !vm.app.isTv) {
                IconButton(
                    onClick = { vm.play(li.id) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(C.Accent),
                ) { Icon(Ic.Play, tr("play"), tint = C.OnAccent) }
            }
        }
        Text(
            li.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        val sub = when {
            p?.isFinished == true -> tr("finished")
            p != null && li.media.duration != null -> tr("left", "t" to formatLeft(li.media.duration - p.currentTime))
            else -> li.author
        }
        li.media.metadata.seriesLabel.takeIf { it.isNotBlank() }?.let {
            Text(it, color = C.Accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(sub, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val authorColors = listOf(
    Color(0xFF2B4D6B), Color(0xFF5C2E2E), Color(0xFF2B5876), Color(0xFF6B5233), Color(0xFF4A3D5C), Color(0xFF3D5C4A),
)
private fun placeholderFor(id: String) = authorColors[kotlin.math.abs(id.hashCode()) % authorColors.size]

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = C.Muted)
    }
}

/** Flag of the app language; opens the same 15 languages as the Windows app. */
@Composable
private fun LanguageButton() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.padding(horizontal = 2.dp).size(44.dp),
        ) {
            Image(
                painterResource(flagRes(L.lang)), tr("language") + ": " + L.names[L.codes.indexOf(L.lang)],
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(24.dp, 16.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false }, containerColor = C.Surface,
            modifier = Modifier.width(210.dp),
        ) {
            L.codes.forEachIndexed { i, code ->
                val on = code == L.lang
                DropdownMenuItem(
                    text = { Text(L.names[i], fontSize = 14.sp) },
                    leadingIcon = {
                        Image(
                            painterResource(flagRes(code)), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(24.dp, 16.dp).clip(RoundedCornerShape(2.dp)),
                        )
                    },
                    trailingIcon = { if (on) Icon(Ic.Check, null, tint = C.Accent, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.background(if (on) C.Press else Color.Transparent),
                    onClick = {
                        open = false
                        L.set(ctx, code)
                    },
                )
            }
        }
    }
}

fun flagRes(code: String): Int = when (code) {
    "cs" -> R.drawable.flag_cs
    "sk" -> R.drawable.flag_sk
    "en" -> R.drawable.flag_en
    "de" -> R.drawable.flag_de
    "pl" -> R.drawable.flag_pl
    "fr" -> R.drawable.flag_fr
    "es" -> R.drawable.flag_es
    "it" -> R.drawable.flag_it
    "pt" -> R.drawable.flag_pt
    "nl" -> R.drawable.flag_nl
    "hu" -> R.drawable.flag_hu
    "uk" -> R.drawable.flag_uk
    "ru" -> R.drawable.flag_ru
    "zh" -> R.drawable.flag_zh
    else -> R.drawable.flag_ja
}
