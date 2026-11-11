package cz.ondra.absplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.player.PlayerEngine

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val s by vm.playing.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val itemId = s.itemId

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
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (target.isRemote) C.AccentDark else C.Surface2)
                    .clickable { vm.sheet = Sheet.Devices }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (target.isRemote) Ic.Tv else Ic.Phone, null, tint = if (target.isRemote) C.Accent else C.Text, modifier = Modifier.size(18.dp))
                Text(target.name, modifier = Modifier.padding(start = 8.dp), fontSize = 14.sp, color = if (target.isRemote) C.Accent else C.Text)
            }
        }

        if (itemId == null) {
            Spacer(Modifier.height(80.dp))
            Text(if (s.isLoading) "Načítám…" else "Nic se nepřehrává", color = C.Muted)
            s.error?.let { Text(it, color = C.Error, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center) }
            return@Column
        }

        Box(Modifier.padding(top = 8.dp).widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f)) {
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

        val ci = s.chapterIndex
        val ch = s.chapter
        if (ch != null) {
            Text(
                "Kapitola ${ci + 1}/${s.chapters.size} · ${ch.title}",
                color = C.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp).clickable { vm.sheet = Sheet.Chapters },
            )
        }

        // Chapter slider
        val chStart = ch?.startSec ?: 0.0
        val chLen = ((ch?.endSec ?: s.durationSec) - chStart).coerceAtLeast(1.0)
        val inChapter = (s.positionSec - chStart).coerceIn(0.0, chLen)
        var dragging by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = dragging ?: inChapter.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { target.seekTo(chStart + it) }
                dragging = null
            },
            valueRange = 0f..chLen.toFloat(),
            colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Line),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val shown = dragging?.toDouble() ?: inChapter
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(shown), color = C.Muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("−" + formatClock(chLen - shown), color = C.Muted, fontSize = 13.sp)
        }

        // Whole book
        val bookFrac = if (s.durationSec > 0) (s.positionSec / s.durationSec).toFloat().coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { bookFrac },
            color = C.Accent.copy(alpha = 0.55f), trackColor = C.Line,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Text(
            "Celá kniha ${(bookFrac * 100).toInt()} % · zbývá ${formatLeft((s.durationSec - s.positionSec) / s.speed)}",
            color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )

        // Transport
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Ic.Prev, "Předchozí kapitola", 56.dp) { target.prev() }
            SkipButton(Ic.Replay, "Zpět o 30 s") { target.skip(-30.0) }
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(C.Accent).clickable { target.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (s.isPlaying) Ic.Pause else Ic.Play, if (s.isPlaying) "Pozastavit" else "Přehrát", tint = C.OnAccent, modifier = Modifier.size(42.dp))
            }
            SkipButton(Ic.Forward, "Vpřed o 30 s") { target.skip(30.0) }
            RoundButton(Ic.Next, "Další kapitola", 56.dp) { target.next() }
        }

        // Options
        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Chip(Ic.Speed, PlayerEngine.formatSpeed(s.speed)) { vm.sheet = Sheet.Speed }
            Chip(Ic.Timer, sleepLabel(s.sleepSec)) { vm.sheet = Sheet.Sleep }
            Chip(Ic.List, "Kapitoly") { vm.sheet = Sheet.Chapters }
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun sleepLabel(sec: Int?): String = when {
    sec == null -> "Časovač"
    sec < 0 -> "Konec kap."
    else -> "${(sec + 59) / 60} min"
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, size: Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
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

@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(C.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
                s.chapter?.let { append(it.title) }
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
