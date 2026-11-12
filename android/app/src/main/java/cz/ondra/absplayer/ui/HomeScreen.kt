package cz.ondra.absplayer.ui

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val target by vm.target.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = C.Bg,
        topBar = {
            TopAppBar(
                title = { Text("ABS Player", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            TabRow(
                selectedTabIndex = vm.tab, containerColor = C.Bg, contentColor = C.Accent,
                divider = { HorizontalDivider(color = C.Line) },
            ) {
                listOf(tr("tabContinue"), tr("tabLibrary"), tr("tabSeries")).forEachIndexed { i, t ->
                    Tab(selected = vm.tab == i, onClick = {
                        vm.tab = i
                        if (i == 2) vm.loadSeries()
                    }, text = { Text(t, fontSize = 15.sp, fontWeight = if (vm.tab == i) FontWeight.SemiBold else FontWeight.Medium) },
                        selectedContentColor = C.Accent, unselectedContentColor = C.Muted)
                }
            }
            vm.error?.let {
                Text(
                    it, color = C.Error, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { vm.error = null }.padding(12.dp, 8.dp),
                )
            }
            when (vm.tab) {
                0 -> ContinueTab(vm)
                1 -> LibraryTab(vm)
                else -> SeriesTab(vm)
            }
        }
    }
}

@Composable
private fun ContinueTab(vm: MainViewModel) {
    if (vm.continueItems.isEmpty()) {
        Empty(if (vm.refreshing) tr("loading") else tr("nothingInProgress"))
        return
    }
    BookGrid(vm, vm.continueItems, showPlay = true)
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
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.onQuery(it) },
            placeholder = { Text(tr("searchLibrary")) },
            leadingIcon = { Icon(Ic.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Ic.Close, tr("clear")) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val results = vm.searchResults
        if (results != null) {
            if (results.isEmpty()) Empty(tr("notFound")) else BookGrid(vm, results)
        } else {
            BookGrid(vm, vm.libItems, onNearEnd = { vm.loadLibrary() })
        }
    }
}

@Composable
private fun SeriesTab(vm: MainViewModel) {
    LaunchedEffect(vm.libraryId) { vm.loadSeries() }
    if (vm.series.isEmpty()) {
        Empty(tr("loadingSeries"))
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(vm.series, key = { it.id }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
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

@Composable
fun BookGrid(
    vm: MainViewModel,
    items: List<LibraryItem>,
    showPlay: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { li -> BookCard(vm, li, showPlay) }
        if (onNearEnd != null && vm.libLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) { Empty(tr("loading")) }
        }
    }
}

@Composable
private fun BookCard(vm: MainViewModel, li: LibraryItem, showPlay: Boolean) {
    val p = vm.progress[li.id]
    Column(Modifier.clickable { vm.openBook(li.id, li.title) }) {
        Box {
            Cover(li.id, li.title, Modifier.fillMaxWidth().aspectRatio(1f), corner = 6.dp, progress = p?.progress)
            if (showPlay) {
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
        Text(sub, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
            modifier = Modifier.padding(horizontal = 2.dp).size(44.dp).clip(RoundedCornerShape(8.dp)).background(C.Press),
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
