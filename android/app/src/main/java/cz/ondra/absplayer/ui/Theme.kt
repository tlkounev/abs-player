package cz.ondra.absplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    // Dark palette of the Windows app (popup.html)
    val Bg = Color(0xFF272727)
    val Surface = Color(0xFF2F2F2F)
    val Surface2 = Color(0xFF383838)
    val Line = Color(0x2EFFFFFF)
    val Text = Color(0xFFF2F2F2)
    val Muted = Color(0xFFB5B5B5)
    val Accent = Color(0xFFE3A54D)
    val AccentDark = Color(0xFF3A3225)
    val OnAccent = Color(0xFF1B1B1B)
    val Ok = Color(0xFF8FD19E)
    val Error = Color(0xFFFF8A80)
    val Press = Color(0x1CFFFFFF)
    val Outline = Color(0x38FFFFFF)
}

private val Scheme = darkColorScheme(
    primary = C.Accent,
    onPrimary = C.OnAccent,
    primaryContainer = C.AccentDark,
    onPrimaryContainer = C.Accent,
    secondary = C.Accent,
    onSecondary = C.OnAccent,
    background = C.Bg,
    onBackground = C.Text,
    surface = C.Bg,
    onSurface = C.Text,
    surfaceVariant = C.Surface2,
    onSurfaceVariant = C.Muted,
    surfaceContainer = C.Surface,
    surfaceContainerHigh = C.Surface2,
    surfaceContainerHighest = C.Surface2,
    surfaceContainerLow = C.Surface,
    outline = C.Line,
    outlineVariant = C.Line,
    error = C.Error,
)

@Composable
fun AbsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
