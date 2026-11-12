package cz.ondra.absplayer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.player.ChapterInfo
import cz.ondra.absplayer.player.NowPlaying
import cz.ondra.absplayer.player.PlayerEngine
import cz.ondra.absplayer.tr
import kotlin.math.roundToInt

/** Chapter containing [sec] (by start time), or null for a book without chapters. */
fun NowPlaying.chapterAtSec(sec: Double): Pair<Int, ChapterInfo>? {
    if (chapters.size <= 1) return null
    var i = chapters.indexOfLast { it.startSec <= sec + 0.001 }
    if (i < 0) i = 0
    return i to chapters[i]
}

private val SKIP = PlayerEngine.SKIP_SEC

/**
 * Variant B: big cover on top, below it the Windows mini player 1:1 –
 * text | sleep timer, play-on, chapters; chapter bar + thin book bar;
 * volume | ⏮ | ↺10 | ▶ | ↻10 | ⏭ | speed. Drag down anywhere = back to the home screen.
 */
@Composable
fun PlayerScreen(vm: MainViewModel) {
    val s by vm.playing.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val itemId = s.itemId
    /** Position shown while a bar is being dragged (seconds in the book). */
    var preview by remember { mutableStateOf<Double?>(null) }
    val wide = LocalConfiguration.current.let { it.screenWidthDp >= 600 && it.screenWidthDp > it.screenHeightDp }

    // --- drag down to close ---
    val closePx = with(LocalDensity.current) { 110.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(
        if (dragging) drag else 0f,
        if (dragging) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "drag",
    )

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = offset
                alpha = 1f - (offset / (closePx * 4)).coerceIn(0f, 0.4f)
            }
            .background(C.Bg)
            .systemBarsPadding()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        drag = 0f
                        dragging = true
                    },
                    onDragEnd = {
                        if (drag > closePx) vm.pop()
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                ) { change, dy ->
                    change.consume()
                    drag = (drag + dy).coerceAtLeast(0f)
                }
            },
    ) {
        // --- handle + close ---
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            if (!vm.app.isTv) {
                // drag handle + close (touch only; on the TV the Back button closes the player)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(40.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x59FFFFFF)),
                )
                IconBtn(Ic.WDown, tr("close"), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), size = 44.dp, iconSize = 22.dp) { vm.pop() }
            }
            val status = s.error ?: s.info
            if (status != null) {
                Text(
                    status, color = if (s.error != null) C.Error else C.Ok, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(start = 60.dp, end = 16.dp, top = 10.dp),
                )
            }
        }

        if (itemId == null) {
            NothingPlaying(vm, s)
            return@Column
        }

        val pos = preview ?: s.positionSec
        val rate = s.speed.coerceAtLeast(0.1f)
        // While dragging, the chapter labels follow the dragged position.
        val ch = s.chapterAtSec(pos)

        // remote control: start on Play
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(itemId) {
            if (vm.app.isTv) {
                delay(200)
                runCatching { playFocus.requestFocus() }
            }
        }
        val coverBox: @Composable (Modifier) -> Unit = { m ->
            Box(m.aspectRatio(1f, matchHeightConstraintsFirst = true)) {
                Cover(itemId, s.title, Modifier.fillMaxSize(), corner = 10.dp)
                if (s.isLoading || s.isBuffering) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = C.Accent)
                }
            }
        }
        val infoRow: @Composable () -> Unit = {
            // --- text | timer, play on, chapters (as in Windows) ---
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(s.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(s.author, s.series).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = C.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ch != null) Text(ch.second.title, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (target.isRemote) {
                        Text(tr("playingOnT", "t" to target.name), color = C.Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val sleepOn = s.sleepSec != null
                    Row(
                        Modifier
                            .height(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.sheet = Sheet.Sleep }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Ic.WMoon, tr("sleep"), tint = if (sleepOn) C.Accent else C.Muted, modifier = Modifier.size(13.dp))
                        if (sleepOn) Text(" " + sleepLabel(s.sleepSec), color = C.Muted, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconBtn(
                            Ic.WMonitor, tr("playOn"), width = 44.dp, size = 40.dp, iconSize = 20.dp,
                            tint = if (target.isRemote) C.Accent else C.Text,
                            background = if (target.isRemote) C.Press else Color.Transparent,
                        ) { vm.sheet = Sheet.Devices }
                        if (s.chapters.size > 1) {
                            IconBtn(Ic.WChapters, tr("chapters"), width = 44.dp, size = 40.dp, iconSize = 20.dp) { vm.sheet = Sheet.Chapters }
                        }
                    }
                }
            }

        }
        val bars: @Composable () -> Unit = {
            // --- chapter bar + thin book bar, tight like Windows ---
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val dur = s.durationSec.coerceAtLeast(1.0)
                if (ch != null) {
                    val c = ch.second
                    val len = (c.endSec - c.startSec).coerceAtLeast(1.0)
                    val inCh = (pos - c.startSec).coerceIn(0.0, len)
                    SeekBar(
                        fraction = (inCh / len).toFloat(),
                        thin = false,
                        onPreview = { f -> preview = f?.let { c.startSec + it * len } },
                        onSeek = { f -> target.seekTo(c.startSec + f * len); preview = null },
                        onStep = { d -> target.skip(d * 10.0) },
                    )
                    TimesRow(
                        formatClock(inCh),
                        tr("chapterN", "n" to ch.first + 1, "total" to s.chapters.size),
                        "−" + formatClock((len - inCh) / rate),
                        labelColor = C.Text,
                        onLabel = { vm.sheet = Sheet.Chapters },
                    )
                    SeekBar(
                        fraction = (pos / dur).toFloat(),
                        thin = true,
                        onPreview = { f -> preview = f?.let { it * dur } },
                        onSeek = { f -> target.seekTo(f * dur); preview = null },
                        onStep = { d -> target.skip(d * 60.0) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    SeekBar(
                        fraction = (pos / dur).toFloat(),
                        thin = false,
                        onPreview = { f -> preview = f?.let { it * dur } },
                        onSeek = { f -> target.seekTo(f * dur); preview = null },
                        onStep = { d -> target.skip(d * 30.0) },
                    )
                }
                TimesRow(
                    formatClock(pos),
                    tr("bookPct", "p" to ((pos / dur) * 100).toInt()),
                    "−" + formatClock((dur - pos) / rate),
                )
            }

        }
        val controls: @Composable () -> Unit = {
            // --- volume | ⏮ | ↺10 | ▶ | ↻10 | ⏭ | speed ---
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = if (wide) 0.dp else 22.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VolumeButton(s, target)
                val hasCh = s.chapters.size > 1
                IconBtn(Ic.WPrev, tr("prevCh"), size = 48.dp, iconSize = 26.dp, enabled = hasCh) { target.prev() }
                IconBtn(Ic.WBack, tr("back", "s" to SKIP.toInt()), size = 48.dp, iconSize = 30.dp) { target.skip(-SKIP) }
                val playSrc = remember { MutableInteractionSource() }
                val playFocused by playSrc.collectIsFocusedAsState()
                Box(
                    Modifier
                        .graphicsLayer { val k = if (playFocused) 1.08f else 1f; scaleX = k; scaleY = k }
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(C.Accent)
                        .focusRequester(playFocus)
                        .clickable(interactionSource = playSrc, indication = FocusRing(Color.White)) { target.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (s.isPlaying) Ic.WPause else Ic.WPlay, if (s.isPlaying) tr("pause") else tr("play"),
                        tint = C.OnAccent, modifier = Modifier.size(32.dp),
                    )
                }
                IconBtn(Ic.WFwd, tr("fwd", "s" to SKIP.toInt()), size = 48.dp, iconSize = 30.dp) { target.skip(SKIP) }
                IconBtn(Ic.WNext, tr("nextCh"), size = 48.dp, iconSize = 26.dp, enabled = hasCh) { target.next() }
                SpeedButton(s, target)
            }
        }

        if (wide) {
            // TV / landscape: cover left, everything else right (reachable with the remote's arrows)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 24.dp)) {
                // the cover never reaches into the text: at most 40 % of the width
                val side = minOf(maxHeight, maxWidth * 0.4f, 420.dp)
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    coverBox(Modifier.size(side))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        infoRow()
                        bars()
                        controls()
                    }
                }
            }
        } else {
            // --- cover (takes the space that is left) ---
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { coverBox(Modifier.widthIn(max = 360.dp)) }
            infoRow()
            bars()
            controls()
        }
    }
}

