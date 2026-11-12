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
        if (!file.exists() || file.length() == 0L) {
            val url = if (author) App.instance.api.authorImageUrl(itemId) else App.instance.api.coverUrl(itemId)
            download(url, file)
        }
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun download(url: String?, target: File) {
        val app = App.instance
        if (url == null) return
        try {
            app.api.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return
                val tmp = File(target.parentFile, target.name + ".part")
                resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() > 0) tmp.renameTo(target) else tmp.delete()
            }
        } catch (_: Exception) {
            // No cover (offline, missing): caller throws FileNotFoundException.
        }
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private val ID = Regex("[A-Za-z0-9_\\-]+")

        fun uriFor(itemId: String): Uri =
            Uri.parse("content://${BuildConfig.APPLICATION_ID}.artwork/cover/$itemId")

        fun uriForAuthor(authorId: String): Uri =
            Uri.parse("content://${BuildConfig.APPLICATION_ID}.artwork/author/$authorId")

        fun clearCache(app: App) {
            File(app.cacheDir, "covers").listFiles()?.forEach { it.delete() }
        }
    }
}
