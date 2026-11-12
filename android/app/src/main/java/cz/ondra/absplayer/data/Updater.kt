package cz.ondra.absplayer.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import cz.ondra.absplayer.BuildConfig
import cz.ondra.absplayer.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Update check against the GitHub releases of tlkounev/abs-player (tags `android-v<version>-build<n>`).
 * The APK is downloaded and handed to the system installer (PackageInstaller): Android shows its
 * own confirmation, the app keeps its data. Works only when all APKs are signed with the same key.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/tlkounev/abs-player/releases?per_page=100"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Release(val version: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val done: Long, val total: Long) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("abs", Context.MODE_PRIVATE)

    /** Version hidden with ✕ in the banner (shown again when an even newer one appears). */
    fun skipped(ctx: Context): String? = prefs(ctx).getString("updSkip", null)
    fun skip(ctx: Context, version: String) = prefs(ctx).edit().putString("updSkip", version).apply()

    /** Automatic check at most once a day; [manual] always checks. */
    suspend fun check(ctx: Context, manual: Boolean) {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return
        val last = prefs(ctx).getLong("updLast", 0)
        if (!manual && System.currentTimeMillis() - last < DAY_MS) {
            // Re-use the last result so the banner stays visible after a restart.
            prefs(ctx).getString("updAvail", null)?.let { cached ->
                val p = cached.split('|')
                if (p.size == 3 && newer(p[0], currentVersion)) _state.value = State.Available(Release(p[0], p[1], p[2].toLongOrNull() ?: 0))
            }
            return
        }
        _state.value = State.Checking
        _state.value = try {
            val rel = withContext(Dispatchers.IO) { latest() }
            prefs(ctx).edit().putLong("updLast", System.currentTimeMillis())
                .putString("updAvail", rel?.let { "${it.version}|${it.apkUrl}|${it.size}" }).apply()
            if (rel != null && newer(rel.version, currentVersion)) State.Available(rel) else State.UpToDate
        } catch (e: Exception) {
            if (manual) State.Failed(e.message ?: e.javaClass.simpleName) else State.Idle
        }
    }

    private fun latest(): Release? {
        val req = Request.Builder().url(RELEASES)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ABS-Player-Android/$currentVersion")
            .build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("GitHub ${r.code}")
            r.body!!.string()
        }
        val list = Json.parseToJsonElement(body) as JsonArray
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!tag.startsWith("android-v")) return@mapNotNull null
            if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            if ((o["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val version = tag.removePrefix("android-v").substringBefore("-build")
            val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true }
                ?: return@mapNotNull null
            Release(
                version,
                (apk["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }.maxWithOrNull { a, b -> compare(a.version, b.version) }
    }

    /** Downloads the APK and starts the system installer. */
    suspend fun update(ctx: Context, rel: Release) {
        val s = _state.value
        if (s is State.Downloading || s is State.Installing) return
        _state.value = State.Downloading(rel, 0, rel.size)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "abs-player-${rel.version}.apk")
                val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "ABS-Player-Android/$currentVersion").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val total = r.body!!.contentLength().takeIf { it > 0 } ?: rel.size
                    r.body!!.byteStream().use { input ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastUi = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastUi > 256 * 1024) {
                                    lastUi = done
                                    _state.value = State.Downloading(rel, done, total)
                                }
                            }
                        }
                    }
                }
                f
            }
            _state.value = State.Installing(rel)
            withContext(Dispatchers.IO) { install(ctx, file) }
        } catch (e: Exception) {
            _state.value = State.Failed(tr("updateFailed", "t" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // After the first update this app is the installer of record: later updates may need no tap.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallResult(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    (_state.value as? State.Installing)?.let { State.Available(it.release) } ?: State.Idle
                } else {
                    State.Failed(tr("updateFailed", "t" to msg))
                }
            }
        }
    }

    /** Hides an error message. */
    fun clearError() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    fun newer(a: String, b: String) = compare(a, b) > 0

    private fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onInstallResult(context, intent)
}
