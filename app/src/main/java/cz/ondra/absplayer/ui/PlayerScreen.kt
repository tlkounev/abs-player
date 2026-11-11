package cz.ondra.absplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.player.ChapterInfo
import cz.ondra.absplayer.player.NowPlaying
import cz.ondra.absplayer.player.PlayerEngine

/** Chapter containing [sec] (by start time), or null for a book without chapters. */
fun NowPlaying.chapterAtSec(sec: Double): Pair<Int, ChapterInfo>? {
    if (chapters.size <= 1) return null
    var i = chapters.indexOfLast { it.startSec <= sec + 0.001 }
    if (i < 0) i = 0
    return i to chapters[i]
}

/**
 * Same layout and controls as the Audiobookshelf Tray mini player:
 * chapter bar + thin book bar (both draggable), elapsed / label / remaining (at the current speed),
 * volume (tap = mute, hold = slider), speed (tap = next step, hold = fine), chapter list.
 */
@Composable
fun PlayerScreen(vm: MainViewModel) {
    val s by vm.playing.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val itemId = s.itemId
    /** Position shown while a bar is being dragged (seconds in the book). */
    var preview by remember { mutableStateOf<Double?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Ic.Down, "Zavřít") }
            Spacer(Modifier.weight(1f))
            TargetChip(vm, target.name, target.isRemote, vm.targetKind())
        }

        if (itemId == null) {
            NothingPlaying(vm, s)
            return@Column
        }

        Box(Modifier.padding(top = 8.dp).widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)) {
            Cover(itemId, s.title, Modifier.fillMaxSize(), corner = 18.dp)
            if (s.isLoading || s.isBuffering) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = C.Accent)
            }
        }

        val status = s.error ?: s.info
        Text(
            status ?: " ", color = if (s.error != null) C.Error else C.Ok, fontSize = 13.sp,
            modifier = Modifier.padding(top = 12.dp), maxLines = 2, textAlign = TextAlign.Center,
        )
        Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(s.author, color = C.Muted, fontSize = 15.sp, maxLines = 1)

        val pos = preview ?: s.positionSec
        val rate = s.speed.coerceAtLeast(0.1f)
        val ch = s.chapterAtSec(pos)
        // While dragging the book bar outside the current chapter, the chapter labels follow the position.
        if (ch != null) {
            Text(
                ch.second.title,
                color = C.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // --- chapter bar (big) ---
        if (ch != null) {
            val c = ch.second
            val len = (c.endSec - c.startSec).coerceAtLeast(1.0)
            val inCh = (pos - c.startSec).coerceIn(0.0, len)
            SeekBar(
                fraction = (inCh / len).toFloat(),
                thin = false,
                onPreview = { f -> preview = f?.let { c.startSec + it * len } },
                onSeek = { f -> target.seekTo(c.startSec + f * len); preview = null },
                modifier = Modifier.padding(top = 10.dp),
            )
            TimesRow(
                formatClock(inCh),
                "Kapitola ${ch.first + 1}/${s.chapters.size}",
                "−" + formatClock((len - inCh) / rate),
                onLabel = { vm.sheet = Sheet.Chapters },
            )
        }

        // --- book bar (thin with chapters, big without) ---
        val dur = s.durationSec.coerceAtLeast(1.0)
        SeekBar(
            fraction = (pos / dur).toFloat(),
            thin = ch != null,
            onPreview = { f -> preview = f?.let { it * dur } },
            onSeek = { f -> target.seekTo(f * dur); preview = null },
            modifier = Modifier.padding(top = if (ch != null) 6.dp else 10.dp),
        )
        TimesRow(
            formatClock(pos),
            "Kniha · ${((pos / dur) * 100).toInt()} %",
            "−" + formatClock((dur - pos) / rate),
        )

        // --- transport ---
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Ic.Prev, "Předchozí kapitola", 56.dp, enabled = ch != null) { target.prev() }
            SkipButton(Ic.Replay, "Zpět o 30 s") { target.skip(-30.0) }
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(C.Accent).clickable { target.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (s.isPlaying) Ic.Pause else Ic.Play, if (s.isPlaying) "Pozastavit" else "Přehrát", tint = C.OnAccent, modifier = Modifier.size(42.dp))
            }
            SkipButton(Ic.Forward, "Vpřed o 30 s") { target.skip(30.0) }
            RoundButton(Ic.Next, "Další kapitola", 56.dp, enabled = ch != null) { target.next() }
        }

        // --- volume · speed · sleep · chapters ---
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            val vol = if (s.muted) 0f else s.volume
            val volIcon = when {
                vol <= 0f -> Ic.VolumeOff
                vol <= 0.5f -> Ic.VolumeDown
                else -> Ic.VolumeUp
            }
            Chip(volIcon, "${(vol * 100).toInt()} %", onLongClick = { vm.sheet = Sheet.Volume }) { target.toggleMute() }
            Chip(Ic.Speed, PlayerEngine.formatSpeed(s.speed), onLongClick = { vm.sheet = Sheet.Speed }) {
                val next = PlayerEngine.SPEEDS.firstOrNull { it > s.speed + 0.001f } ?: PlayerEngine.SPEEDS.first()
                target.setSpeed(next)
            }
            Chip(Ic.Timer, sleepLabel(s.sleepSec)) { vm.sheet = Sheet.Sleep }
            if (ch != null) Chip(Ic.List, "Kapitoly") { vm.sheet = Sheet.Chapters }
        }
        Text(
            "Podrž hlasitost nebo rychlost pro jemné nastavení",
            color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Like the Tray popup when nothing plays: recently listened titles. */
@Composable
private fun NothingPlaying(vm: MainViewModel, s: NowPlaying) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        if (s.isLoading) {
            Text("Načítám…", color = C.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }
        s.error?.let { Text(it, color = C.Error, modifier = Modifier.padding(bottom = 12.dp)) }
        Text("Pokračovat v poslechu", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
        val items = vm.continueItems.take(5)
        if (items.isEmpty()) Text("Nic se nepřehrává.", color = C.Muted)
        items.forEach { li ->
            val p = vm.progress[li.id]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { vm.play(li.id) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(li.id, li.title, Modifier.size(56.dp), corner = 6.dp, progress = p?.progress)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(li.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(li.author, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(C.Accent), contentAlignment = Alignment.Center) {
                    Icon(Ic.Play, "Přehrát", tint = C.OnAccent)
                }
            }
        }
    }
}

@Composable
private fun TargetChip(vm: MainViewModel, name: String, remote: Boolean, kind: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (remote) C.AccentDark else C.Surface2)
            .clickable { vm.sheet = Sheet.Devices }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            !remote -> Ic.Phone
            kind == "pc" -> Ic.Computer
            else -> Ic.Tv
        }
        Icon(icon, null, tint = if (remote) C.Accent else C.Text, modifier = Modifier.size(18.dp))
        Text(name, modifier = Modifier.padding(start = 8.dp), fontSize = 14.sp, color = if (remote) C.Accent else C.Text)
    }
}

