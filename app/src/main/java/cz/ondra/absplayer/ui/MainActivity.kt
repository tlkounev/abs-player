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
        when (val s = vm.stack.last()) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Book -> BookScreen(vm, s)
            is Screen.SeriesBooks -> SeriesScreen(vm, s)
            Screen.Player -> PlayerScreen(vm)
        }
        Sheets(vm)
    }
}
