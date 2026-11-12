package cz.ondra.absplayer.tv

import cz.ondra.absplayer.tr
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.App
import cz.ondra.absplayer.data.AbsApi
import cz.ondra.absplayer.data.LibraryItem
import cz.ondra.absplayer.player.NowPlaying
import cz.ondra.absplayer.player.PlaybackHub
import cz.ondra.absplayer.player.PlayerEngine
import cz.ondra.absplayer.ui.AbsTheme
import cz.ondra.absplayer.ui.C
import cz.ondra.absplayer.ui.Cover
import cz.ondra.absplayer.ui.Ic
import cz.ondra.absplayer.ui.formatClock
import cz.ondra.absplayer.ui.formatLeft
import cz.ondra.absplayer.ui.sleepLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.start(this)
        val app = application as App
        setContent {
            AbsTheme {
                Box(Modifier.fillMaxSize().background(C.Bg)) {
                    TvRoot(app)
                }
            }
        }
    }
}

private fun engine(block: (PlayerEngine) -> Unit) = PlaybackHub.whenReady(block)

private fun lanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

@Composable
private fun TvRoot(app: App) {
    val loggedIn by app.loggedIn.collectAsStateWithLifecycle()
    val port by ReceiverService.port.collectAsStateWithLifecycle()
    val s by PlaybackHub.state.collectAsStateWithLifecycle()
    var manualLogin by remember { mutableStateOf(false) }
    when {
        loggedIn -> TvHome(app, s, port)
        manualLogin -> TvLogin(app) { manualLogin = false }
        else -> TvPairing(port) { manualLogin = true }
    }
}

@Composable
private fun TvPairing(port: Int, onManual: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ABS Player", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = C.Accent)
        Spacer(Modifier.height(24.dp))
        Text(tr("tvPairHelp"), fontSize = 22.sp, color = C.Muted)
        Spacer(Modifier.height(24.dp))
        Text(ReceiverService.pairingCode.chunked(3).joinToString(" "), fontSize = 88.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (port > 0) tr("tvAddress", "a" to "${lanAddress() ?: "?"} : $port") else tr("startingReceiver"),
            fontSize = 20.sp, color = C.Muted,
        )
        Spacer(Modifier.height(40.dp))
        TvButton(onClick = onManual, modifier = Modifier.focusRequester(focus)) {
            Text(tr("signInManually"), fontSize = 20.sp)
        }
    }
}

