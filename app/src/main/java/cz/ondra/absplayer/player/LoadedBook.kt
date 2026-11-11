package cz.ondra.absplayer.player

import cz.ondra.absplayer.data.AbsApi
import cz.ondra.absplayer.data.Chapter
import cz.ondra.absplayer.data.PlaybackSession

data class TrackInfo(val startSec: Double, val durationSec: Double, val url: String)

/** A book opened on the server as a playback session: audio files plus chapters. */
data class LoadedBook(
    val itemId: String,
    val title: String,
    val author: String,
    val tracks: List<TrackInfo>,
    val chapters: List<Chapter>,
    val durationSec: Double,
) {
    companion object {
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
                tracks = tracks,
                chapters = chapters,
                durationSec = duration,
            )
        }
    }
}
