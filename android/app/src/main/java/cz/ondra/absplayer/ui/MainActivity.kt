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

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The same screen runs on the TV; there it also receives playback from phones / PCs
        // (pairing code + address are in "Přehrávat na").
        if ((application as App).isTv) cz.ondra.absplayer.tv.ReceiverService.start(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AbsTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as App).targets.current.value.activate()
    }

    override fun onStop() {
        val t = (application as App).targets.current.value
        if (t.isRemote) t.deactivate()
        super.onStop()
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
        val top = vm.stack.last()
        val overlay = top == Screen.Player && vm.stack.size > 1
        // The screen below stays composed (keeps its scroll position) and visible while the player is dragged down.
        ScreenFor(vm, if (overlay) vm.stack[vm.stack.size - 2] else top)
        if (overlay) PlayerScreen(vm)
        Sheets(vm)
    }
}

@Composable
private fun ScreenFor(vm: MainViewModel, s: Screen) {
    when (s) {
        Screen.Home -> HomeScreen(vm)
        is Screen.Book -> BookScreen(vm, s)
        is Screen.SeriesBooks -> SeriesScreen(vm, s)
        Screen.Player -> PlayerScreen(vm)
    }
}
