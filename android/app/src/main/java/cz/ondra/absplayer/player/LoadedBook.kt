package cz.ondra.absplayer.player

import cz.ondra.absplayer.data.AbsApi
import cz.ondra.absplayer.data.Chapter
import cz.ondra.absplayer.data.PlaybackSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class TrackInfo(val startSec: Double, val durationSec: Double, val url: String)

/** A book opened on the server as a playback session: audio files plus chapters. */
data class LoadedBook(
    val itemId: String,
    val title: String,
    val author: String,
    val series: String,
    val tracks: List<TrackInfo>,
    val chapters: List<Chapter>,
    val durationSec: Double,
) {
    companion object {
        /** "Series #2" from the session metadata (expanded `series` list or minified `seriesName`). */
        private fun seriesOf(s: PlaybackSession): String = runCatching {
            val m = s.mediaMetadata ?: return ""
            val list = m["series"] as? JsonArray
            val first = list?.firstOrNull() as? JsonObject
            if (first != null) {
                val name = (first["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val seq = (first["sequence"] as? JsonPrimitive)?.contentOrNull
                if (seq.isNullOrBlank()) name else "$name #$seq"
            } else {
                (m["seriesName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
        }.getOrDefault("")

        fun from(s: PlaybackSession, api: AbsApi): LoadedBook {
            val tracks = s.audioTracks.sortedBy { it.index }.map {
                TrackInfo(it.startOffset, it.duration, api.resolveContentUrl(it.contentUrl))
            }
            val duration = if (s.duration > 0) s.duration else tracks.sumOf { it.durationSec }
            val title = s.displayTitle ?: ""
            val chapters = s.chapters
                .filter { it.end > it.start }
                .sortedBy { it.start }
                .ifEmpty { listOf(Chapter(0, 0.0, duration, title)) }
            return LoadedBook(
                itemId = s.libraryItemId,
                title = title,
                author = s.displayAuthor ?: "",
                series = seriesOf(s),
                tracks = tracks,
                chapters = chapters,
                durationSec = duration,
            )
        }
    }
}
