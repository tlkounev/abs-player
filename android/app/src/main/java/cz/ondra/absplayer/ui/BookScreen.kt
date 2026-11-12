package cz.ondra.absplayer.ui

import cz.ondra.absplayer.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(vm: MainViewModel, screen: Screen.Book) {
    val b = vm.book?.takeIf { it.id == screen.id }
    val p = vm.progress[screen.id]
    val target by vm.target.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val isCurrent = playing.itemId == screen.id
    var descOpen by remember { mutableStateOf(false) }
    var chOpen by remember(screen.id) { mutableStateOf(false) }
    val wide = LocalConfiguration.current.let { it.screenWidthDp >= 600 && it.screenWidthDp > it.screenHeightDp }
    // remote control: start on Play / Continue
    val playFocus = remember { FocusRequester() }
    // also when coming back from the player (this screen is on top again)
    val onTop = vm.stack.lastOrNull() == screen
    LaunchedEffect(screen.id, b != null, onTop) {
        if (vm.app.isTv && b != null && onTop) {
            delay(150)
            runCatching { playFocus.requestFocus() }
        }
    }

    Scaffold(
        containerColor = C.Bg,
        topBar = {
            TopAppBar(
                title = { Text(screen.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Ic.Back, tr("back0")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Bg),
            )
        },
        bottomBar = { MiniPlayer(vm, up = playFocus) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                val label = when {
                    isCurrent && playing.isPlaying -> tr("playingNow")
                    p?.isFinished == true -> tr("playAgain")
                    p != null && p.currentTime > 0 -> tr("resume")
                    else -> tr("play")
                }
                val onPlay = {
                    if (p?.isFinished == true) vm.play(screen.id, 0.0) else vm.play(screen.id)
                    vm.push(Screen.Player)
                }
                val info: @Composable (Alignment.Horizontal) -> Unit = { align ->
                    Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(b?.title ?: screen.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        b?.media?.metadata?.subtitle?.let { Text(it, color = C.Muted) }
                        if (b != null) {
                            Text(b.author, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
                            b.media.metadata.narrator.takeIf { it.isNotBlank() }?.let {
                                Text(tr("readBy", "t" to it), color = C.Muted, fontSize = 14.sp)
                            }
                            val dur = b.media.duration ?: 0.0
                            val line = when {
                                p?.isFinished == true -> tr("finishedDur", "t" to formatLeft(dur))
                                p != null && p.currentTime > 0 -> tr("pctLeft", "p" to (p.progress * 100).toInt(), "t" to formatLeft(dur - p.currentTime))
                                else -> formatLeft(dur)
                            }
                            Text(line, color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (wide) {
                    // TV / landscape: cover left, details and buttons right; buttons only as wide as their text
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Cover(screen.id, screen.title, Modifier.size(260.dp), corner = 14.dp, progress = p?.progress)
                        Column(Modifier.weight(1f)) {
                            info(Alignment.Start)
                            Row(
                                Modifier.padding(top = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayPill(label, Modifier.focusRequester(playFocus).focusProperties { left = FocusRequester.Cancel }, onPlay)
                                OutlinePill(tr("playOn") + ": " + target.name) { vm.sheet = Sheet.Devices }
                                if (b != null) {
                                    OutlinePill(if (p?.isFinished == true) tr("markUnfinished") else tr("markFinished")) {
                                        vm.setFinished(screen.id, p?.isFinished != true)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(screen.id, screen.title, Modifier.size(200.dp), corner = 14.dp, progress = p?.progress)
                        Spacer(Modifier.height(14.dp))
                        info(Alignment.CenterHorizontally)
                        Spacer(Modifier.height(14.dp))
                        PlayPill(label, Modifier.fillMaxWidth().focusRequester(playFocus), onPlay)
                        Text(
                            tr("playOn") + ": " + target.name, color = C.Muted, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { vm.sheet = Sheet.Devices }.padding(4.dp),
                        )
                        if (b != null) {
                            TextButton(onClick = { vm.setFinished(screen.id, p?.isFinished != true) }) {
                                Text(if (p?.isFinished == true) tr("markUnfinished") else tr("markFinished"))
                            }
                        }
                    }
                }
            }
            b?.media?.metadata?.description?.takeIf { it.isNotBlank() }?.let { d ->
                item {
                    Text(
                        android.text.Html.fromHtml(d, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim(),
                        color = C.Muted, fontSize = 14.sp, lineHeight = 20.sp,
                        maxLines = if (descOpen) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 12.dp).clickable { descOpen = !descOpen },
                    )
                }
            }
            val chapters = b?.media?.chapters.orEmpty()
            if (chapters.isNotEmpty()) {
                item {
                    // collapsed by default; one row opens / closes the list
                    val cur = if (p != null && p.currentTime > 0) chapters.indexOfLast { it.start <= p.currentTime } + 1 else 0
                    Row(
                        Modifier
                            .padding(top = 8.dp, bottom = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(C.Surface)
                            .clickable { chOpen = !chOpen }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Ic.WChapters, null, modifier = Modifier.size(18.dp))
                        Text(tr("chaptersN", "n" to chapters.size), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (cur > 0) Text(tr("nowCh", "n" to cur), color = C.Muted, fontSize = 13.sp)
                        Icon(Ic.WDown, null, modifier = Modifier.size(16.dp).rotate(if (chOpen) 180f else 0f))
                    }
                }
            }
            if (chapters.isNotEmpty() && chOpen) {
                itemsIndexed(chapters) { i, c ->
                    val done = p != null && (p.isFinished || c.end <= p.currentTime + 1)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (isCurrent) target.chapter(i) else vm.play(screen.id, c.start)
                                vm.push(Screen.Player)
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", color = C.Muted, modifier = Modifier.width(36.dp))
                        Text(
                            c.title.ifBlank { tr("chapterX", "n" to i + 1) }, modifier = Modifier.weight(1f),
                            color = if (done) C.Muted else C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(formatClock(c.start), color = C.Muted, fontSize = 13.sp)
                        if (done) Icon(Ic.Check, tr("done"), tint = C.Ok, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    }
                }
            }
            if (b == null) item { Empty(tr("loading")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(vm: MainViewModel, screen: Screen.SeriesBooks) {
    Scaffold(
        containerColor = C.Bg,
        topBar = {
            TopAppBar(
                title = { Text(screen.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Ic.Back, tr("back0")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Bg),
            )
        },
        bottomBar = { MiniPlayer(vm) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(C.Bg)) {
            BookGrid(vm, vm.seriesBooks)
        }
    }
}

/** Accent pill: the main action (Play / Continue). Focused by the remote: white ring + slightly bigger. */
@Composable
fun PlayPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier
            .graphicsLayer { val k = if (focused) 1.06f else 1f; scaleX = k; scaleY = k }
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(C.Accent)
            .clickable(interactionSource = src, indication = FocusRing(Color.White, 24.dp), onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Ic.WPlay, null, tint = C.OnAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = C.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OutlinePill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(22.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = FocusRing(C.Accent, 22.dp), onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