@Composable
private fun TimesRow(left: String, label: String, right: String, onLabel: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            label, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = (if (onLabel != null) Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLabel) else Modifier)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(right, color = C.Muted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/**
 * Progress bar that can be tapped or dragged anywhere (as in the Tray). [onPreview] gets the
 * fraction while dragging (null when done), [onSeek] the final fraction.
 */
@Composable
fun SeekBar(
    fraction: Float,
    thin: Boolean,
    onPreview: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val preview by rememberUpdatedState(onPreview)
    val seek by rememberUpdatedState(onSeek)
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    val railH = if (thin) 3.dp else 5.dp
    val knob = if (thin) (if (drag != null) 7.dp else 5.dp) else (if (drag != null) 9.dp else 7.dp)

    Box(
        modifier
            .fillMaxWidth()
            .height(if (thin) 24.dp else 32.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { o -> seek((o.x / width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        drag = (o.x / width).coerceIn(0f, 1f)
                        preview(drag)
                    },
                    onDragEnd = {
                        drag?.let { seek(it) }
                        drag = null
                        preview(null)
                    },
                    onDragCancel = {
                        drag = null
                        preview(null)
                    },
                ) { change, _ ->
                    change.consume()
                    drag = (change.position.x / width).coerceIn(0f, 1f)
                    preview(drag)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2
            val rh = railH.toPx()
            val r = CornerRadius(rh / 2, rh / 2)
            drawRoundRect(C.Line, Offset(0f, cy - rh / 2), Size(size.width, rh), r)
            drawRoundRect(C.Accent, Offset(0f, cy - rh / 2), Size(size.width * shown, rh), r)
            drawCircle(C.Accent, knob.toPx(), Offset(size.width * shown, cy))
        }
    }
}

fun sleepLabel(sec: Int?): String = when {
    sec == null -> "Časovač"
    sec < 0 -> "Konec kap."
    else -> "${(sec + 59) / 60} min"
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) {
        Icon(icon, desc, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun SkipButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, modifier = Modifier.size(36.dp))
        Text("30", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(icon: ImageVector, label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(C.Surface2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 6.dp), fontSize = 14.sp)
    }
}

@Composable
fun MiniPlayer(vm: MainViewModel) {
    val s by vm.playing.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val itemId = s.itemId ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface2)
            .clickable { vm.push(Screen.Player) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(itemId, s.title, Modifier.size(52.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                s.chapterAtSec(s.positionSec)?.let { append(it.second.title) }
                if (target.isRemote) append(" · na ${target.name}")
            }
            Text(sub, color = if (target.isRemote) C.Accent else C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { target.skip(-30.0) }) { Icon(Ic.Replay, "Zpět o 30 s") }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(C.Accent).clickable { target.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (s.isPlaying) Ic.Pause else Ic.Play, null, tint = C.OnAccent)
        }
    }
}
