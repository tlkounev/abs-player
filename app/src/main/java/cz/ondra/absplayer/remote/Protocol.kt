package cz.ondra.absplayer.remote

import kotlinx.serialization.Serializable

/**
 * Phone ⇄ TV protocol (plain HTTP + JSON on the LAN; the TV advertises itself over mDNS as
 * [SERVICE_TYPE]). Everything except /info and /pair needs the `X-Secret` header the TV handed
 * out at pairing.
 *
 *   GET  /info   -> InfoResponse
 *   POST /pair   PairRequest -> PairResponse   (needs the code shown on the receiver)
 *   GET  /state  -> NowPlaying
 *   POST /cmd    Command -> NowPlaying
 *
 * Receivers: the Google TV app (kind "tv") and Audiobookshelf Tray on Windows (kind "pc").
 * A TV plays on its own login, so pairing hands it a token pair unless it is already signed in
 * to the same account. The PC uses the login of its ABS web window and needs no tokens.
 */
const val SERVICE_TYPE = "_absplayer._tcp"
const val DEFAULT_PORT = 8765
const val PROTOCOL_VERSION = 2

@Serializable
data class InfoResponse(
    val deviceId: String,
    val name: String,
    val loggedIn: Boolean,
    val server: String? = null,
    val username: String? = null,
    val version: Int = PROTOCOL_VERSION,
    /** "tv" | "pc" */
    val kind: String = "tv",
    /** Receiver plays on credentials handed over at pairing (TV), not on its own login (PC). */
    val acceptsTokens: Boolean = true,
)

@Serializable
data class PairRequest(
    val code: String,
    val server: String,
    val username: String,
    val userId: String? = null,
    /** Null when the receiver needs no credentials (already signed in to this account, or PC). */
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val phoneName: String = "",
)

@Serializable
data class PairResponse(val secret: String, val deviceId: String, val name: String)

@Serializable
data class Command(
    /** play | pause | toggle | seek | skip | chapter | next | prev | speed | sleep | volume | mute | playItem | stop */
    val action: String,
    val value: Double? = null,
    val itemId: String? = null,
)

@Serializable
data class ErrorResponse(val error: String)

/** Server URLs typed on different devices may differ in case or a trailing slash. */
fun sameServer(a: String?, b: String?): Boolean =
    a != null && b != null && a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
