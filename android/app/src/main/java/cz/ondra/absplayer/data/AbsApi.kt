package cz.ondra.absplayer.data

import cz.ondra.absplayer.tr
import android.os.Build
import android.util.Base64
import cz.ondra.absplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP error from the server (as opposed to a network failure, which is an IOException). */
class ApiException(val code: Int, message: String) : Exception(message)

class AbsApi(private val settings: Settings) {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /** Called (on an OkHttp thread) when the refresh token is rejected and the user must log in again. */
    var onSessionExpired: (() -> Unit)? = null

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Any()

    /** Shared client: adds the bearer token to requests for our server and refreshes it on 401. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val token = settings.accessToken
            if (isOurServer(req.url) && req.header("Authorization") == null && req.header(NO_AUTH) == null && token != null) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
            } else if (req.header(NO_AUTH) != null) {
                chain.proceed(req.newBuilder().removeHeader(NO_AUTH).build())
            } else {
                chain.proceed(req)
            }
        }
        .authenticator(Authenticator { _, response -> authenticate(response) })
        .build()

    val serverUrl: String? get() = settings.serverUrl

    private fun isOurServer(url: HttpUrl): Boolean {
        val base = settings.serverUrl?.toHttpUrlOrNull() ?: return false
        return url.host == base.host && url.port == base.port
    }

    private fun authenticate(response: Response): Request? {
        val req = response.request
        if (!isOurServer(req.url)) return null
        val path = req.url.encodedPath
        if (path.endsWith("/login") || path.endsWith("/auth/refresh")) return null
        if (response.priorResponse != null) return null // already retried once
        val used = req.header("Authorization")?.removePrefix("Bearer ")
        synchronized(refreshLock) {
            val current = settings.accessToken
            if (current != null && current != used) {
                return req.newBuilder().header("Authorization", "Bearer $current").build()
            }
            return if (refreshBlocking()) {
                req.newBuilder().header("Authorization", "Bearer ${settings.accessToken}").build()
            } else {
                onSessionExpired?.invoke()
                null
            }
        }
    }

    /**
     * Exchanges the refresh token for a new access/refresh pair. Returns false when the server
     * rejects it (user must log in again). Network errors are thrown as IOException.
     */
    fun refreshBlocking(): Boolean {
        synchronized(refreshLock) {
            return doRefresh()
        }
    }

