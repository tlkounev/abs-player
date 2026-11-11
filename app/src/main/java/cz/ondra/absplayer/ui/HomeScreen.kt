package cz.ondra.absplayer.ui

import androidx.compose.foundation.background
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
                title = { Text("ABS Player", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Bg),
                actions = {
                    IconButton(onClick = { vm.sheet = Sheet.Devices }) {
                        Icon(
                            if (target.isRemote) Ic.Tv else Ic.Devices, "Přehrávat na",
                            tint = if (target.isRemote) C.Accent else C.Text,
                        )
                    }
                    IconButton(onClick = { vm.refreshAll() }) { Icon(Ic.Refresh, "Obnovit") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Ic.More, "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Odhlásit (${vm.app.settings.username ?: ""})") },
                                onClick = { menu = false; vm.logout() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(vm) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = vm.tab, containerColor = C.Bg) {
                listOf("Pokračovat", "Knihovna", "Série").forEachIndexed { i, t ->
                    Tab(selected = vm.tab == i, onClick = {
                        vm.tab = i
                        if (i == 2) vm.loadSeries()
                    }, text = { Text(t) })
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
        Empty(if (vm.refreshing) "Načítám…" else "Nic rozposlouchaného")
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
            placeholder = { Text("Hledat v knihovně") },
            leadingIcon = { Icon(Ic.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Ic.Close, "Smazat") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val results = vm.searchResults
        if (results != null) {
            if (results.isEmpty()) Empty("Nic nenalezeno") else BookGrid(vm, results)
        } else {
            BookGrid(vm, vm.libItems, onNearEnd = { vm.loadLibrary() })
        }
    }
}

@Composable
private fun SeriesTab(vm: MainViewModel) {
    LaunchedEffect(vm.libraryId) { vm.loadSeries() }
    if (vm.series.isEmpty()) {
        Empty("Načítám série…")
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
                    Text("${s.books.size} knih", color = C.Muted, fontSize = 14.sp)
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
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { li -> BookCard(vm, li, showPlay) }
        if (onNearEnd != null && vm.libLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) { Empty("Načítám…") }
        }
    }
}

@Composable
private fun BookCard(vm: MainViewModel, li: LibraryItem, showPlay: Boolean) {
    val p = vm.progress[li.id]
    Column(Modifier.clickable { vm.openBook(li.id, li.title) }) {
        Box {
            Cover(li.id, li.title, Modifier.fillMaxWidth().aspectRatio(1f), progress = p?.progress)
            if (showPlay) {
                IconButton(
                    onClick = { vm.play(li.id) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(C.Accent),
                ) { Icon(Ic.Play, "Přehrát", tint = C.OnAccent) }
            }
        }
        Text(
            li.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
        val sub = when {
            p?.isFinished == true -> "Dokončeno"
            p != null && li.media.duration != null -> "zbývá " + formatLeft(li.media.duration - p.currentTime)
            else -> li.author
        }
        Text(sub, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = C.Muted)
    }
}
