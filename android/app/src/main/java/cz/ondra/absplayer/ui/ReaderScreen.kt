package cz.ondra.absplayer.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cz.ondra.absplayer.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream

/** Where the reader is (from the page). */
private data class ReadPos(
    val chapter: String = "",
    val chapterIndex: Int = -1,
    val chapterCount: Int = 0,
    val progress: Double? = null,
    val page: Int? = null,
    val pages: Int? = null,
)

private data class TocEntry(val label: String, val href: String, val index: Int)

private val READER_BG = mapOf("dark" to Color(0xFF1E1E1E), "sepia" to Color(0xFFF4ECD8), "light" to Color(0xFFFAFAFA))

/**
 * EPUB reader: epub.js in a WebView (the same reader as the Windows app, positions compatible with
 * the Audiobookshelf web reader). Bars on top: chapter, Aa, contents; at the bottom progress and
 * "Poslouchat od této strany". On the TV ←/→ turn pages and OK shows the bars.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: MainViewModel, screen: Screen.Reader) {
    val tv = vm.app.isTv
    val st = vm.app.settings
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var bars by remember { mutableStateOf(true) }
    var pos by remember { mutableStateOf(ReadPos()) }
    var toc by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    var sheet by remember { mutableStateOf<String?>(null) } // "text" | "toc"
    var theme by remember { mutableStateOf(st.readerTheme) }
    var fontSize by remember { mutableIntStateOf(st.readerFontSize.let { if (tv && it == 100) 130 else it }) }
    var font by remember { mutableStateOf(st.readerFont) }
    var lineHeight by remember { mutableStateOf(st.readerLineHeight) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var turns by remember { mutableIntStateOf(0) }
    // errors from the page (shown small at the bottom: a screenshot tells what went wrong)
    var jsLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastCfi by remember { mutableStateOf<String?>(null) }
    val item = vm.books[screen.itemId]
    val hasAudio = item?.media?.hasAudio == true
    val listenFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }

    fun js(code: String) = web?.evaluateJavascript(code, null)
    fun settingsJson() = buildJsonObject {
        put("theme", theme); put("fontSize", fontSize); put("font", font); put("lineHeight", lineHeight.toDouble())
    }.toString()
    fun applySettings() {
        st.readerTheme = theme; st.readerFontSize = fontSize; st.readerFont = font; st.readerLineHeight = lineHeight
        js("reader.applySettings(" + org.json.JSONObject.quote(settingsJson()) + ")")
    }

    // the book (and its chapters, for the sync with listening)
    LaunchedEffect(screen.itemId) {
        try {
            vm.details(screen.itemId)
            file = vm.ebookFile(screen.itemId)
        } catch (e: Exception) {
            error = tr("ebookError") + " (" + (e.message ?: e.javaClass.simpleName) + ")"
            loading = false
        }
    }
    // the bars go away when you start reading
    LaunchedEffect(turns) { if (turns > 0) bars = false }
    // TV: the text alone; OK shows the bars (←/→ then move between the buttons, Back hides them)
    LaunchedEffect(loading) { if (tv && !loading && error == null) bars = false }
    LaunchedEffect(bars) { if (tv && bars && hasAudio) { delay(80); runCatching { listenFocus.requestFocus() } } else if (tv) runCatching { rootFocus.requestFocus() } }

    BackHandler {
        when {
            sheet != null -> sheet = null
            tv && bars && !loading -> bars = false
            else -> vm.pop()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(READER_BG[theme] ?: READER_BG.getValue("dark"))
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                // TV: ←/→ page, OK shows the bars (when they are hidden)
                if (!tv || e.type != KeyEventType.KeyDown || sheet != null) return@onPreviewKeyEvent false
                if (bars) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { js("reader.prev()"); true }
                    Key.DirectionRight -> { js("reader.next()"); true }
                    Key.DirectionCenter, Key.Enter, Key.DirectionDown, Key.DirectionUp -> { bars = true; true }
                    else -> false
                }
            },
    ) {
        val f = file
        if (f != null && error == null) {
            AndroidView(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                factory = { ctx ->
                    val main = Handler(Looper.getMainLooper())
                    WebView(ctx).apply {
                        isFocusable = !tv
                        isFocusableInTouchMode = !tv
                        setBackgroundColor((READER_BG[theme] ?: READER_BG.getValue("dark")).let {
                            android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
                        })
                        WebView.setWebContentsDebuggingEnabled(true)
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                    val line = m.message() + " (" + m.sourceId().substringAfterLast('/') + ":" + m.lineNumber() + ")"
                                    main.post { jsLog = (jsLog + line).takeLast(4) }
                                }
                                return true
                            }
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun config(): String {
                                val p = vm.progress[screen.itemId]
                                val b = vm.books[screen.itemId]
                                val locFile = vm.ebookLocationsFile(screen.itemId)
                                return buildJsonObject {
                                    put("bookUrl", "https://reader.local/book.epub")
                                    put("mode", screen.mode)
                                    p?.ebookLocation?.let { put("startCfi", it) }
                                    put("audioTime", screen.audioTime ?: p?.currentTime ?: 0.0)
                                    put("audioDuration", b?.media?.duration ?: 0.0)
                                    put("audioChapters", buildJsonArray {
                                        b?.media?.chapters?.forEach { c ->
                                            add(buildJsonObject { put("start", c.start); put("end", c.end); put("title", c.title) })
                                        }
                                    })
                                    put("spread", "auto")
                                    put("settings", Json.parseToJsonElement(settingsJson()))
                                    if (locFile.exists()) put("locations", runCatching { locFile.readText() }.getOrDefault(""))
                                }.toString()
                            }

                            @JavascriptInterface
                            fun relocated(json: String) = main.post {
                                val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@post
                                fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
                                fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull
                                pos = ReadPos(
                                    chapter = o["chapter"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    chapterIndex = i("chapterIndex") ?: -1,
                                    chapterCount = i("chapterCount") ?: 0,
                                    progress = d("progress"), page = i("page"), pages = i("pages"),
                                )
                                o["cfi"]?.jsonPrimitive?.contentOrNull?.let { cfi ->
                                    if (lastCfi != null && cfi != lastCfi) turns++ // a real page turn (not the first report)
                                    lastCfi = cfi
                                    vm.saveEbookPosition(screen.itemId, cfi, pos.progress)
                                }
                            }

                            @JavascriptInterface
                            fun toc(json: String) = main.post {
                                toc = runCatching {
                                    Json.parseToJsonElement(json).jsonArray.map {
                                        val o = it.jsonObject
                                        TocEntry(
                                            o["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                            o["href"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                            o["index"]?.jsonPrimitive?.intOrNull ?: 0,
                                        )
                                    }
                                }.getOrDefault(emptyList())
                            }

                            @JavascriptInterface
                            fun tap(zone: String) = main.post { if (zone == "center") bars = !bars }

                            @JavascriptInterface
                            fun listen(seconds: String) = main.post {
                                seconds.toDoubleOrNull()?.let { vm.listenFrom(screen.itemId, it) }
                            }

                            @JavascriptInterface
                            fun saveLocations(json: String) {
                                runCatching { vm.ebookLocationsFile(screen.itemId).writeText(json) }
                            }

                            @JavascriptInterface
                            fun ready(x: String) = main.post { loading = false }

                            @JavascriptInterface
                            fun log(text: String) = main.post { jsLog = (jsLog + text).takeLast(4) }

                            @JavascriptInterface
                            fun error(text: String) = main.post { error = tr("ebookError") + " (" + text + ")"; loading = false }
                        }, "AbsHost")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url
                                if (u.host != "reader.local") return null
                                val path = u.path.orEmpty().trimStart('/')
                                return runCatching {
                                    if (path == "book.epub") WebResourceResponse("application/epub+zip", null, FileInputStream(f))
                                    else WebResourceResponse(
                                        when {
                                            path.endsWith(".js") -> "text/javascript"
                                            path.endsWith(".html") -> "text/html"
                                            path.endsWith(".css") -> "text/css"
                                            else -> "application/octet-stream"
                                        },
                                        "utf-8", ctx.assets.open("reader/$path"),
                                    )
                                }.getOrNull()
                            }
                        }
                        loadUrl("https://reader.local/reader.html")
                        web = this
                    }
                },
            )
        }

        if (jsLog.isNotEmpty() && pos.progress == null) {
            Text(
                jsLog.joinToString("\n"), color = C.Error, fontSize = 10.sp, lineHeight = 13.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 120.dp),
            )
        }
        if (loading && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = C.Accent)
                Text(tr("ebookLoading"), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        error?.let {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = C.Error, fontSize = 14.sp)
                TextButton(onClick = { vm.pop() }) { Text(tr("back0")) }
            }
        }

        // --- top bar: back, title + chapter, Aa, contents
        if (bars && error == null) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(C.Surface)
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.pop() }) { Icon(Ic.Back, tr("back0")) }
                Column(Modifier.weight(1f)) {
                    Text(screen.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pos.chapter.isNotBlank()) Text(pos.chapter, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { sheet = "text" }) { Text("Aa", color = C.Text, fontSize = 16.sp) }
                IconButton(onClick = { sheet = "toc" }, enabled = toc.isNotEmpty()) { Icon(Ic.WChapters, tr("contents"), modifier = Modifier.size(20.dp)) }
            }

            // --- bottom bar: chapter, progress, page; listen from this page
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(C.Surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pos.chapterIndex >= 0) Text(tr("chShort") + " ${pos.chapterIndex + 1}/${pos.chapterCount}", color = C.Muted, fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { (pos.progress ?: 0.0).toFloat().coerceIn(0f, 1f) },
                        color = Color(0xFF8FB7E6), trackColor = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                    val pct = pos.progress?.let { "${(it * 100).toInt()} %" } ?: ""
                    val page = if (pos.page != null && pos.pages != null) " · " + tr("pageShort") + " ${pos.page}/${pos.pages}" else ""
                    Text(pct + page, color = C.Muted, fontSize = 12.sp)
                }
                if (hasAudio) {
                    Button(
                        onClick = { js("reader.listenHere()") },
                        enabled = !loading,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp).focusRequester(listenFocus),
                    ) { Text("🎧  " + tr("listenFromPage"), fontSize = 14.sp) }
                }
            }
        }
    }

    // --- Aa: text settings
    if (sheet == "text") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("readerText"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Label(tr("fontSize"))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A", fontSize = 13.sp)
                    Slider(
                        value = fontSize.toFloat(), valueRange = 70f..200f, steps = 12,
                        onValueChange = { fontSize = (it / 10).toInt() * 10 }, onValueChangeFinished = { applySettings() },
                        colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent),
                        modifier = Modifier.weight(1f),
                    )
                    Text("A", fontSize = 20.sp)
                }
                Label(tr("font"))
                Segmented(listOf("serif" to tr("fontSerif"), "sans" to tr("fontSans"), "original" to tr("fontOriginal")), font) { font = it; applySettings() }
                Label(tr("theme"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Triple("dark", tr("themeDark"), Color(0xFFE6E1D8)), Triple("sepia", tr("themeSepia"), Color(0xFF4A3F2C)), Triple("light", tr("themeLight"), Color(0xFF222222)))
                        .forEach { (k, label, fg) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(READER_BG.getValue(k))
                                    .border(if (theme == k) 2.dp else 1.dp, if (theme == k) C.Accent else C.Outline, RoundedCornerShape(10.dp))
                                    .clickable { theme = k; applySettings() },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = fg, fontSize = 13.sp) }
                        }
                }
                Label(tr("lineHeight"))
                Segmented(listOf("1.35" to tr("lhTight"), "1.6" to tr("lhNormal"), "1.9" to tr("lhLoose")), lineHeight.toString()) {
                    lineHeight = it.toFloat(); applySettings()
                }
            }
        }
    }

    // --- ≡: contents
    if (sheet == "toc") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Surface) {
            Text(tr("contents"), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (pos.chapterIndex - 2).coerceAtLeast(0))
            LazyColumn(state = list, modifier = Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                itemsIndexed(toc) { i, t ->
                    val cur = i == pos.chapterIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (cur) Color(0x268FB7E6) else Color.Transparent)
                            .clickable {
                                js("reader.goto(" + org.json.JSONObject.quote(t.href) + ")")
                                sheet = null
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                        Text(t.label, color = if (cur) Color(0xFF8FB7E6) else C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun Segmented(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(10.dp)),
    ) {
        options.forEach { (k, label) ->
            val on = k == value
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (on) C.Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onPick(k) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 13.sp, color = if (on) C.Accent else C.Text,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = if (k == "serif") FontFamily.Serif else null,
                )
            }
        }
    }
}
