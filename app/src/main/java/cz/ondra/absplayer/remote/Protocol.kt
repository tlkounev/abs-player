package cz.ondra.absplayer.remote

import kotlinx.serialization.Serializable

/**
 * Phone ⇄ TV protocol (plain HTTP + JSON on the LAN; the TV advertises itself over mDNS as
 * [SERVICE_TYPE]). Everything except /info and /pair needs the `X-Secret` header the TV handed
 * out at pairing.
 *
 *   GET  /info   -> InfoResponse
 *   POST /pair   PairRequest -> PairResponse   (needs the code shown on the TV)
 *   GET  /state  -> NowPlaying
 *   POST /cmd    Command -> NowPlaying
 */
const val SERVICE_TYPE = "_absplayer._tcp"
const val DEFAULT_PORT = 8765
const val PROTOCOL_VERSION = 1

@Serializable
data class InfoResponse(
    val deviceId: String,
    val name: String,
    val loggedIn: Boolean,
    val server: String? = null,
    val username: String? = null,
    val version: Int = PROTOCOL_VERSION,
)

@Serializable
data class PairRequest(
    val code: String,
    val server: String,
    val username: String,
    val userId: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val phoneName: String = "",
)

@Serializable
data class PairResponse(val secret: String, val deviceId: String, val name: String)

@Serializable
data class Command(
    /** play | pause | toggle | seek | skip | chapter | next | prev | speed | sleep | playItem | stop */
    val action: String,
    val value: Double? = null,
    val itemId: String? = null,
)

@Serializable
data class ErrorResponse(val error: String)
