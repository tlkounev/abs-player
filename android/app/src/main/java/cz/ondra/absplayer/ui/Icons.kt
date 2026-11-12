package cz.ondra.absplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material icon paths (24×24), drawn without the large icons library. */
object Ic {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    private fun icon(name: String, vp: Float, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, vp, vp)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    // --- the same glyphs as the Windows mini player ---
    val WPlay = icon("wplay", 20f, "M6 3.7v12.6a.9.9 0 0 0 1.4.8l9.8-6.3a1 1 0 0 0 0-1.6L7.4 2.9a.9.9 0 0 0-1.4.8Z")
    val WPause = icon("wpause", 20f, "M5 3h3.4v14H5zM11.6 3H15v14h-3.4z")
    val WPrev = icon("wprev", 20f, "M4 3h2v14H4V3Zm12.2.6a.8.8 0 0 1 .8.7v11.4a.8.8 0 0 1-1.2.7L7.5 11a1.2 1.2 0 0 1 0-2l8.3-5.3a.8.8 0 0 1 .4-.1Z")
    val WNext = icon("wnext", 20f, "M16 3h-2v14h2V3ZM3.8 3.6a.8.8 0 0 0-.8.7v11.4a.8.8 0 0 0 1.2.7l8.3-5.4a1.2 1.2 0 0 0 0-2L4.2 3.7a.8.8 0 0 0-.4-.1Z")
    val WBack = icon("wback", 24f, "M12 4.5V1.8L7.2 5.5 12 9.2V6.5a6.5 6.5 0 1 1-6.5 6.5h-2A8.5 8.5 0 1 0 12 4.5Z")
    val WFwd = icon("wfwd", 24f, "M12 4.5V1.8l4.8 3.7L12 9.2V6.5A6.5 6.5 0 1 0 18.5 13h2A8.5 8.5 0 1 1 12 4.5Z")
    val WVolume = icon("wvol", 20f, "M2.5 7.2h3l4.2-3.6a.6.6 0 0 1 1 .5v11.8a.6.6 0 0 1-1 .5L5.5 12.8h-3a.8.8 0 0 1-.8-.8V8a.8.8 0 0 1 .8-.8ZM13.4 7a4 4 0 0 1 0 6l-1-1.1a2.5 2.5 0 0 0 0-3.8zM15.4 4.6a7.2 7.2 0 0 1 0 10.8l-1-1.1a5.7 5.7 0 0 0 0-8.6z")
    val WVolumeLow = icon("wvollow", 20f, "M2.5 7.2h3l4.2-3.6a.6.6 0 0 1 1 .5v11.8a.6.6 0 0 1-1 .5L5.5 12.8h-3a.8.8 0 0 1-.8-.8V8a.8.8 0 0 1 .8-.8ZM13.4 7a4 4 0 0 1 0 6l-1-1.1a2.5 2.5 0 0 0 0-3.8z")
    val WMuted = icon("wmuted", 20f, "M2.5 7.2h3l4.2-3.6a.6.6 0 0 1 1 .5v11.8a.6.6 0 0 1-1 .5L5.5 12.8h-3a.8.8 0 0 1-.8-.8V8a.8.8 0 0 1 .8-.8ZM13.1 7.6l1-1 1.9 1.9 1.9-1.9 1 1-1.9 1.9 1.9 1.9-1 1-1.9-1.9-1.9 1.9-1-1 1.9-1.9z")
    val WMonitor = icon("wmon", 16f, "M1.5 3.5A1.5 1.5 0 0 1 3 2h10a1.5 1.5 0 0 1 1.5 1.5v6A1.5 1.5 0 0 1 13 11H9v1.5h2V14H5v-1.5h2V11H3a1.5 1.5 0 0 1-1.5-1.5v-6ZM3 3.5v6h10v-6H3Z")
    val WChapters = icon("wch", 16f, "M2.7 2.65a1.35 1.35 0 1 1 0 2.7 1.35 1.35 0 0 1 0-2.7ZM2.7 6.65a1.35 1.35 0 1 1 0 2.7 1.35 1.35 0 0 1 0-2.7ZM2.7 10.65a1.35 1.35 0 1 1 0 2.7 1.35 1.35 0 0 1 0-2.7ZM5.6 3.25H14v1.5H5.6zM5.6 7.25H14v1.5H5.6zM5.6 11.25H14v1.5H5.6z")
    val WMoon = icon("wmoon", 16f, "M6.2 1.3a.6.6 0 0 1 .1.7A5.6 5.6 0 0 0 14 9.7a.6.6 0 0 1 .8.8A6.8 6.8 0 1 1 5.5 1.2a.6.6 0 0 1 .7.1Z")
    val WDown = icon("wdown", 16f, "M8 11.3 2.9 6.2 4 5.1l4 4 4-4 1.1 1.1z")

    val Play = icon("play", "M8,5v14l11,-7z")
    val Pause = icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Next = icon("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Prev = icon("prev", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Replay = icon("replay", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    val Forward = icon("forward", "M12,5V1l5,5 -5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8s-8,-3.58 -8,-8 3.58,-8 8,-8z")
    val Back = icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Down = icon("down", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
    val Search = icon("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val Download = icon("download", "M5,20h14v-2H5V20z M19,9h-4V3H9v6H5l7,7L19,9z")
    val Close = icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Check = icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val List = icon("list", "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z")
    val Timer = icon("timer", "M12,2C6.5,2 2,6.5 2,12s4.5,10 10,10 10,-4.5 10,-10S17.5,2 12,2zM16.2,16.2L11,13V7h1.5v5.2l4.5,2.7 -0.8,1.3z")
    val Speed = icon("speed", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z")
    val Tv = icon("tv", "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z")
    val Phone = icon("phone", "M16,1H8C6.34,1 5,2.34 5,4v16c0,1.66 1.34,3 3,3h8c1.66,0 3,-1.34 3,-3V4c0,-1.66 -1.34,-3 -3,-3zM14,21h-4v-1h4v1zM17.25,18H6.75V4h10.5v14z")
    val Devices = icon("devices", "M4,6h18V4H4c-1.1,0 -2,0.9 -2,2v11H0v3h14v-3H4V6zM23,8h-6c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V9c0,-0.55 -0.45,-1 -1,-1zM22,17h-4v-7h4v7z")
    val Edit = icon("edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    val Add = icon("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val More = icon("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    val VolumeUp = icon("volup", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    val VolumeDown = icon("voldown", "M18.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM5,9v6h4l5,5V4L9,9H5z")
    val VolumeOff = icon("voloff", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z")
    val Computer = icon("computer", "M20,18c1.1,0 1.99,-0.9 1.99,-2L22,6c0,-1.1 -0.9,-2 -2,-2H4c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2H0v2h24v-2h-4zM4,6h16v10H4V6z")
    val Refresh = icon("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
}
