package cz.ondra.absplayer

import android.app.Application
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import cz.ondra.absplayer.data.AbsApi
import cz.ondra.absplayer.data.LoginResponse
import cz.ondra.absplayer.data.Settings
import cz.ondra.absplayer.data.SocketManager
import cz.ondra.absplayer.player.ArtworkProvider
import cz.ondra.absplayer.remote.TargetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var settings: Settings
        private set
    lateinit var api: AbsApi
        private set
    lateinit var socket: SocketManager
        private set
    lateinit var targets: TargetManager
        private set

    /** Long-lived background work that must outlive screens and services (final syncs etc.). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    val isTv: Boolean by lazy {
        val ui = getSystemService(UI_MODE_SERVICE) as UiModeManager
        ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        L.init(this)
        api = AbsApi(settings)
        socket = SocketManager(settings, api, appScope)
        targets = TargetManager(this)
        api.onSessionExpired = { mainScope.launch { logout() } }
        _loggedIn.value = settings.isLoggedIn
        if (settings.isLoggedIn) socket.connect()
    }

    fun onLoggedIn(server: String, resp: LoginResponse) {
        val changedServer = settings.serverUrl != server
        settings.saveLogin(server, resp.user)
        if (changedServer) ArtworkProvider.clearCache(this)
        _loggedIn.value = true
        socket.connect()
    }

    /** Credentials were written directly (TV pairing). */
    fun onCredentialsChanged() {
        _loggedIn.value = settings.isLoggedIn
        if (settings.isLoggedIn) socket.connect() else socket.disconnect()
    }

    fun logout() {
        settings.clearLogin()
        socket.disconnect()
        _loggedIn.value = false
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
