package cz.ondra.absplayer.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import cz.ondra.absplayer.App
import cz.ondra.absplayer.BuildConfig
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves book covers as content:// URIs. Android Auto cannot load covers from an authenticated
 * http URL, so it (and the notification and our own UI) reads them through this provider, which
 * downloads each cover once with the logged-in client and caches it.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val itemId = uri.lastPathSegment?.takeIf { ID.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val ctx = context ?: throw FileNotFoundException("no context")
        val dir = File(ctx.cacheDir, "covers").apply { mkdirs() }
        val author = uri.pathSegments.firstOrNull() == "author"
        val file = File(dir, if (author) "a_$itemId.img" else "$itemId.img")
        // one download per cover at a time (grid, player and notification ask together – two writers
        // into one file left broken covers in the cache for good)
        synchronized(locks.getOrPut(file.name) { Any() }) {
            if (file.exists() && !isImage(file)) file.delete() // broken from before: fetch again
            if (!file.exists()) {
                val api = App.instance.api
                val urls = if (author) listOf(api.authorImageUrl(itemId)) else listOf(api.coverUrl(itemId), api.coverRawUrl(itemId))
                // resized first; if the server cannot resize this cover, the original file
                for (u in urls) if (download(u, file)) break
            }
        }
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Downloads an image; keeps it only when it really is a picture. */
    private fun download(url: String?, target: File): Boolean {
        val app = App.instance
        if (url == null) return false
        val tmp = File.createTempFile(target.name, ".part", target.parentFile)
        try {
            app.api.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            return isImage(tmp) && tmp.renameTo(target)
        } catch (_: Exception) {
            return false // no cover (offline, missing): caller throws FileNotFoundException
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun isImage(f: File): Boolean = f.length() > 0 && runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        o.outWidth > 0 && o.outHeight > 0
    }.getOrDefault(false)

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private val ID = Regex("[A-Za-z0-9_\\-]+")
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        fun uriFor(itemId: String): Uri =
            Uri.parse("content://${BuildConfig.APPLICATION_ID}.artwork/cover/$itemId")

        fun uriForAuthor(authorId: String): Uri =
            Uri.parse("content://${BuildConfig.APPLICATION_ID}.artwork/author/$authorId")

        fun clearCache(app: App) {
            File(app.cacheDir, "covers").listFiles()?.forEach { it.delete() }
        }
    }
}
