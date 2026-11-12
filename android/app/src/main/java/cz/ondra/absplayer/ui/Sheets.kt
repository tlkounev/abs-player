package cz.ondra.absplayer.ui

import cz.ondra.absplayer.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.player.PlayerEngine
import cz.ondra.absplayer.remote.DEFAULT_PORT
import cz.ondra.absplayer.remote.InfoResponse
import cz.ondra.absplayer.remote.PlaybackTarget
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheets(vm: MainViewModel) {
    val sheet = vm.sheet ?: return
    // always fully open: on the TV a half-open sheet cut off the bottom (pairing code)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.sheet = null },
        sheetState = state,
        containerColor = C.Surface,
    ) {
        when (sheet) {
            Sheet.Chapters -> ChaptersSheet(vm)
            Sheet.Devices -> DevicesSheet(vm)
            Sheet.Pair -> DevicesSheet(vm, pairInitially = true)
            Sheet.Sleep -> SleepSheet(vm)
            Sheet.Speed -> SpeedSheet(vm)
            Sheet.Volume -> VolumeSheet(vm)
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
}

private fun norm(x: String): String =
    java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** As in the Windows app: search by title (ignoring diacritics) or number, current chapter highlighted with progress. */
@Composable
private fun ChaptersSheet(vm: MainViewModel) {
    val s by vm.playing.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val current = s.chapterIndex
    var query by remember { mutableStateOf("") }
    val q = norm(query.trim())
    val shown = s.chapters.withIndex().filter { (i, c) -> q.isEmpty() || norm(c.title).contains(q) || "${i + 1}" == q }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    LaunchedEffect(q) { if (q.isNotEmpty()) list.scrollToItem(0) }

    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("chapters"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("  ${s.chapters.size}", color = C.Muted, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        AbsTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(tr("search")) },
            leadingIcon = { Icon(Ic.Search, null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.width(190.dp),
        )
    }
    LazyColumn(state = list, modifier = Modifier.heightIn(max = 560.dp)) {
        if (shown.isEmpty()) item { Text("—", color = C.Muted, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
        items(shown.size) { k ->
            val (i, c) = shown[k]
            val isCur = i == current
            val done = c.endSec <= s.positionSec && !isCur
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (isCur) C.AccentDark else C.Surface)
                    .clickable {
                        target.chapter(i)
                        vm.sheet = null
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = if (isCur) C.Accent else C.Muted, modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        c.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (isCur) C.Accent else if (done) C.Muted else C.Text,
                        fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isCur) {
                        val len = (c.endSec - c.startSec).coerceAtLeast(1.0)
                        val frac = ((s.positionSec - c.startSec) / len).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp).background(C.Line)) {
                            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(C.Accent))
                        }
                    }
                }
                Text(formatClock(c.endSec - c.startSec), color = if (isCur) C.Accent else C.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                if (done) Icon(Ic.Check, tr("done"), tint = C.Ok, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                if (isCur) Icon(Ic.VolumeDown, tr("playingNow"), tint = C.Accent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** Hold on the volume chip: slider + mute, as the Windows app volume popup. */
@Composable
private fun VolumeSheet(vm: MainViewModel) {
    val target by vm.target.collectAsStateWithLifecycle()
    val s by vm.playing.collectAsStateWithLifecycle()
    var value by remember { mutableFloatStateOf(if (s.muted) 0f else s.volume) }
    SheetTitle(tr("volume"))
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            target.toggleMute()
            value = if (value > 0f) 0f else s.volume.takeIf { it > 0f } ?: 0.5f
        }) { Icon(if (value <= 0f) Ic.VolumeOff else Ic.VolumeUp, tr("mute")) }
        Slider(
            value = value,
            onValueChange = { value = Math.round(it * 20) / 20f },
            onValueChangeFinished = { target.setVolume(value) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Line),
            modifier = Modifier.weight(1f),
        )
        Text("${(value * 100).toInt()} %", modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
    Text(
        tr("volumeNote"),
        color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun DevicesSheet(vm: MainViewModel, pairInitially: Boolean = false) {
    val targets = vm.app.targets
    val current by vm.target.collectAsStateWithLifecycle()
    val paired by targets.paired.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        targets.discovery.start()
        onDispose { targets.discovery.stop() }
    }
    LaunchedEffect(paired) {
        paired.forEach { targets.remote(it).refresh() }
    }

    // same as the Windows panel: this device first ("toto zařízení"), paired ones with ×, renaming with the
    // pencil, "+ Spárovat zařízení" opens the pairing form right here, this device's pairing code at the bottom
    var renaming by remember { mutableStateOf<String?>(null) }
    var pairOpen by remember { mutableStateOf(pairInitially) }
    val scroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .verticalScroll(scroll)
            .imePadding(),
    ) {
        Text(tr("playOn"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
        DeviceRow(
            if (vm.app.isTv) Ic.Tv else Ic.Phone, targets.local.name, tr("thisDevice").lowercase(),
            selected = current.id == targets.local.id, online = true,
            renaming = renaming == targets.local.id,
            onRename = { renaming = targets.local.id },
            onRenamed = { n -> vm.app.settings.localName = n; renaming = null },
        ) {
            vm.switchTarget(targets.local)
            vm.sheet = null
        }
        paired.forEach { d ->
            val rt = targets.remote(d)
            val online by rt.online.collectAsStateWithLifecycle()
            val rs by rt.state.collectAsStateWithLifecycle()
            val sub = when (online) {
                null -> tr("checking")
                false -> tr("offlineHost", "h" to d.host)
                true -> if (rs.itemId != null) tr(if (rs.isPlaying) "playingT" else "pausedT", "t" to rs.title) else tr("ready")
            }
            DeviceRow(
                if (d.kind == "pc") Ic.Computer else Ic.Tv, d.name, sub, selected = current.id == d.deviceId, online = online == true,
                renaming = renaming == d.deviceId,
                onRename = { renaming = d.deviceId },
                onRenamed = { n -> targets.rename(d.deviceId, n); renaming = null },
                onRemove = { targets.unpair(d.deviceId) },
            ) {
                vm.switchTarget(rt)
                vm.sheet = null
            }
        }
        Spacer(Modifier.height(10.dp))
        if (pairOpen) {
            PairSection(vm, onCancel = { pairOpen = false })
        } else {
            OutlinedButton(
                onClick = { pairOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    // TV: the texts under the button cannot take focus – show them when the button gets it
                    .onFocusChanged { if (it.isFocused) scope.launch { scroll.animateScrollTo(scroll.maxValue) } },
                shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, C.Outline),
            ) {
                Text("+ " + tr("pairNewBtn"), color = C.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        if (vm.app.isTv) {
            val port by cz.ondra.absplayer.tv.ReceiverService.port.collectAsStateWithLifecycle()
            Text(
                buildAnnotatedString {
                    append(tr("pairCodeThis") + " ")
                    withStyle(SpanStyle(color = C.Text, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)) {
                        append(cz.ondra.absplayer.tv.ReceiverService.pairingCode.chunked(3).joinToString(" "))
                    }
                    append(" · " + (cz.ondra.absplayer.tv.ReceiverService.lanAddress() ?: "?") + ":" + (if (port > 0) port else "…"))
                },
                color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
            OverlayPermission()
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    name: String,
    sub: String,
    selected: Boolean,
    online: Boolean,
    renaming: Boolean = false,
    onRename: (() -> Unit)? = null,
    onRenamed: (String) -> Unit = {},
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .alpha(if (online || selected) 1f else 0.55f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) C.AccentDark else Color.Transparent)
            .drawBehind {
                // accent bar on the left of the selected device (as in Windows)
                if (selected) drawRect(C.Accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            }
            .then(if (renaming) Modifier else Modifier.clickable(enabled = online || selected, onClick = onClick))
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) C.Accent else C.Text, modifier = Modifier.size(22.dp))
        if (renaming) {
            var text by remember { mutableStateOf(name) }
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            AbsTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(tr("newName")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRenamed(text) }),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).focusRequester(fr),
            )
            IconButton(onClick = { onRenamed(text) }) { Icon(Ic.Check, tr("save"), tint = C.Accent) }
        } else {
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = if (selected) C.Accent else C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onRename != null) {
                IconButton(onClick = onRename) { Icon(Ic.Edit, tr("rename"), tint = C.Muted, modifier = Modifier.size(18.dp)) }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Ic.Close, tr("unpair"), tint = C.Muted, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

/** Pairing form inside "Přehrávat na" (as in Windows): devices found in the network, IP, port, code. */
@Composable
private fun PairSection(vm: MainViewModel, onCancel: () -> Unit) {
    val targets = vm.app.targets
    val found by targets.discovery.found.collectAsStateWithLifecycle()
    val paired by targets.paired.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(DEFAULT_PORT.toString()) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    /** null = unknown (not checked yet / unreachable) */
    var info by remember { mutableStateOf<InfoResponse?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }
    val unpaired = found.values
        .filter { f -> paired.none { it.deviceId == f.deviceId } }
        .distinctBy { it.deviceId ?: (it.host + ":" + it.port) }
    val codeFocus = remember { FocusRequester() }

    // Ask the selected device what it is and whether it needs the password.
    LaunchedEffect(host, port) {
        info = null
        infoError = null
        val p = port.toIntOrNull() ?: return@LaunchedEffect
        if (host.isBlank()) return@LaunchedEffect
        delay(400)
        try {
            info = targets.info(host, p)
        } catch (e: Exception) {
            // nothing on that port: the TV may be on the next one (8766 when 8765 is taken)
            val other = cz.ondra.absplayer.remote.Discovery.SCAN_PORTS.filter { it != p }.firstNotNullOfOrNull { q ->
                runCatching { q to targets.info(host, q) }.getOrNull()
            }
            if (other != null) port = other.first.toString() // runs this again and fills info
            else infoError = tr("noResponseAt", "h" to "$host:$p")
        }
    }
    val needPassword = info?.let { targets.needsPassword(it) } ?: false
    val isPc = info?.kind == "pc"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("pairTitle"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.pairError = null; onCancel() }) { Icon(Ic.Close, tr("close"), tint = C.Muted, modifier = Modifier.size(20.dp)) }
        }
        if (unpaired.isEmpty()) {
            var long by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(10_000); long = true }
            Text(tr("searchingNet"), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            // nothing found after 10 s: the TV shows its address at the bottom of "Přehrávat na"
            if (long) Text(tr("notFoundHint"), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        } else {
            unpaired.forEach { f ->
                val sel = host == f.host && port == f.port.toString()
                DeviceRow(if (f.kind == "pc") Ic.Computer else Ic.Tv, f.name, "${f.host}:${f.port}", selected = sel, online = true) {
                    host = f.host
                    port = f.port.toString()
                    runCatching { codeFocus.requestFocus() }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AbsTextField(
                value = host, onValueChange = { host = it.trim() },
                label = { Text(tr("ipAddr")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(2f),
            )
            AbsTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text(tr("port")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        AbsTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text(tr("codeFromDevice")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
        )
        if (needPassword) {
            AbsTextField(
                value = password, onValueChange = { password = it },
                label = { Text(tr("absPassword", "u" to (vm.app.settings.username ?: ""))) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val hint = when {
            info != null -> (if (isPc) "PC: " else "TV: ") + info!!.name +
                (if (info!!.loggedIn && !isPc) " · " + tr("loggedInAs", "u" to (info!!.username ?: "?")) else "") +
                (if (needPassword) " · " + tr("tvNotLoggedIn") else "")
            infoError != null -> infoError!!
            else -> tr("pairHelp")
        }
        Text(hint, color = if (info != null) C.Ok else C.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        vm.pairError?.let { Text(it, color = C.Error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
        Button(
            onClick = {
                vm.pair(host, port.toIntOrNull() ?: DEFAULT_PORT, code, password.ifEmpty { null }) { vm.sheet = null }
            },
            // active as soon as there is an address and the code; what is wrong is said after pressing it
            enabled = !vm.pairBusy && host.isNotBlank() && code.length == 6 && (!needPassword || password.isNotEmpty()),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            if (vm.pairBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(tr("pairBtn"))
        }
    }
}

@Composable
private fun SleepSheet(vm: MainViewModel) {
    val target by vm.target.collectAsStateWithLifecycle()
    val s by vm.playing.collectAsStateWithLifecycle()
    SheetTitle(tr("sleep"))
    s.sleepSec?.let {
        Text(
            if (it < 0) tr("stopsAtChEnd") else tr("stopsIn", "t" to formatClock(it.toDouble())),
            color = C.Accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    val options = listOf(5, 10, 15, 30, 45, 60, 90).map { "$it min" to it } + listOf(tr("chapterEnd") to -1, tr("turnOff") to 0)
    Column(Modifier.padding(bottom = 16.dp)) {
        options.forEach { (label, min) ->
            Text(
                label, fontSize = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        target.setSleep(min)
                        vm.sheet = null
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(vm: MainViewModel) {
    val target: PlaybackTarget by vm.target.collectAsStateWithLifecycle()
    val s by vm.playing.collectAsStateWithLifecycle()
    var value by remember { mutableFloatStateOf(s.speed) }
    SheetTitle(tr("speedTitle"))
    Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(PlayerEngine.formatSpeed(value), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = C.Accent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                value = ((value - 0.05f) * 20).let { Math.round(it) / 20f }.coerceAtLeast(0.5f)
                target.setSpeed(value)
            }) { Text("−", fontSize = 26.sp) }
            Slider(
                value = value,
                onValueChange = { value = Math.round(it * 20) / 20f },
                onValueChangeFinished = { target.setSpeed(value) },
                valueRange = 0.5f..3.0f,
                colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Line),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                value = ((value + 0.05f) * 20).let { Math.round(it) / 20f }.coerceAtMost(3.0f)
                target.setSpeed(value)
            }) { Text("+", fontSize = 26.sp) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerEngine.SPEEDS.forEach { sp ->
                FilterChip(
                    selected = kotlin.math.abs(value - sp) < 0.01f,
                    onClick = {
                        value = sp
                        target.setSpeed(sp)
                    },
                    label = { Text(PlayerEngine.formatSpeed(sp)) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * TV: to open the app by itself when playback is sent here, Android needs "Display over other apps".
 * Some TVs have no settings screen for it – then the adb command is shown.
 */
@Composable
private fun OverlayPermission() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    var noScreen by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = android.provider.Settings.canDrawOverlays(ctx)
        onPauseOrDispose { }
    }
    if (granted) return
    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("overlayHint"), color = C.Muted, fontSize = 13.sp)
        if (noScreen) {
            Text(tr("overlayAdb"), color = C.Muted, fontSize = 13.sp)
            Text("adb shell appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow", color = C.Text, fontSize = 13.sp)
        } else {
            OutlinedButton(onClick = {
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                noScreen = runCatching { ctx.startActivity(i) }.isFailure
            }) { Text(tr("overlayAllow")) }
        }
    }
}
