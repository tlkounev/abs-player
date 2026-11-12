package cz.ondra.absplayer.ui

import android.content.Intent
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
        // The same APK runs on the TV: when the phone screen is opened there (e.g. "Open" after
        // installing), switch to the TV screen, which also starts the receiver (code, IP, port).
        if ((application as App).isTv) {
            super.onCreate(savedInstanceState)
            startActivity(Intent(this, cz.ondra.absplayer.tv.TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
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
        if (isFinishing) return
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
