package cz.ondra.absplayer.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** A TV (or other receiver) this phone has paired with. Host/port are refreshed by discovery. */
@Serializable
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val secret: String,
    val host: String,
    val port: Int,
    /** "tv" | "pc" */
    val kind: String = "tv",
)

data class LocalProgress(val positionSec: Double, val updatedAt: Long, val synced: Boolean)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("abs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var serverUrl: String?
        get() = prefs.getString("server", null)
        set(v) = prefs.edit().putString("server", v).apply()
    var username: String?
        get() = prefs.getString("username", null)
        set(v) = prefs.edit().putString("username", v).apply()
    var userId: String?
        get() = prefs.getString("userId", null)
        set(v) = prefs.edit().putString("userId", v).apply()

    /** Access token (JWT) or, on old servers, the legacy API token. */
    @get:Synchronized @set:Synchronized
    var accessToken: String?
        get() = prefs.getString("access", null)
        set(v) { prefs.edit().putString("access", v).commit() }

    @get:Synchronized @set:Synchronized
    var refreshToken: String?
        get() = prefs.getString("refresh", null)
        set(v) { prefs.edit().putString("refresh", v).commit() }

    val isLoggedIn: Boolean get() = serverUrl != null && accessToken != null

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    var lastItemId: String?
        get() = prefs.getString("lastItem", null)
        set(v) = prefs.edit().putString("lastItem", v).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 1.0f)
        set(v) = prefs.edit().putFloat("speed", v).apply()

    var volume: Float
        get() = prefs.getFloat("volume", 1.0f)
        set(v) = prefs.edit().putFloat("volume", v).apply()

    var lastLibraryId: String?
        get() = prefs.getString("lastLibrary", null)
        set(v) = prefs.edit().putString("lastLibrary", v).apply()

    fun saveLogin(server: String, user: User) {
        prefs.edit()
            .putString("server", server)
            .putString("username", user.username)
            .putString("userId", user.id)
            .putString("access", user.accessToken ?: user.token)
            .putString("refresh", user.refreshToken)
            .commit()
    }

    fun clearLogin() {
        prefs.edit().remove("access").remove("refresh").remove("userId").commit()
    }

    // --- local progress backup (for offline / failed syncs) ---

    fun localProgress(itemId: String): LocalProgress? {
        val raw = prefs.getString("lp_$itemId", null) ?: return null
        val p = raw.split('|')
        if (p.size != 3) return null
        return LocalProgress(p[0].toDoubleOrNull() ?: return null, p[1].toLongOrNull() ?: 0, p[2] == "1")
    }

    fun saveLocalProgress(itemId: String, positionSec: Double, synced: Boolean) {
        prefs.edit()
            .putString("lp_$itemId", "$positionSec|${System.currentTimeMillis()}|${if (synced) 1 else 0}")
            .apply()
    }

    // --- phone: paired receivers ---

    var pairedDevices: List<PairedDevice>
        get() = prefs.getString("paired", null)?.let {
            runCatching { json.decodeFromString<List<PairedDevice>>(it) }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit().putString("paired", json.encodeToString(v)).apply()

    fun upsertPaired(d: PairedDevice) {
        pairedDevices = pairedDevices.filter { it.deviceId != d.deviceId } + d
    }

    fun removePaired(deviceId: String) {
        pairedDevices = pairedDevices.filter { it.deviceId != deviceId }
    }

    // --- TV: secrets of phones allowed to control this receiver ---

    var receiverSecrets: Set<String>
        get() = prefs.getStringSet("rxSecrets", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("rxSecrets", v).apply()
}