    private fun doRefresh(): Boolean {
        val refresh = settings.refreshToken ?: return false
        val server = settings.serverUrl ?: return false
        val req = Request.Builder()
            .url("$server/auth/refresh")
            .header("x-refresh-token", refresh)
            .header(NO_AUTH, "1")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val body: String = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        } ?: return false
        val user = runCatching { json.decodeFromString(LoginResponse.serializer(), body).user }.getOrNull()
            ?: return false
        val access = user.accessToken ?: return false
        settings.accessToken = access
        if (user.refreshToken != null) settings.refreshToken = user.refreshToken
        return true
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(resp.code, body.take(200).ifBlank { "HTTP ${resp.code}" })
            body
        }
    }

    private fun base(): String = settings.serverUrl ?: throw IOException(tr("noServer"))

    private suspend fun get(path: String): String = call(Request.Builder().url(base() + path).get().build())

    private suspend fun post(path: String, body: JsonObject? = null): String =
        call(Request.Builder().url(base() + path).post((body?.toString() ?: "{}").toRequestBody(jsonType)).build())

    private suspend fun patch(path: String, body: JsonObject): String =
        call(Request.Builder().url(base() + path).patch(body.toString().toRequestBody(jsonType)).build())

    // ---------------------------------------------------------------- auth

    /** Logs in against [server] without touching stored credentials. */
    suspend fun login(server: String, username: String, password: String): LoginResponse {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }
        val req = Request.Builder()
            .url("$server/login")
            .header("x-return-tokens", "true")
            .header(NO_AUTH, "1")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val text = try {
            call(req)
        } catch (e: ApiException) {
            if (e.code == 401) throw ApiException(401, tr("badLogin")) else throw e
        }
        return json.decodeFromString(LoginResponse.serializer(), text)
    }

    // ---------------------------------------------------------------- library

    suspend fun me(): User = json.decodeFromString(User.serializer(), get("/api/me"))

    suspend fun libraries(): List<Library> =
        json.decodeFromString(LibrariesResponse.serializer(), get("/api/libraries")).libraries

    suspend fun itemsInProgress(): List<LibraryItem> =
        json.decodeFromString(ItemsInProgressResponse.serializer(), get("/api/me/items-in-progress?limit=50"))
            .libraryItems.filter { it.mediaType == "book" }

    suspend fun libraryItems(
        libraryId: String,
        page: Int,
        limit: Int,
        sort: String = "media.metadata.title",
        desc: Boolean = false,
        filter: String? = null,
    ): LibraryItemsResponse {
        val q = buildString {
            append("/api/libraries/$libraryId/items?minified=1&limit=$limit&page=$page")
            append("&sort=").append(sort)
            if (desc) append("&desc=1")
            if (filter != null) append("&filter=").append(filter)
        }
        return json.decodeFromString(LibraryItemsResponse.serializer(), get(q))
    }

    suspend fun series(libraryId: String, page: Int = 0, limit: Int = 200): SeriesResponse =
        json.decodeFromString(
            SeriesResponse.serializer(),
            get("/api/libraries/$libraryId/series?limit=$limit&page=$page&sort=name&minified=1"),
        )

    /** Books of one series, in series order. */
    suspend fun seriesBooks(libraryId: String, seriesId: String): List<LibraryItem> {
        val enc = Base64.encodeToString(seriesId.toByteArray(), Base64.NO_WRAP)
        val filter = "series." + java.net.URLEncoder.encode(enc, "UTF-8")
        return libraryItems(libraryId, 0, 200, sort = "sequence", filter = filter).results
    }

    /** Books of one author (by title). */
    suspend fun authorBooks(libraryId: String, authorId: String): List<LibraryItem> {
        val enc = Base64.encodeToString(authorId.toByteArray(), Base64.NO_WRAP)
        val filter = "authors." + java.net.URLEncoder.encode(enc, "UTF-8")
        return libraryItems(libraryId, 0, 300, filter = filter).results
    }

    suspend fun authors(libraryId: String): List<Author> =
        json.decodeFromString(AuthorsResponse.serializer(), get("/api/libraries/$libraryId/authors")).authors

    /** "Recently added" and "Recent series" from the library's personalized home (as on the ABS web home). */
    suspend fun recent(libraryId: String, limit: Int = 12): Recent {
        val root = json.parseToJsonElement(get("/api/libraries/$libraryId/personalized?limit=$limit"))
        val shelves = (root as? kotlinx.serialization.json.JsonArray) ?: return Recent()
        fun entities(id: String) = shelves.firstOrNull {
            ((it as? JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content == id
        }?.let { (it as JsonObject)["entities"] }
        val books = entities("recently-added")?.let {
            runCatching { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(LibraryItem.serializer()), it) }.getOrNull()
        }.orEmpty()
        val series = entities("recent-series")?.let {
            runCatching { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Series.serializer()), it) }.getOrNull()
        }.orEmpty()
        return Recent(books, series)
    }

    suspend fun search(libraryId: String, query: String): List<LibraryItem> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return json.decodeFromString(SearchResponse.serializer(), get("/api/libraries/$libraryId/search?q=$q&limit=25"))
            .book.map { it.libraryItem }
    }

    suspend fun item(itemId: String): LibraryItem =
        json.decodeFromString(LibraryItem.serializer(), get("/api/items/$itemId?expanded=1"))

    // ---------------------------------------------------------------- playback & progress

    suspend fun startPlay(itemId: String): PlaybackSession {
        val body = buildJsonObject {
            put("deviceInfo", buildJsonObject {
                put("deviceId", settings.deviceId)
                put("clientName", "ABS Player")
                put("clientVersion", BuildConfig.VERSION_NAME)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sdkVersion", Build.VERSION.SDK_INT)
            })
            put("forceDirectPlay", true)
            put("forceTranscode", false)
            put("mediaPlayer", "exo-player")
            put("supportedMimeTypes", buildJsonArray {
                listOf("audio/flac", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/aac", "audio/webm", "audio/x-m4b")
                    .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        }
        return json.decodeFromString(PlaybackSession.serializer(), post("/api/items/$itemId/play", body))
    }

    suspend fun syncSession(sessionId: String, currentTime: Double, timeListened: Double, duration: Double) {
        post("/api/session/$sessionId/sync", buildJsonObject {
            put("currentTime", currentTime)
            put("timeListened", timeListened)
            put("duration", duration)
        })
    }

    suspend fun closeSession(sessionId: String) {
        post("/api/session/$sessionId/close")
    }

    /** Progress for an item, or null when the user has never played it. */
    suspend fun progress(itemId: String): MediaProgress? = try {
        json.decodeFromString(MediaProgress.serializer(), get("/api/me/progress/$itemId"))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    suspend fun setFinished(itemId: String, finished: Boolean) {
        patch("/api/me/progress/$itemId", buildJsonObject { put("isFinished", finished) })
    }

    // ---------------------------------------------------------------- urls

    fun authorImageUrl(authorId: String, width: Int = 300): String? =
        settings.serverUrl?.let { "$it/api/authors/$authorId/image?width=$width" }

    fun coverUrl(itemId: String, width: Int = 600): String? =
        settings.serverUrl?.let { "$it/api/items/$itemId/cover?width=$width" }

    /**
     * Track URLs from the server already contain the router base path (e.g. /audiobookshelf),
     * so they are resolved against the origin when the server URL has the same prefix.
     */
    fun resolveContentUrl(contentUrl: String): String {
        if (contentUrl.startsWith("http://") || contentUrl.startsWith("https://")) return contentUrl
        val server = base()
        val url = server.toHttpUrlOrNull() ?: return server + contentUrl
        val basePath = url.encodedPath.trimEnd('/')
        val origin = server.removeSuffix(url.encodedPath.trimEnd('/')).trimEnd('/')
        return if (basePath.isNotEmpty() && contentUrl.startsWith("$basePath/")) origin + contentUrl
        else server + contentUrl
    }

    companion object {
        private const val NO_AUTH = "x-abs-no-auth"

        /** Normalizes what the user typed into "scheme://host[:port][/path]" without a trailing slash. */
        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s
        }
    }
}
