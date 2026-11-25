package cz.ondra.absplayer.player

import cz.ondra.absplayer.data.ListeningSession
import kotlin.math.abs

enum class DeviceKind { PHONE, TV, PC, WEB }

/** A name this app knows for an ABS device id (this device, a paired TV / PC, a phone that controls this one). */
data class KnownDevice(val name: String, val kind: DeviceKind)

/** One row of "Listening history". Chapters are 0-based indexes into the book's chapters. */
data class HistoryEntry(
    val id: String,
    val startSec: Double,
    val endSec: Double,
    val startedAt: Long,
    val updatedAt: Long,
    val device: String,
    val kind: DeviceKind,
    val startChapter: Int?,
    val endChapter: Int?,
    /** Seconds from the start of [endChapter] (or of the book without chapters). */
    val endInChapterSec: Double,
    /** Ends where playback stands now. */
    val now: Boolean = false,
    /** The furthest place any session reached (shown only when it is ahead of now). */
    val furthest: Boolean = false,
)

/** Turns the server's listening sessions into history rows (pure, unit-tested). */
object History {
    const val SAME_POS = 3.0

    fun chapterAt(chapters: List<ChapterInfo>, sec: Double): Int? {
        if (chapters.isEmpty()) return null
        val i = chapters.indexOfLast { it.startSec <= sec + 0.001 }
        return if (i < 0) 0 else i
    }

    fun label(s: ListeningSession, known: Map<String, KnownDevice>): KnownDevice {
        s.deviceId?.let { id -> known[id]?.let { return it } }
        val web = s.clientName.equals("Abs Web", ignoreCase = true) || (s.model == null && s.browserName != null)
        val name = s.deviceName?.takeIf { it.isNotBlank() && !it.startsWith("Unknown") }
            ?: s.model ?: s.clientName ?: "?"
        return KnownDevice(name, if (web) DeviceKind.WEB else DeviceKind.PHONE)
    }

    fun build(
        sessions: List<ListeningSession>,
        chapters: List<ChapterInfo>,
        known: Map<String, KnownDevice>,
        positionSec: Double?,
    ): List<HistoryEntry> {
        val rows = sessions
            .filter { it.updatedAt > 0 && abs(it.endSec - it.startSec) >= 1.0 }
            .sortedByDescending { it.updatedAt }
            .map { s ->
                val d = label(s, known)
                val ec = chapterAt(chapters, s.endSec)
                HistoryEntry(
                    id = s.id, startSec = s.startSec, endSec = s.endSec,
                    startedAt = if (s.startedAt > 0) s.startedAt else s.updatedAt, updatedAt = s.updatedAt,
                    device = d.name, kind = d.kind,
                    startChapter = chapterAt(chapters, s.startSec), endChapter = ec,
                    endInChapterSec = (s.endSec - (ec?.let { chapters[it].startSec } ?: 0.0)).coerceAtLeast(0.0),
                )
            }
        if (rows.isEmpty()) return rows
        val nowIdx = if (positionSec == null) -1 else rows.indexOfFirst { abs(it.endSec - positionSec) <= SAME_POS }
        val far = rows.indices.maxBy { rows[it].endSec }
        val farAhead = rows.size >= 2 && far != nowIdx && rows[far].endSec > (positionSec ?: -1.0) + SAME_POS
        return rows.mapIndexed { i, r -> r.copy(now = i == nowIdx, furthest = farAhead && i == far) }
    }
}
