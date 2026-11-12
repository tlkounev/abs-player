package cz.ondra.absplayer.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Subset of the Audiobookshelf API models. Unknown fields are ignored (see AbsApi.json).

@Serializable
data class LoginResponse(
    val user: User,
    val userDefaultLibraryId: String? = null,
)

@Serializable
data class User(
    val id: String = "",
    val username: String = "",
    /** Legacy non-expiring token (servers before the JWT change). */
    val token: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val mediaProgress: List<MediaProgress> = emptyList(),
)

@Serializable
data class MediaProgress(
    val id: String? = null,
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val hideFromContinueListening: Boolean = false,
    val lastUpdate: Long = 0,
)

@Serializable
data class Library(
    val id: String,
    val name: String = "",
    val mediaType: String = "book",
)

@Serializable
data class LibrariesResponse(val libraries: List<Library> = emptyList())

@Serializable
data class Named(val id: String? = null, val name: String = "")

@Serializable
data class BookMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val description: String? = null,
    val publishedYear: String? = null,
    val authors: List<Named> = emptyList(),
    val narrators: List<String> = emptyList(),
    /** Expanded items: list of {id, name, sequence}; some filtered lists: one object; minified: absent. */
    val series: JsonElement? = null,
) {
    val seriesRefs: List<SeriesRef>
        get() = when (val s = series) {
            is JsonArray -> s.mapNotNull { (it as? JsonObject)?.let(::seriesRef) }
            is JsonObject -> listOfNotNull(seriesRef(s))
            else -> emptyList()
        }

    /** "Duna #2" (several series joined by ", "). */
    val seriesLabel: String
        get() = seriesRefs.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label }
            ?: seriesName.orEmpty()

    val author: String
        get() = authorName?.takeIf { it.isNotBlank() } ?: authors.joinToString(", ") { it.name }
    val narrator: String
        get() = narratorName?.takeIf { it.isNotBlank() } ?: narrators.joinToString(", ")
}

@Serializable
data class Chapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

@Serializable
data class Media(
    val metadata: BookMetadata = BookMetadata(),
    val duration: Double? = null,
    val chapters: List<Chapter> = emptyList(),
    val numChapters: Int? = null,
    val coverPath: String? = null,
)

@Serializable
data class LibraryItem(
    val id: String,
    val libraryId: String? = null,
    val mediaType: String = "book",
    val media: Media = Media(),
    val addedAt: Long? = null,
    val progressLastUpdate: Long? = null,
) {
    val title: String get() = media.metadata.title ?: ""
    val author: String get() = media.metadata.author
}

@Serializable
data class LibraryItemsResponse(
    val results: List<LibraryItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

@Serializable
data class ItemsInProgressResponse(val libraryItems: List<LibraryItem> = emptyList())

@Serializable
data class Series(
    val id: String,
    val name: String = "",
    val books: List<LibraryItem> = emptyList(),
)

@Serializable
data class SeriesResponse(
    val results: List<Series> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SearchBook(val libraryItem: LibraryItem)

@Serializable
data class SearchResponse(val book: List<SearchBook> = emptyList())

@Serializable
data class AudioTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    val contentUrl: String = "",
    val mimeType: String? = null,
)

@Serializable
data class PlaybackSession(
    val id: String,
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    /** Full book metadata; only the series is used (as "Name #n", like the Windows app). */
    val mediaMetadata: kotlinx.serialization.json.JsonObject? = null,
    val duration: Double = 0.0,
    val currentTime: Double = 0.0,
    val chapters: List<Chapter> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
)

/** Real-time progress change pushed by the server over socket.io. */
data class ProgressEvent(
    val sessionId: String?,
    val deviceDescription: String?,
    val libraryItemId: String,
    val episodeId: String?,
    val currentTime: Double,
    val isFinished: Boolean,
    val lastUpdate: Long,
    val progress: Double,
)

@Serializable
data class SeriesRef(val id: String = "", val name: String = "", val sequence: String? = null) {
    val label: String get() = if (sequence.isNullOrBlank()) name else "$name #$sequence"
}

private fun seriesRef(o: JsonObject): SeriesRef? {
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    val id = str("id") ?: return null
    return SeriesRef(id, str("name").orEmpty(), str("sequence"))
}

@Serializable
data class Author(
    val id: String,
    val name: String = "",
    val imagePath: String? = null,
    val numBooks: Int? = null,
    val addedAt: Long? = null,
)

@Serializable
data class AuthorsResponse(val authors: List<Author> = emptyList())

/** The two shelves of the Audiobookshelf home that the app shows under "Přidané". */
data class Recent(val books: List<LibraryItem> = emptyList(), val series: List<Series> = emptyList())
