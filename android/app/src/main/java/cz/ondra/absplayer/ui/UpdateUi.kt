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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.data.Updater
import cz.ondra.absplayer.tr
import kotlinx.coroutines.launch
import java.util.Locale

private val BannerBg = Color(0xFF3A3225)
private val BannerLine = Color(0x73E3A54D)

/** Status line for the menu / TV footer: "Máš nejnovější verzi", "Hledám aktualizace…", error. */
@Composable
fun updateStatus(): Pair<String, Color>? {
    val st by Updater.state.collectAsStateWithLifecycle()
    return when (val s = st) {
        Updater.State.Checking -> tr("checkingUpdates") to C.Muted
        Updater.State.UpToDate -> tr("upToDate") to C.Ok
        is Updater.State.Available -> tr("updateAvailable", "v" to s.release.version) to C.Accent
        is Updater.State.Failed -> s.message to C.Error
        else -> null
    }
}

/**
 * Banner under the top bar (phone) or under the header (TV: [tvButton] = its focusable button, bigger text):
 * new version → Aktualizovat; download progress; install; error. ✕ hides a version until a newer one.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier, tvButton: (@Composable (onClick: () -> Unit) -> Unit)? = null) {
    val tv = tvButton != null
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsStateWithLifecycle()
    var skipped by remember { mutableStateOf(Updater.skipped(ctx)) }
    val title: TextUnit = if (tv) 22.sp else 14.sp
    val sub: TextUnit = if (tv) 16.sp else 12.sp
    val pad: Dp = if (tv) 20.dp else 14.dp

    val s = st
    val show = when (s) {
        is Updater.State.Available -> s.release.version != skipped
        is Updater.State.Downloading, is Updater.State.Installing, is Updater.State.Failed -> true
        else -> false
    }
    if (!show) return

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (tv) 14.dp else 12.dp))
            .background(BannerBg)
            .border(1.dp, BannerLine, RoundedCornerShape(if (tv) 14.dp else 12.dp))
            .padding(start = pad, end = if (tv) pad else 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        when (s) {
            is Updater.State.Available -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Ic.Download, null, tint = C.Accent, modifier = Modifier.size(if (tv) 30.dp else 22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(tr("updateAvailable", "v" to s.release.version), fontSize = title, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("youHave", "v" to Updater.currentVersion, "s" to String.format(Locale.ROOT, "%.0f", s.release.size / 1_048_576.0)),
                        fontSize = sub, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tvButton != null) {
                    tvButton { scope.launch { Updater.update(ctx, s.release) } }
                } else {
                    Box(
                        Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(C.Accent)
                            .clickable { scope.launch { Updater.update(ctx, s.release) } }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(tr("updateBtn"), color = C.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable {
                            Updater.skip(ctx, s.release.version)
                            skipped = s.release.version
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.Close, tr("hide"), tint = C.Muted, modifier = Modifier.size(16.dp)) }
                }
            }
            is Updater.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                Row {
                    Text(tr("downloadingV", "v" to s.release.version), fontSize = title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        String.format(Locale.ROOT, "%.1f / %.0f MB", s.done / 1_048_576.0, s.total / 1_048_576.0),
                        fontSize = sub, color = C.Muted,
                    )
                }
                val f = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
                    Box(Modifier.fillMaxWidth(f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Accent))
                }
            }
            is Updater.State.Installing -> Text(tr("installingV", "v" to s.release.version), fontSize = title, fontWeight = FontWeight.SemiBold)
            is Updater.State.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.message, fontSize = sub, color = C.Error, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { Updater.clearError() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.Close, tr("hide"), tint = C.Muted, modifier = Modifier.size(16.dp)) }
            }
            else -> Unit
        }
    }
}
