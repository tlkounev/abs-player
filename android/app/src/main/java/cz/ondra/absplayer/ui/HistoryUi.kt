package cz.ondra.absplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.ondra.absplayer.L
import cz.ondra.absplayer.player.DeviceKind
import cz.ondra.absplayer.player.HistoryEntry
import cz.ondra.absplayer.tr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// "Listening history": the server's sessions of a book from every device – where each one ended,
// on which device and when; a tap continues from there.

private fun locale(): Locale = Locale.forLanguageTag(L.lang)

private fun capFirst(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale()) else it.toString() }

fun historyDay(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> tr("todayH")
        today.minusDays(1) -> capFirst(tr("yesterday"))
        else -> {
            val skeleton = if (d.year == today.year) "EEEdM" else "EEEdMy"
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale(), skeleton)
            capFirst(DateTimeFormatter.ofPattern(pattern, locale()).format(d))
        }
    }
}

fun historyClock(ms: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale())
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun historyTitle(e: HistoryEntry): String =
    if (e.endChapter != null) tr("histChapterAt", "n" to e.endChapter + 1, "t" to formatClock(e.endInChapterSec))
    else formatClock(e.endSec)

fun historyLine(e: HistoryEntry): String {
    val a = historyClock(e.startedAt)
    val b = historyClock(e.updatedAt)
    return if (e.startChapter != null) tr("histLine", "d" to e.device, "a" to a, "b" to b, "n" to e.startChapter + 1)
    else tr("histLineNoCh", "d" to e.device, "a" to a, "b" to b)
}

private fun kindIcon(k: DeviceKind): ImageVector = when (k) {
    DeviceKind.TV -> Ic.WTv
    DeviceKind.PC -> Ic.WMonitor
    DeviceKind.WEB -> Ic.Web
    DeviceKind.PHONE -> Ic.WPhone
}

@Composable
private fun Pill(text: String, filled: Boolean) {
    Text(
        text, color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (filled) Modifier.background(C.Accent.copy(alpha = 0.2f))
                else Modifier.border(1.dp, C.Accent, RoundedCornerShape(10.dp)),
            )
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

@Composable
fun HistoryDayHeader(text: String) {
    Text(text, color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
}

/**
 * One session. [onPick] = continue from where it ended (null for the row of the current position).
 * [goButton]: the round ▶ on the right (the phone's player list); on the TV the focused row shows "Continue from here".
 */
@Composable
fun HistoryRow(e: HistoryEntry, onPick: (() -> Unit)?, goButton: Boolean = true, tv: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (goButton) 60.dp else 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (e.now) Modifier.background(C.Accent.copy(alpha = 0.12f)) else Modifier)
            .then(
                if (e.now) Modifier.drawBehind {
                    val w = 3.dp.toPx()
                    drawRoundRect(C.Accent, topLeft = Offset(0f, 8.dp.toPx()), size = Size(w, size.height - 16.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2))
                } else Modifier,
            )
            .then(if (onPick != null) Modifier.clickable(onClick = onPick) else Modifier)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(kindIcon(e.kind), null, tint = if (e.now) C.Accent else C.Muted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    historyTitle(e), fontSize = if (goButton) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (e.now) C.Accent else C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (e.now) Pill(tr("histNow"), filled = true)
                else if (e.furthest) Pill(tr("histFurthest"), filled = false)
            }
            Text(historyLine(e), color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (goButton && onPick != null && !tv) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (e.furthest) C.Accent else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ic.WGo, tr("histContinue"), tint = if (e.furthest) Color(0xFF1B1B1B) else C.Text, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Kapitoly | Historie – the segmented switch at the top of the chapter list. */
@Composable
fun ListTabs(
    history: Boolean, showChapters: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    focus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        @Composable
        fun tab(label: String, on: Boolean, click: () -> Unit) {
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) C.Accent else Color.Transparent)
                    .then(if (on && focus != null) Modifier.focusRequester(focus) else Modifier)
                    .clickable(onClick = click),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color(0xFF1B1B1B) else C.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (showChapters) tab(tr("chapters"), !history) { onChange(false) }
        tab(tr("history"), history) { onChange(true) }
    }
}

/** Shown instead of the rows while loading / when empty / on an error. */
@Composable
fun HistoryMessage(rows: List<HistoryEntry>?, loading: Boolean) {
    val t = when {
        loading -> "…"
        rows == null -> tr("histError")
        else -> tr("histEmpty")
    }
    Text(t, color = C.Muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
}

/** Book detail: a folding "Listening history" row under Chapters; shown when the book has any sessions. */
@Composable
fun BookHistory(
    vm: MainViewModel,
    itemId: String,
    chapters: List<cz.ondra.absplayer.player.ChapterInfo>,
    positionSec: Double?,
    onPick: (HistoryEntry) -> Unit,
) {
    var rows by androidx.compose.runtime.remember(itemId) { androidx.compose.runtime.mutableStateOf<List<HistoryEntry>?>(null) }
    var open by androidx.compose.runtime.remember(itemId) { androidx.compose.runtime.mutableStateOf(false) }
    var all by androidx.compose.runtime.remember(itemId) { androidx.compose.runtime.mutableStateOf(false) }
    val pos by androidx.compose.runtime.rememberUpdatedState(positionSec)
    val chs by androidx.compose.runtime.rememberUpdatedState(chapters)
    androidx.compose.runtime.LaunchedEffect(itemId, chapters.size) {
        rows = vm.loadHistory(itemId, chs, pos)
    }
    val list = rows ?: return
    if (list.isEmpty()) return
    val far = list.firstOrNull { it.furthest }
    Column(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.Surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Ic.History, null, modifier = Modifier.size(18.dp))
            Text(tr("listenHistory"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (far?.endChapter != null) Text(tr("histFurthestCh", "n" to far.endChapter + 1), color = C.Muted, fontSize = 13.sp)
            Icon(Ic.WDown, null, modifier = Modifier.size(16.dp).then(if (open) Modifier.graphicsLayer(rotationZ = 180f) else Modifier))
        }
        if (open) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
                val shown = if (all) list else list.take(3)
                shown.forEach { e ->
                    HistoryRow(
                        e, goButton = false,
                        onPick = if (e.now) null else ({ onPick(e) }),
                        modifier = Modifier,
                    )
                }
                if (!all && list.size > 3) {
                    Text(
                        tr("histAll", "n" to list.size), color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(start = 36.dp, top = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { all = true }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
