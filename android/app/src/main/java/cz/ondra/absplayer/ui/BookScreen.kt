package cz.ondra.absplayer.ui

import cz.ondra.absplayer.tr
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
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

    Scaffold(
        containerColor = C.Bg,
        topBar = {
            TopAppBar(
                title = { Text(screen.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Ic.Back, tr("back0")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Bg),
            )
        },
        bottomBar = { MiniPlayer(vm) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(screen.id, screen.title, Modifier.size(230.dp), corner = 16.dp, progress = p?.progress)
                    Spacer(Modifier.height(16.dp))
                    Text(b?.title ?: screen.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    b?.media?.metadata?.subtitle?.let { Text(it, color = C.Muted) }
                    if (b != null) {
                        Text(b.author, color = C.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                        b.media.metadata.narrator.takeIf { it.isNotBlank() }?.let {
                            Text(tr("readBy", "t" to it), color = C.Muted, fontSize = 14.sp)
                        }
                        val dur = b.media.duration ?: 0.0
                        val line = when {
                            p?.isFinished == true -> tr("finishedDur", "t" to formatLeft(dur))
                            p != null && p.currentTime > 0 -> tr("pctLeft", "p" to (p.progress * 100).toInt(), "t" to formatLeft(dur - p.currentTime))
                            else -> formatLeft(dur)
                        }
                        Text(line, color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    val label = when {
                        isCurrent && playing.isPlaying -> tr("playingNow")
                        p?.isFinished == true -> tr("playAgain")
                        p != null && p.currentTime > 0 -> tr("resume")
                        else -> tr("play")
                    }
                    Button(
                        onClick = {
                            if (p?.isFinished == true) vm.play(screen.id, 0.0) else vm.play(screen.id)
                            vm.push(Screen.Player)
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Icon(Ic.Play, null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 17.sp)
                    }
                    Text(
                        tr("playOn") + ": " + target.name, color = C.Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp).clickable { vm.sheet = Sheet.Devices },
                    )
                    if (b != null) {
                        TextButton(onClick = { vm.setFinished(screen.id, p?.isFinished != true) }) {
                            Text(if (p?.isFinished == true) tr("markUnfinished") else tr("markFinished"))
                        }
                    }
                }
            }
            b?.media?.metadata?.description?.takeIf { it.isNotBlank() }?.let { d ->
                item {
                    Text(
                        android.text.Html.fromHtml(d, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim(),
                        color = C.Muted, fontSize = 15.sp, lineHeight = 21.sp,
                        maxLines = if (descOpen) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 12.dp).clickable { descOpen = !descOpen },
                    )
                }
            }
            val chapters = b?.media?.chapters.orEmpty()
            if (chapters.isNotEmpty()) {
                item {
                    Text(tr("chaptersN", "n" to chapters.size), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                }
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