/** Tap = mute / unmute, hold = vertical slider above the button (like the Windows volume popup). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VolumeButton(s: NowPlaying, target: cz.ondra.absplayer.remote.PlaybackTarget) {
    var open by remember { mutableStateOf(false) }
    val vol = if (s.muted) 0f else s.volume
    val icon = when {
        vol <= 0f -> Ic.WMuted
        vol <= 0.5f -> Ic.WVolumeLow
        else -> Ic.WVolume
    }
    Box {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (open) C.Press else Color.Transparent)
                .combinedClickable(
                    onClickLabel = tr("mute"),
                    onLongClickLabel = tr("volume"),
                    onClick = { target.toggleMute() },
                    onLongClick = { open = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, tr("volumeHint"), tint = C.Text, modifier = Modifier.size(24.dp))
        }
        if (open) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, -(with(LocalDensity.current) { 44.dp.roundToPx() } + gap)),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                VolumeSlider(s.volume.takeIf { !s.muted } ?: 0f) { v, final ->
                    if (final) target.setVolume(v)
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(initial: Float, onChange: (Float, Boolean) -> Unit) {
    var value by remember { mutableFloatStateOf(initial) }
    var h by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    fun at(y: Float) = (1f - y / h).coerceIn(0f, 1f).let { (it * 20).roundToInt() / 20f }
    Column(
        Modifier
            .width(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(10.dp))
            .padding(top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${(value * 100).roundToInt()}", color = C.Muted, fontSize = 12.sp)
        Box(
            Modifier
                .padding(top = 8.dp)
                .width(36.dp)
                .height(120.dp)
                .onSizeChanged { h = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        value = at(o.y)
                        change(value, true)
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { change(value, true) },
                    ) { c, _ ->
                        c.consume()
                        value = at(c.position.y)
                        change(value, false)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 6.dp.toPx()
                val x = size.width / 2
                val pad = 9.dp.toPx()
                val top = pad
                val bottom = size.height - pad
                val y = bottom - (bottom - top) * value
                drawRoundRect(C.Line, Offset(x - w / 2, top), Size(w, bottom - top), CornerRadius(w / 2))
                drawRoundRect(C.Accent, Offset(x - w / 2, y), Size(w, bottom - y), CornerRadius(w / 2))
                drawCircle(C.Accent, 9.dp.toPx(), Offset(x, y))
            }
        }
    }
}

/** Tap = next step (0.75–2×), hold = − value + with 0.05 steps (like the Windows mouse wheel). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedButton(s: NowPlaying, target: cz.ondra.absplayer.remote.PlaybackTarget) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    Box {
        Box(
            Modifier
                .height(34.dp)
                .widthIn(min = 58.dp)
                .clip(shape)
                .background(if (open) C.Press else Color.Transparent)
                .border(1.dp, if (open) C.Accent else C.Outline, shape)
                .combinedClickable(
                    onClickLabel = tr("speedHint"),
                    onClick = {
                        val next = PlayerEngine.SPEEDS.firstOrNull { it > s.speed + 0.001f } ?: PlayerEngine.SPEEDS.first()
                        target.setSpeed(next)
                    },
                    onLongClick = { open = true },
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(PlayerEngine.formatSpeed(s.speed), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (open) {
            val d = LocalDensity.current
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, -with(d) { (34.dp + 10.dp).roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(C.Surface)
                        .border(1.dp, C.Line, RoundedCornerShape(22.dp))
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fun nudge(delta: Float) {
                        val v = (((s.speed + delta) * 20).roundToInt() / 20f).coerceIn(0.5f, 3.0f)
                        target.setSpeed(v)
                    }
                    StepButton("−", tr("slower")) { nudge(-0.05f) }
                    Text(
                        PlayerEngine.formatSpeed(s.speed), color = C.Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp),
                    )
                    StepButton("+", tr("faster")) { nudge(0.05f) }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(C.Press).clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 22.sp, color = C.Text) }
}

/** Like the Windows mini player when nothing plays: recently listened titles. */
@Composable
private fun NothingPlaying(vm: MainViewModel, s: NowPlaying) {
    Column(Modifier.fillMaxWidth()) {
        if (s.isLoading) {
            Text(tr("loading"), color = C.Muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("continueListening"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconBtn(Ic.WMonitor, tr("playOn"), width = 44.dp, size = 40.dp, iconSize = 20.dp) { vm.sheet = Sheet.Devices }
        }
        s.error?.let { Text(it, color = C.Error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        val items = vm.continueItems.take(6)
        if (items.isEmpty()) Text(tr("nothing"), color = C.Muted, modifier = Modifier.padding(16.dp))
        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { li ->
                val p = vm.progress[li.id]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.play(li.id) }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(li.id, li.title, Modifier.size(56.dp), corner = 4.dp, progress = p?.progress)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(li.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(li.author, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.WPlay, tr("play"), tint = C.Text, modifier = Modifier.padding(end = 10.dp).size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun TimesRow(left: String, label: String, right: String, labelColor: Color = C.Muted, onLabel: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = C.Muted, fontSize = 12.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
        Text(
            label, color = labelColor, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .then(if (onLabel != null) Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLabel) else Modifier),
        )
        Text(right, color = C.Muted, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/**
 * Progress bar that can be tapped or dragged anywhere (as in the Windows app). [onPreview] gets the
 * fraction while dragging (null when done), [onSeek] the final fraction.
 * Big = 18 dp high, 5 dp rail, 16 dp knob with a ring; thin = 12 dp high, 3 dp rail, knob only while dragging.
 */
@Composable
fun SeekBar(
    fraction: Float,
    thin: Boolean,
    onPreview: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Remote control: ←/→ on the focused bar (−1 / +1). */
    onStep: ((Int) -> Unit)? = null,
) {
    val keySrc = remember { MutableInteractionSource() }
    val step by rememberUpdatedState(onStep)
    var width by remember { mutableIntStateOf(1) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val preview by rememberUpdatedState(onPreview)
    val seek by rememberUpdatedState(onSeek)
    val shown = (drag ?: fraction).coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (onStep == null) Modifier else Modifier
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { step?.invoke(-1); true }
                            Key.DirectionRight -> { step?.invoke(1); true }
                            else -> false
                        }
                    }
                    .indication(keySrc, LocalIndication.current)
                    .focusable(interactionSource = keySrc)
                    .padding(horizontal = 2.dp, vertical = 3.dp)
            )
            .height(if (thin) 12.dp else 18.dp)
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
            val rh = (if (thin) 3.dp else 5.dp).toPx()
            val r = CornerRadius(rh / 2, rh / 2)
            drawRoundRect(C.Line, Offset(0f, cy - rh / 2), Size(size.width, rh), r)
            drawRoundRect(C.Accent, Offset(0f, cy - rh / 2), Size(size.width * shown, rh), r)
            val x = (size.width * shown).coerceIn(0f, size.width)
            if (!thin) {
                drawCircle(C.Bg, 11.dp.toPx(), Offset(x, cy))
                drawCircle(C.Accent, (if (drag != null) 9.dp else 8.dp).toPx(), Offset(x, cy))
            } else if (drag != null) {
                drawCircle(C.Accent, 6.dp.toPx(), Offset(x, cy))
            }
        }
    }
}

fun sleepLabel(sec: Int?): String = when {
    sec == null -> tr("sleep")
    sec < 0 -> tr("chEndShort")
    else -> formatClock(sec.toDouble())
}

@Composable
private fun IconBtn(
    icon: ImageVector,
    desc: String,
    modifier: Modifier = Modifier,
    size: Dp,
    width: Dp = size,
    iconSize: Dp,
    enabled: Boolean = true,
    tint: Color = C.Text,
    background: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(width, size)
            .clip(if (width == size) CircleShape else RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(iconSize))
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
            .padding(start = 10.dp, end = 10.dp, bottom = 14.dp, top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.Surface)
            .clickable { vm.push(Screen.Player) }
            .padding(8.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(itemId, s.title, Modifier.size(52.dp), corner = 4.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ch = s.chapterAtSec(s.positionSec)
            val sub = buildString {
                if (ch != null) {
                    append(ch.second.title)
                    append(" · −")
                    append(formatClock((ch.second.endSec - s.positionSec).coerceAtLeast(0.0) / s.speed.coerceAtLeast(0.1f)))
                } else {
                    append("−")
                    append(formatClock((s.durationSec - s.positionSec).coerceAtLeast(0.0) / s.speed.coerceAtLeast(0.1f)))
                }
                if (target.isRemote) append(" · ").append(target.name)
            }
            Text(sub, color = if (target.isRemote) C.Accent else C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(Ic.WBack, tr("back", "s" to SKIP.toInt()), size = 44.dp, iconSize = 26.dp) { target.skip(-SKIP) }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(C.Accent).clickable { target.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (s.isPlaying) Ic.WPause else Ic.WPlay, if (s.isPlaying) tr("pause") else tr("play"), tint = C.OnAccent, modifier = Modifier.size(24.dp))
        }
    }
}
