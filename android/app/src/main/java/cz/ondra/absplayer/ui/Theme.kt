package cz.ondra.absplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF0F1115)
    val Surface = Color(0xFF171A20)
    val Surface2 = Color(0xFF1F232A)
    val Line = Color(0xFF2B2F37)
    val Text = Color(0xFFEEF0F3)
    val Muted = Color(0xFFA3A9B3)
    val Accent = Color(0xFFF2A33A)
    val AccentDark = Color(0xFF2A2116)
    val OnAccent = Color(0xFF1A1205)
    val Ok = Color(0xFF8FD19E)
    val Error = Color(0xFFFF8A80)
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
