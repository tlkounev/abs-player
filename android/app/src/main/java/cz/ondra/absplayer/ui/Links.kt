package cz.ondra.absplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.ondra.absplayer.data.LibraryItem

/**
 * Authors and series of a book as links (accent, focusable by the remote): an author opens his
 * books, a series its books in series order. Without ids (not loaded yet) plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaLinks(
    vm: MainViewModel,
    item: LibraryItem?,
    fallbackAuthor: String = "",
    fallbackSeries: String = "",
    center: Boolean = false,
    fontSize: TextUnit = 15.sp,
) {
    val md = item?.media?.metadata
    val authors = md?.authors.orEmpty().filter { !it.id.isNullOrBlank() && it.name.isNotBlank() }
    val series = md?.seriesRefs.orEmpty().filter { it.id.isNotBlank() }
    val lib = item?.libraryId
    FlowRow(
        horizontalArrangement = if (center) Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (authors.isNotEmpty()) {
            authors.forEachIndexed { i, a ->
                if (i > 0) Text(",", color = C.Muted, fontSize = fontSize)
                Link(a.name, fontSize) { vm.openAuthor(lib, a.id!!, a.name) }
            }
        } else {
            (md?.author ?: fallbackAuthor).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = fontSize, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
        if (series.isNotEmpty()) {
            Text("·", color = C.Muted, fontSize = fontSize)
            series.forEachIndexed { i, s ->
                if (i > 0) Text(",", color = C.Muted, fontSize = fontSize)
                Link(s.label, fontSize) { vm.openSeries(lib, s.id, s.name) }
            }
        } else {
            (md?.seriesLabel ?: fallbackSeries).takeIf { it.isNotBlank() }?.let {
                Text("·", color = C.Muted, fontSize = fontSize)
                Text(it, color = C.Muted, fontSize = fontSize, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun Link(text: String, fontSize: TextUnit, onClick: () -> Unit) {
    Text(
        text, color = C.Accent, fontSize = fontSize, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = FocusRing(C.Accent, 6.dp), onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
