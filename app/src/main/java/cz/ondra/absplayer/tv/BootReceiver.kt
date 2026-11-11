package cz.ondra.absplayer.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.ondra.absplayer.App

/** On Google TV the receiver starts with the TV so a phone can reach it without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        if (!app.isTv) return
        runCatching { ReceiverService.start(context) }
    }
}
