package cz.ondra.absplayer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * App language, same 15 languages as the Windows app. Default = system language (English if not supported).
 * [lang] is Compose state, so screens re-render immediately after a change.
 */
object L {
    val codes: List<String> = I18nData.CODES
    val names: List<String> = I18nData.NAMES
    private val keys: Map<String, Int> = I18nData.KEYS.split('\u0001').withIndex().associate { (i, k) -> k to i }
    private val data: List<List<String>> = I18nData.DATA.map { it.split('\u0001') }

    var lang by mutableStateOf(defaultLang())
        private set

    private fun defaultLang(): String {
        val sys = Locale.getDefault().language.let { if (it == "iw") "he" else it }
        return if (sys in I18nData.CODES) sys else "en"
    }

    fun init(context: Context) {
        val saved = context.getSharedPreferences("abs", Context.MODE_PRIVATE).getString("lang", null)
        if (saved != null && saved in codes) lang = saved
    }

    fun set(context: Context, code: String) {
        if (code !in codes) return
        context.getSharedPreferences("abs", Context.MODE_PRIVATE).edit().putString("lang", code).apply()
        lang = code
    }

    fun t(key: String, vararg args: Pair<String, Any?>): String {
        val i = keys[key] ?: return key
        var s = data[codes.indexOf(lang).coerceAtLeast(0)][i]
        for ((k, v) in args) s = s.replace("{$k}", v.toString())
        return s
    }
}

/** Shorthand: `tr("back", "s" to 10)`. */
fun tr(key: String, vararg args: Pair<String, Any?>): String = L.t(key, *args)
