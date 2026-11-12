package cz.ondra.absplayer.player

import cz.ondra.absplayer.tr
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import cz.ondra.absplayer.App
import cz.ondra.absplayer.data.Library
import cz.ondra.absplayer.data.LibraryItem
import cz.ondra.absplayer.data.MediaProgress
import cz.ondra.absplayer.data.Series

/**
 * Android Auto browse tree. Top-level folders become tabs in the car:
 *   Pokračovat | Knihovna | Série | Nové
 */
class BrowseTree(private val app: App) {

    private var libraries: List<Library>? = null
    private var progress: Map<String, MediaProgress> = emptyMap()
    private val seriesCache = mutableMapOf<String, List<Series>>()

    private suspend fun bookLibraries(): List<Library> =
        libraries ?: app.api.libraries().filter { it.mediaType == "book" }.also { libraries = it }

    private suspend fun refreshProgress() {
        runCatching { app.api.me() }.getOrNull()?.let { u ->
            progress = u.mediaProgress.filter { it.episodeId == null }.associateBy { it.libraryItemId }
        }
    }

    fun root(): MediaItem = folder(ROOT, "ABS Player")

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val size = pageSize.coerceIn(1, 200)
        return when {
            parentId == ROOT -> listOf(
                folder(CONTINUE, tr("tabContinue"), grid = true),
                folder(LIBRARY, tr("tabLibrary"), grid = true),
                folder(SERIES, tr("tabSeries")),
                folder(RECENT, tr("newest"), grid = true),
            )

            parentId == CONTINUE -> {
                if (page > 0) return emptyList()
                refreshProgress()
                app.api.itemsInProgress().take(size).map { book(it) }
            }

            parentId == LIBRARY -> {
                val libs = bookLibraries()
                if (libs.size == 1) libraryPage(libs[0].id, page, size)
                else if (page > 0) emptyList()
                else libs.map { folder("$LIB${it.id}", it.name, grid = true) }
            }

            parentId.startsWith(LIB) -> libraryPage(parentId.removePrefix(LIB), page, size)

            parentId == SERIES -> {
                val libs = bookLibraries()
                if (libs.size == 1) seriesFolders(libs[0].id, page, size)
                else if (page > 0) emptyList()
                else libs.map { folder("$LIB_SERIES${it.id}", it.name) }
            }

            parentId.startsWith(LIB_SERIES) -> seriesFolders(parentId.removePrefix(LIB_SERIES), page, size)

            parentId.startsWith(SERIES_ONE) -> {
                if (page > 0) return emptyList()
                val (libId, seriesId) = parentId.removePrefix(SERIES_ONE).split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (progress.isEmpty()) refreshProgress()
                app.api.seriesBooks(libId, seriesId).map { book(it) }
            }

            parentId == RECENT -> {
                if (page > 0) return emptyList()
                val lib = bookLibraries().firstOrNull() ?: return emptyList()
                if (progress.isEmpty()) refreshProgress()
                app.api.libraryItems(lib.id, 0, minOf(size, 50), sort = "addedAt", desc = true).results.map { book(it) }
            }

            else -> emptyList()
        }
    }

    private suspend fun libraryPage(libId: String, page: Int, size: Int): List<MediaItem> {
        if (page == 0) refreshProgress()
        return app.api.libraryItems(libId, page, size).results.map { book(it) }
    }

    private suspend fun seriesFolders(libId: String, page: Int, size: Int): List<MediaItem> {
        val all = seriesCache[libId] ?: app.api.series(libId, 0, 500).results.also { seriesCache[libId] = it }
        return all.drop(page * size).take(size).map { s ->
            val first = s.books.firstOrNull()
            folder(
                "$SERIES_ONE$libId|${s.id}", s.name,
                subtitle = if (s.books.isNotEmpty()) "${s.books.size} knih" else null,
                artworkItemId = first?.id,
                grid = true,
            )
        }
    }

    suspend fun search(query: String): List<MediaItem> {
        if (progress.isEmpty()) refreshProgress()
        return bookLibraries().flatMap { runCatching { app.api.search(it.id, query) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .map { book(it) }
    }

    suspend fun item(mediaId: String): MediaItem? {
        val id = ChapterPlayer.itemIdOf(mediaId) ?: return when (mediaId) {
            ROOT -> root()
            else -> folder(mediaId, "")
        }
        return book(app.api.item(id))
    }

    // ------------------------------------------------------------------ builders

    private fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkItemId: String? = null,
        grid: Boolean = false,
    ): MediaItem {
        val extras = Bundle().apply {
            putInt(STYLE_PLAYABLE, if (grid) STYLE_GRID else STYLE_LIST)
            putInt(STYLE_BROWSABLE, if (grid) STYLE_GRID else STYLE_LIST)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artworkItemId?.let { ArtworkProvider.uriFor(it) })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun book(li: LibraryItem): MediaItem {
        val p = progress[li.id]
        val extras = Bundle()
        if (p != null) {
            when {
                p.isFinished -> extras.putInt(COMPLETION_STATUS, 2)
                p.progress > 0 -> {
                    extras.putInt(COMPLETION_STATUS, 1)
                    extras.putDouble(COMPLETION_PERCENTAGE, p.progress.coerceIn(0.0, 1.0))
                }
                else -> extras.putInt(COMPLETION_STATUS, 0)
            }
        } else {
            extras.putInt(COMPLETION_STATUS, 0)
        }
        val remaining = if (p != null && li.media.duration != null && !p.isFinished) {
            tr("left", "t" to formatDuration(li.media.duration - p.currentTime))
        } else li.media.duration?.let { formatDuration(it) }
        return MediaItem.Builder()
            .setMediaId("book:${li.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(li.title)
                    .setArtist(li.author)
                    .setSubtitle(listOfNotNull(li.author.ifBlank { null }, remaining).joinToString(" · "))
                    .setArtworkUri(ArtworkProvider.uriFor(li.id))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    companion object {
        const val ROOT = "root"
        const val CONTINUE = "continue"
        const val LIBRARY = "library"
        const val SERIES = "series"
        const val RECENT = "recent"
        const val LIB = "lib:"
        const val LIB_SERIES = "libseries:"
        const val SERIES_ONE = "series:"

        // Android Auto content-style hints (androidx.media.utils.MediaConstants).
        const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_LIST = 1
        const val STYLE_GRID = 2
        const val COMPLETION_STATUS = "android.media.extra.PLAYBACK_STATUS"
        const val COMPLETION_PERCENTAGE = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        fun formatDuration(sec: Double): String {
            val s = sec.coerceAtLeast(0.0).toLong()
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) "$h h $m min" else "$m min"
        }
    }
}