@Composable
private fun TvLogin(app: App, onBack: () -> Unit) {
    var server by remember { mutableStateOf(app.settings.serverUrl ?: "") }
    var user by remember { mutableStateOf(app.settings.username ?: "") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr("signInTitle"), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(server, { server = it }, label = { Text(tr("serverAddr")) }, singleLine = true, modifier = Modifier.width(600.dp))
        OutlinedTextField(user, { user = it }, label = { Text(tr("user")) }, singleLine = true, modifier = Modifier.width(600.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text(tr("password")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.width(600.dp),
        )
        error?.let { Text(it, color = C.Error) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(onClick = {
                val url = AbsApi.normalizeServer(server)
                scope.launch {
                    try {
                        app.onLoggedIn(url, app.api.login(url, user.trim(), pass))
                    } catch (e: Exception) {
                        error = e.message ?: tr("loginFailed")
                    }
                }
            }) { Text(tr("signIn"), fontSize = 20.sp) }
            TvButton(onClick = onBack) { Text(tr("back0"), fontSize = 20.sp) }
        }
    }
}

@Composable
private fun TvHome(app: App, s: NowPlaying, port: Int) {
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { items = app.api.itemsInProgress() }
            delay(60_000)
        }
    }
    val playFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(s.itemId != null, items.isNotEmpty()) {
        runCatching { if (s.itemId != null) playFocus.requestFocus() else if (items.isNotEmpty()) firstFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ABS Player", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Accent)
            Spacer(Modifier.weight(1f))
            Text(
                tr("pairingCodeT", "c" to ReceiverService.pairingCode) + " · ${lanAddress() ?: "?"}:$port",
                fontSize = 15.sp, color = C.Muted,
            )
        }
        Spacer(Modifier.height(24.dp))

        val itemId = s.itemId
        if (itemId != null) {
            Row(Modifier.fillMaxWidth().height(330.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Cover(itemId, s.title, Modifier.size(330.dp), corner = 18.dp)
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        (s.error ?: s.info)?.let {
                            Text(it, color = if (s.error != null) C.Error else C.Ok, fontSize = 16.sp)
                        }
                        Text(s.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(s.author, fontSize = 20.sp, color = C.Muted)
                        s.chapter?.let {
                            Text(
                                tr("chapterN", "n" to s.chapterIndex + 1, "total" to s.chapters.size) + " · " + it.title,
                                fontSize = 20.sp, color = C.Accent, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    Column {
                        val ch = s.chapter
                        val start = ch?.startSec ?: 0.0
                        val len = ((ch?.endSec ?: s.durationSec) - start).coerceAtLeast(1.0)
                        val inCh = (s.positionSec - start).coerceIn(0.0, len)
                        LinearProgressIndicator(
                            progress = { (inCh / len).toFloat() }, color = C.Accent, trackColor = C.Line,
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text(formatClock(inCh), color = C.Muted, fontSize = 16.sp)
                            Spacer(Modifier.weight(1f))
                            Text("−" + formatClock(len - inCh), color = C.Muted, fontSize = 16.sp)
                        }
                        val frac = if (s.durationSec > 0) (s.positionSec / s.durationSec).toFloat() else 0f
                        LinearProgressIndicator(
                            progress = { frac }, color = C.Accent.copy(alpha = 0.5f), trackColor = C.Line,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                        )
                        Text(
                            tr("wholeBook", "p" to (frac * 100).toInt(), "t" to formatLeft((s.durationSec - s.positionSec) / s.speed)),
                            color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvButton(onClick = { engine { it.prevChapter() } }) { Icon(Ic.Prev, tr("prevCh")) }
                        TvButton(onClick = { engine { it.skip(-10.0) } }) { Icon(Ic.Replay, null); Text(" 10", fontSize = 18.sp) }
                        TvButton(onClick = { engine { it.toggle() } }, modifier = Modifier.focusRequester(playFocus)) {
                            Icon(if (s.isPlaying) Ic.Pause else Ic.Play, if (s.isPlaying) tr("pause") else tr("play"), Modifier.size(34.dp))
                        }
                        TvButton(onClick = { engine { it.skip(10.0) } }) { Icon(Ic.Forward, null); Text(" 10", fontSize = 18.sp) }
                        TvButton(onClick = { engine { it.nextChapter() } }) { Icon(Ic.Next, tr("nextCh")) }
                        TvButton(onClick = { engine { it.cycleSpeed() } }) { Text(PlayerEngine.formatSpeed(s.speed), fontSize = 18.sp) }
                        TvButton(onClick = {
                            engine { e ->
                                val cur = s.sleepSec
                                e.setSleep(
                                    when {
                                        cur == null -> 15
                                        cur < 0 -> 0
                                        cur <= 15 * 60 -> 30
                                        cur <= 30 * 60 -> 60
                                        cur <= 60 * 60 -> -1
                                        else -> 0
                                    }
                                )
                            }
                        }) { Icon(Ic.Timer, null); Text(" " + sleepLabel(s.sleepSec), fontSize = 18.sp) }
                    }
                }
            }
        } else {
            Text(tr("tvNothing"), fontSize = 22.sp, color = C.Muted)
        }

        Spacer(Modifier.height(32.dp))
        Text(tr("continueListening"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items, key = { it.id }) { li ->
                val m = if (li.id == items.firstOrNull()?.id) Modifier.focusRequester(firstFocus) else Modifier
                TvCard(li, m) { engine { it.playItem(li.id) } }
            }
        }
    }
}

@Composable
private fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    CompositionLocalProvider(LocalContentColor provides if (focused) C.OnAccent else C.Text) {
        Row(
            modifier
                .clip(RoundedCornerShape(32.dp))
                .background(if (focused) C.Accent else C.Surface2)
                .clickable(interactionSource = src, indication = null, onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun TvCard(li: LibraryItem, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Column(
        modifier
            .width(170.dp)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(4.dp, if (focused) C.Accent else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(4.dp)
        ) {
            Cover(li.id, li.title, Modifier.fillMaxSize(), corner = 10.dp)
        }
        Text(
            li.title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (focused) C.Accent else C.Text, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
