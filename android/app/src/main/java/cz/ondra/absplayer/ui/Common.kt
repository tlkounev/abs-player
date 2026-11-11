package cz.ondra.absplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import cz.ondra.absplayer.player.ArtworkProvider
import kotlin.math.abs

private val placeholderColors = listOf(
    Color(0xFF3B5BDB), Color(0xFF0B7285), Color(0xFF862E9C), Color(0xFFC2255C), Color(0xFF5C940D), Color(0xFFE8590C),
)

/** Book cover from the server (through [ArtworkProvider]); a colored tile with the title while loading. */
@Composable
fun Cover(
    itemId: String,
    title: String,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    progress: Double? = null,
) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape)) {
        SubcomposeAsyncImage(
            model = ArtworkProvider.uriFor(itemId),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Placeholder(itemId, title) },
            error = { Placeholder(itemId, title) },
        )
        if (progress != null && progress > 0) {
            ProgressStrip(progress)
        }
    }
}

@Composable
private fun Placeholder(itemId: String, title: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(placeholderColors[abs(itemId.hashCode()) % placeholderColors.size])
            .padding(10.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BoxScope.ProgressStrip(progress: Double) {
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(5.dp)
            .background(Color(0x73000000))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.toFloat().coerceIn(0f, 1f))
                .height(5.dp)
                .background(C.Accent)
        )
    }
}

fun formatClock(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

fun formatLeft(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}
