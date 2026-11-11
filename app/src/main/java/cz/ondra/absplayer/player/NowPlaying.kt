package cz.ondra.absplayer.player

import kotlinx.serialization.Serializable

@Serializable
data class ChapterInfo(val title: String, val startSec: Double, val endSec: Double)

/**
 * Everything a UI needs to draw the player. Produced by [PlayerEngine] on this device and sent as
 * JSON by the TV receiver, so the phone draws a remote TV with the same code as local playback.
 */
@Serializable
data class NowPlaying(
    val itemId: String? = null,
    val title: String = "",
    val author: String = "",
    val chapters: List<ChapterInfo> = emptyList(),
    val durationSec: Double = 0.0,
    val positionSec: Double = 0.0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLoading: Boolean = false,
    val speed: Float = 1f,
    /** 0..1, the level before muting. */
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** Seconds left on the sleep timer; -1 = end of chapter; null = off. */
    val sleepSec: Int? = null,
    /** Short status line, e.g. "Pozice převzata z: Windows". */
    val info: String? = null,
    val error: String? = null,
) {
    val chapterIndex: Int
        get() {
            if (chapters.isEmpty()) return -1
            var i = chapters.indexOfLast { it.startSec <= positionSec + 0.001 }
            if (i < 0) i = 0
            return i
        }
    val chapter: ChapterInfo? get() = chapters.getOrNull(chapterIndex)
}
