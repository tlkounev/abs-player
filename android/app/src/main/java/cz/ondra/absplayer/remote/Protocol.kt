package cz.ondra.absplayer.remote

import kotlinx.serialization.Serializable

/**
 * Phone ⇄ TV protocol v3 (HTTP + JSON on the LAN; receivers advertise themselves over mDNS as
 * [SERVICE_TYPE]). Pairing and request signing: see [LinkCrypto].
 *
 *   GET    /info         -> InfoResponse
 *   POST   /pair/start   PairStart -> PairStartResponse
 *   POST   /pair/finish  PairFinish (box: PairPayload) -> PairFinishResponse (box: PairResult)
 *   GET    /state        -> NowPlaying                 (signed)
 *   POST   /cmd          Command -> NowPlaying         (signed)
 *   DELETE /pair         forget this controller        (signed)
 *
 * No secret travels after pairing and the receiver signs its answers, so a device that only claims a
 * paired deviceId (public in /info) learns nothing and is not believed.
 * Receivers: the Google TV app (kind "tv") and ABS Player for Windows (kind "pc").
 * A TV plays on its own login, so pairing hands it a token pair (inside the encrypted box) unless it
 * is already signed in to the same account. The PC uses the login of its ABS web window.
 */
const val SERVICE_TYPE = "_absplayer._tcp"
const val DEFAULT_PORT = 8765
const val PROTOCOL_VERSION = 3

/** Allowed difference of a request's time from the receiver's clock (clients follow /info's time). */
const val MAX_SKEW_MS = 120_000L

@Serializable
data class InfoResponse(
    val deviceId: String,
    val name: String,
    val loggedIn: Boolean,
    val server: String? = null,
    val username: String? = null,
    val version: Int = 2,
    /** "tv" | "pc" */
    val kind: String = "tv",
    /** Receiver plays on credentials handed over at pairing (TV), not on its own login (PC). */
    val acceptsTokens: Boolean = true,
    /** The receiver's clock (ms) – signed requests carry its time. */
    val time: Long? = null,
)

@Serializable
data class PairStart(val clientPub: String)

@Serializable
data class PairStartResponse(val pairId: String, val receiverPub: String)

@Serializable
data class PairFinish(val pairId: String, val proof: String, val box: String)

@Serializable
data class PairFinishResponse(val proof: String, val box: String)

/** Inside the client's box: who pairs, and for a TV the token pair it should play on. */
@Serializable
data class PairPayload(
    val server: String,
    val username: String,
    val userId: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val name: String = "",
    /** "phone" | "tv" | "pc" – the icon in the receiver's list of controllers. */
    val kind: String = "",
)

/** Inside the receiver's box. */
@Serializable
data class PairResult(val clientId: String, val deviceId: String, val name: String)

@Serializable
data class Command(
    /** play | pause | toggle | seek | skip | chapter | next | prev | speed | sleep | volume | mute | playItem | stop */
    val action: String,
    val value: Double? = null,
    val itemId: String? = null,
)

@Serializable
data class ErrorResponse(
    val error: String,
    /** Machine-readable: "needPassword" | "expired" | "updateNeeded"… */
    val code: String? = null,
    /** With error "clock": the receiver's time. */
    val time: Long? = null,
)

/** Server URLs typed on different devices may differ in case or a trailing slash. */
fun sameServer(a: String?, b: String?): Boolean =
    a != null && b != null && a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
