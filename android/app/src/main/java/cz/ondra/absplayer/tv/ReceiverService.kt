package cz.ondra.absplayer.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import cz.ondra.absplayer.App
import cz.ondra.absplayer.R
import cz.ondra.absplayer.player.PlaybackHub
import cz.ondra.absplayer.player.PlayerEngine
import cz.ondra.absplayer.remote.DEFAULT_PORT
import cz.ondra.absplayer.remote.SERVICE_TYPE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * Google TV side. A foreground service (started on boot and when the TV app opens) that:
 *  - owns the playback engine (so playback continues with the TV app in the background),
 *  - runs a small HTTP server for paired phones,
 *  - advertises itself on the LAN via mDNS.
 */
class ReceiverService : Service() {

    private lateinit var engine: PlayerEngine
    private var mediaSession: MediaSession? = null
    private var server: ReceiverServer? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        startInForeground()
        engine = PlayerEngine(this, app)
        mediaSession = MediaSession.Builder(this, engine.player).setId("tv").build()
        PlaybackHub.attach(engine)

        val srv = ReceiverServer(app, engine, deviceName(this), pairingCode)
        server = srv
        val port = srv.startOnFreePort(DEFAULT_PORT)
        if (port > 0) {
            listeningPort.value = port
            register(port, app)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.receiver_channel), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.receiver_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun register(listenPort: Int, app: App) {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "ABS Player " + deviceName(this@ReceiverService)
            serviceType = SERVICE_TYPE
            setPort(listenPort)
            setAttribute("id", app.settings.deviceId)
            setAttribute("name", deviceName(this@ReceiverService))
            setAttribute("kind", "tv")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsdListener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    override fun onDestroy() {
        nsdListener?.let { l ->
            runCatching { (getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) }
        }
        server?.stop()
        mediaSession?.release()
        PlaybackHub.detach(engine)
        engine.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 7

        /** Shown on the TV; a phone must enter it to pair. New on every app start. */
        val pairingCode: String = (100_000 + SecureRandom().nextInt(900_000)).toString()

        val listeningPort = MutableStateFlow(0)
        val port: StateFlow<Int> get() = listeningPort

        fun deviceName(ctx: Context): String =
            AndroidSettings.Global.getString(ctx.contentResolver, "device_name")
                ?: AndroidSettings.Global.getString(ctx.contentResolver, AndroidSettings.Global.DEVICE_NAME)
                ?: Build.MODEL

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ReceiverService::class.java))
        }
    }
}
