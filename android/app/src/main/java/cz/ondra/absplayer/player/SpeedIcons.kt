package cz.ondra.absplayer.player

import cz.ondra.absplayer.R
import kotlin.math.roundToInt

/** Icons with the current speed as text ("1.0x", "1.25x" …) for the Android Auto / notification speed button. */
object SpeedIcons {
    private val ICONS = intArrayOf(
        R.drawable.ic_speed_050,
        R.drawable.ic_speed_055,
        R.drawable.ic_speed_060,
        R.drawable.ic_speed_065,
        R.drawable.ic_speed_070,
        R.drawable.ic_speed_075,
        R.drawable.ic_speed_080,
        R.drawable.ic_speed_085,
        R.drawable.ic_speed_090,
        R.drawable.ic_speed_095,
        R.drawable.ic_speed_100,
        R.drawable.ic_speed_105,
        R.drawable.ic_speed_110,
        R.drawable.ic_speed_115,
        R.drawable.ic_speed_120,
        R.drawable.ic_speed_125,
        R.drawable.ic_speed_130,
        R.drawable.ic_speed_135,
        R.drawable.ic_speed_140,
        R.drawable.ic_speed_145,
        R.drawable.ic_speed_150,
        R.drawable.ic_speed_155,
        R.drawable.ic_speed_160,
        R.drawable.ic_speed_165,
        R.drawable.ic_speed_170,
        R.drawable.ic_speed_175,
        R.drawable.ic_speed_180,
        R.drawable.ic_speed_185,
        R.drawable.ic_speed_190,
        R.drawable.ic_speed_195,
        R.drawable.ic_speed_200,
        R.drawable.ic_speed_205,
        R.drawable.ic_speed_210,
        R.drawable.ic_speed_215,
        R.drawable.ic_speed_220,
        R.drawable.ic_speed_225,
        R.drawable.ic_speed_230,
        R.drawable.ic_speed_235,
        R.drawable.ic_speed_240,
        R.drawable.ic_speed_245,
        R.drawable.ic_speed_250,
        R.drawable.ic_speed_255,
        R.drawable.ic_speed_260,
        R.drawable.ic_speed_265,
        R.drawable.ic_speed_270,
        R.drawable.ic_speed_275,
        R.drawable.ic_speed_280,
        R.drawable.ic_speed_285,
        R.drawable.ic_speed_290,
        R.drawable.ic_speed_295,
        R.drawable.ic_speed_300,
    )

    /** Icon for [speed] (0.5–3.0, rounded to 0.05). */
    fun forSpeed(speed: Float): Int {
        val i = ((speed.coerceIn(0.5f, 3.0f) - 0.5f) / 0.05f).roundToInt()
        return ICONS[i.coerceIn(0, ICONS.size - 1)]
    }
}
