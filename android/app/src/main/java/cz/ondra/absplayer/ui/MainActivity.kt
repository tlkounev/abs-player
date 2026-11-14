package cz.ondra.absplayer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ondra.absplayer.App
import cz.ondra.absplayer.player.PlaybackHub
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /**
     * Local network (TV / PC on the home Wi-Fi). Newer Android versions keep the app off the local
     * network until this permission ("Nearby devices") is granted – then nothing on the LAN answers
     * although Chrome reaches it. Asked for when the system has the permission.
     */
    private val localNetLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.localNetworkOk = ok
        if (ok) (application as App).targets.discovery.let { it.stop(); it.start() }
    }

    fun ensureLocalNetwork(ask: Boolean) {
        val defined = runCatching { packageManager.getPermissionInfo(LOCAL_NETWORK, 0) }.isSuccess
        val ok = !defined || checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        vm.localNetworkOk = ok
        if (!ok && ask) localNetLauncher.launch(LOCAL_NETWORK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The same screen runs on the TV; there it also receives playback from phones / PCs
        // (pairing code + address are in "Play on").
        if ((application as App).isTv) cz.ondra.absplayer.tv.ReceiverService.start(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AbsTheme(tv = (application as App).isTv) {
                AppRoot(vm)
            }
        }
        handleOpenPlayer(intent)
        handleDemo(intent)
        ensureLocalNetwork(ask = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenPlayer(intent)
        handleDemo(intent)
    }

    /**
     * Debug builds only: steering for the README screenshots taken in CI
     * (`adb shell am start -n …/.ui.MainActivity --es demo book --es title Alice`).
     */
    private fun handleDemo(i: Intent?) {
        if (!cz.ondra.absplayer.BuildConfig.DEBUG) return
        val cmd = i?.getStringExtra("demo") ?: return
        val title = i.getStringExtra("title").orEmpty()
        fun find() = (vm.continueItems + vm.libItems + (vm.recent?.books ?: emptyList()))
            .firstOrNull { it.title.contains(title, ignoreCase = true) }
        when (cmd) {
            "login" -> vm.login(i.getStringExtra("server").orEmpty(), i.getStringExtra("user").orEmpty(), i.getStringExtra("pass").orEmpty())
            "home" -> {
                vm.sheet = null
                while (vm.stack.size > 1) vm.stack.removeAt(vm.stack.lastIndex)
                vm.onTab(i.getIntExtra("tab", 0))
            }
            "book" -> find()?.let { vm.openBook(it.id, it.title) }
            "play" -> find()?.let { vm.play(it.id, i.getStringExtra("at")?.toDoubleOrNull()) }
            "pause" -> vm.target.value.pause()
            "player" -> if (vm.stack.lastOrNull() != Screen.Player) vm.push(Screen.Player)
            "reader" -> find()?.let { vm.push(Screen.Reader(it.id, it.title)) }
            "devices" -> vm.sheet = Sheet.Devices
            "lang" -> i.getStringExtra("code")?.let { cz.ondra.absplayer.L.set(this, it) }
            "back" -> vm.pop()
        }
    }

    /** "Play on" from a phone / PC brought the app to the front on the TV: show the player. */
    private fun handleOpenPlayer(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true && vm.stack.lastOrNull() != Screen.Player) {
            vm.push(Screen.Player)
        }
    }

    override fun onStart() {
        super.onStart()
        if (vm.error != null) vm.refreshAll()
        vm.autoCheckUpdates()
        (application as App).targets.current.value.activate()
    }

    override fun onStop() {
        val app = application as App
        val t = app.targets.current.value
        if (t.isRemote) t.deactivate()
        // On the TV, leaving the app (home button, another app) stops the book; the position is synced on pause.
        if (app.isTv && !isChangingConfigurations) PlaybackHub.engine?.let { if (it.player.isPlaying) it.pause() }
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "openPlayer"
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /**
         * Brings the app to the front on the TV (playback sent from a phone / PC). Android allows this
         * from the background only with "Display over other apps"; without it nothing happens.
         */
        fun bringToFront(ctx: android.content.Context) {
            // The app's task already exists when it was only sent to the background (Home): move that
            // task to the front first. FLAG_ACTIVITY_REORDER_TO_FRONT started from a service only
            // reorders inside the task and leaves it behind the launcher, so it is not used.
            runCatching {
                val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                am?.appTasks?.firstOrNull()?.moveToFront()
            }
            runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_OPEN_PLAYER, true)
                )
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        if (!loggedIn) {
            LoginScreen(vm)
            return@Box
        }
        BackHandler(enabled = vm.stack.size > 1) { vm.pop() }
        if (vm.app.isTv) {
            // TV: no screensaver while a book plays (it would stop the app and with it the book)
            val s by vm.playing.collectAsStateWithLifecycle()
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.DisposableEffect(s.isPlaying) {
                view.keepScreenOn = s.isPlaying
                onDispose { view.keepScreenOn = false }
            }
        }
        val top = vm.stack.last()
        val overlay = top == Screen.Player && vm.stack.size > 1
        // The screen below stays composed (keeps its scroll position) and visible while the player is dragged down.
        // On the TV the screen below is not composed: the remote's D-pad would otherwise walk through
        // its (hidden) buttons behind the player.
        if (overlay && vm.app.isTv) PlayerScreen(vm)
        else {
            ScreenFor(vm, if (overlay) vm.stack[vm.stack.size - 2] else top)
            if (overlay) PlayerScreen(vm)
        }
        Sheets(vm)
    }
}

@Composable
private fun ScreenFor(vm: MainViewModel, s: Screen) {
    when (s) {
        Screen.Home -> HomeScreen(vm)
        is Screen.Book -> BookScreen(vm, s)
        is Screen.SeriesBooks -> SeriesScreen(vm, s)
        is Screen.AuthorBooks -> AuthorScreen(vm, s)
        is Screen.Reader -> ReaderScreen(vm, s)
        Screen.Player -> PlayerScreen(vm)
    }
}
