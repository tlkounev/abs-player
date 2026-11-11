package cz.ondra.absplayer.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import cz.ondra.absplayer.remote.PlaybackTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheets(vm: MainViewModel) {
    val sheet = vm.sheet ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == Sheet.Chapters || sheet == Sheet.Pair)
    ModalBottomSheet(
        onDismissRequest = { vm.sheet = null },
        sheetState = state,
        containerColor = C.Surface,
    ) {
        when (sheet) {
            Sheet.Chapters -> ChaptersSheet(vm)
            Sheet.Devices -> DevicesSheet(vm)
            Sheet.Pair -> PairSheet(vm)
            Sheet.Sleep -> SleepSheet(vm)
            Sheet.Speed -> SpeedSheet(vm)
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
}

@Composable
private fun ChaptersSheet(vm: MainViewModel) {
    val s by vm.playing.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val current = s.chapterIndex
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    SheetTitle("Kapitoly")
    LazyColumn(state = list, modifier = Modifier.heightIn(max = 560.dp)) {
        itemsIndexed(s.chapters) { i, c ->
            val isCur = i == current
            val done = c.endSec <= s.positionSec
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (isCur) C.AccentDark else C.Surface)
                    .clickable {
                        target.chapter(i)
                        vm.sheet = null
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = C.Muted, modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        c.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (isCur) C.Accent else if (done) C.Muted else C.Text,
                        fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isCur) {
                        Text(
                            "${formatClock(s.positionSec - c.startSec)} / ${formatClock(c.endSec - c.startSec)}",
                            color = C.Accent, fontSize = 13.sp,
                        )
                    }
                }
                Text(formatClock(c.endSec - c.startSec), color = C.Muted, fontSize = 13.sp)
                if (done) Icon(Ic.Check, "Dohráno", tint = C.Ok, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun DevicesSheet(vm: MainViewModel) {
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

    SheetTitle("Přehrávat na")
    Column(Modifier.padding(horizontal = 12.dp)) {
        DeviceRow(Ic.Phone, targets.local.name, "Toto zařízení", selected = current.id == targets.local.id, online = true) {
            vm.switchTarget(targets.local)
            vm.sheet = null
        }
        paired.forEach { d ->
            val rt = targets.remote(d)
            val online by rt.online.collectAsStateWithLifecycle()
            val rs by rt.state.collectAsStateWithLifecycle()
            val sub = when (online) {
                null -> "Zjišťuji…"
                false -> "Nedostupné (${d.host})"
                true -> if (rs.itemId != null) (if (rs.isPlaying) "Hraje: " else "Pozastaveno: ") + rs.title else "Připraveno"
            }
            DeviceRow(
                Ic.Tv, d.name, sub, selected = current.id == d.deviceId, online = online == true,
                onRemove = { targets.unpair(d.deviceId) },
            ) {
                vm.switchTarget(rt)
                vm.sheet = null
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.sheet = Sheet.Pair }, modifier = Modifier.fillMaxWidth()) {
            Icon(Ic.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Spárovat televizi")
        }
        Text(
            "Při přepnutí se přehrávání pozastaví, pozice se uloží na server a pokračuje na vybraném zařízení.",
            color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    name: String,
    sub: String,
    selected: Boolean,
    online: Boolean,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.AccentDark else C.Surface2)
            .clickable(enabled = online || selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) C.Accent else if (online) C.Text else C.Muted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text(sub, color = if (selected) C.Accent else C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Ic.Check, "Vybráno", tint = C.Accent)
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Ic.Close, "Zrušit spárování", tint = C.Muted) }
        }
    }
}

@Composable
private fun PairSheet(vm: MainViewModel) {
    val targets = vm.app.targets
    val found by targets.discovery.found.collectAsStateWithLifecycle()
    val paired by targets.paired.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        targets.discovery.start()
        onDispose { targets.discovery.stop() }
    }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(DEFAULT_PORT.toString()) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val unpaired = found.values.filter { f -> paired.none { it.deviceId == f.deviceId } }

    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Spárovat televizi", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Na televizi otevři ABS Player. Zobrazí párovací kód. Televize musí být ve stejné síti.",
            color = C.Muted, fontSize = 14.sp,
        )
        if (unpaired.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Hledám televize v síti…", color = C.Muted, modifier = Modifier.padding(start = 10.dp))
            }
        } else {
            unpaired.forEach { f ->
                val sel = host == f.host && port == f.port.toString()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) C.AccentDark else C.Surface2)
                        .clickable {
                            host = f.host
                            port = f.port.toString()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.Tv, null, tint = if (sel) C.Accent else C.Text)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(f.name, fontWeight = FontWeight.SemiBold)
                        Text("${f.host}:${f.port}", color = C.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { host = it.trim() },
                label = { Text("IP adresa TV") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Kód z televize") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Heslo k ABS (${vm.app.settings.username ?: ""})") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Heslo se nikam neukládá. Telefon s ním získá pro televizi vlastní přihlášení k serveru.",
            color = C.Muted, fontSize = 12.sp,
        )
        vm.pairError?.let { Text(it, color = C.Error) }
        Button(
            onClick = {
                vm.pair(host, port.toIntOrNull() ?: DEFAULT_PORT, code, password) { vm.sheet = null }
            },
            enabled = !vm.pairBusy && host.isNotBlank() && code.length == 6 && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (vm.pairBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Spárovat")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SleepSheet(vm: MainViewModel) {
    val target by vm.target.collectAsStateWithLifecycle()
    val s by vm.playing.collectAsStateWithLifecycle()
    SheetTitle("Časovač vypnutí")
    s.sleepSec?.let {
        Text(
            if (it < 0) "Zastaví se na konci kapitoly" else "Zastaví se za ${formatClock(it.toDouble())}",
            color = C.Accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    val options = listOf(5, 10, 15, 30, 45, 60, 90).map { "$it min" to it } + listOf("Konec kapitoly" to -1, "Vypnout" to 0)
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
    SheetTitle("Rychlost přehrávání")
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
            listOf(0.8f, 1.0f, 1.2f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { sp ->
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
