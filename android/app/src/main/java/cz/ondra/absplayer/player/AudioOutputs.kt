package cz.ondra.absplayer.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import cz.ondra.absplayer.data.Settings
import cz.ondra.absplayer.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio outputs of this device and the one the audiobook plays on ("Play on" → output). The choice
 * applies only to this app's player (ExoPlayer.setPreferredAudioDevice); null = Android decides.
 * When the chosen device is not connected, Android plays on its default output; the choice comes
 * back into force when the device connects again.
 */
class AudioOutputs(private val ctx: Context, private val settings: Settings, private val isTv: Boolean) {
    enum class Kind { SPEAKER, BLUETOOTH, WIRED, HDMI, USB, OTHER }

    /** [key] = type|name, stable across reconnections (the AudioDeviceInfo id is not). */
    data class Output(val key: String, val name: String, val kind: Kind, val info: AudioDeviceInfo)

    private val am = ctx.getSystemService(AudioManager::class.java)
    private val players = mutableSetOf<ExoPlayer>()

    private val _list = MutableStateFlow<List<Output>>(emptyList())
    val list: StateFlow<List<Output>> = _list

    private val _selected = MutableStateFlow(settings.audioOutput)
    val selected: StateFlow<String?> = _selected

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun attach(p: ExoPlayer) {
        players += p
        apply()
    }

    fun detach(p: ExoPlayer) {
        players -= p
    }

    fun select(key: String?) {
        settings.audioOutput = key
        _selected.value = key
        apply()
    }

    fun refresh() {
        _list.value = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull(::toOutput).distinctBy { it.key }
        apply()
    }

    private fun apply() {
        val d = _list.value.find { it.key == _selected.value }?.info
        players.forEach { runCatching { it.setPreferredAudioDevice(d) } }
    }

    private fun toOutput(d: AudioDeviceInfo): Output? {
        val name = d.productName?.toString().orEmpty()
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> Kind.WIRED
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_LINE_DIGITAL -> Kind.HDMI
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.USB
            else -> when {
                Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> Kind.BLUETOOTH
                Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_HDMI_EARC -> Kind.HDMI
                else -> return null // earpiece, calls (SCO), telephony, remote submix…
            }
        }
        val label = when (kind) {
            Kind.SPEAKER -> tr(if (isTv) "outputTvSpeakers" else "outputPhoneSpeaker")
            Kind.WIRED -> tr("outputWired")
            Kind.HDMI -> if (d.type == AudioDeviceInfo.TYPE_HDMI_ARC || (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_HDMI_EARC)) "${name.ifBlank { "HDMI" }} (HDMI ARC)" else name.ifBlank { "HDMI" }
            else -> name.ifBlank { "USB" }
        }
        return Output("${d.type}|$name", label, kind, d)
    }
}
