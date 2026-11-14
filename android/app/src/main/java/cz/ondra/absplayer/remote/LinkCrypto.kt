package cz.ondra.absplayer.remote

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto of the "Play on" link (protocol v3), the same as win/src/linkcrypto.js – keep them in step.
 * Pure JVM (no Android classes), so it is covered by unit tests together with the Node vectors.
 *
 * Pairing: ECDH P-256 (raw uncompressed points, base64) → Z. With T = "absplayer-pair-v3|pairId|
 * clientPub|receiverPub|deviceId" and salt = SHA-256(T): kConfirm / kEnc / link key = HKDF-SHA256(Z,
 * salt, "confirm" / "enc" / "link"). The code shown on the receiver is checked by
 * HMAC(kConfirm, "client|code") and HMAC(kConfirm, "receiver|code"); credentials travel in
 * AES-256-GCM boxes under kEnc. A passive listener learns nothing; the link key never travels.
 *
 * Requests: X-Client, X-Time (receiver clock, ms), X-Nonce, X-Sig = HMAC(link,
 * "req\nMETHOD\npath\ntime\nnonce\nsha256hex(body)"); the receiver answers with X-Sig = HMAC(link,
 * "resp\nnonce\nstatus\nsha256hex(body)"), so the client also knows it talks to the paired device.
 */
object LinkCrypto {
    private val rnd = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun b64(bytes: ByteArray): String = b64.encodeToString(bytes)
    fun unb64(s: String): ByteArray = unb64.decode(s)

    fun randomHex(bytes: Int): String = ByteArray(bytes).also { rnd.nextBytes(it) }.hex()

    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun sha256Hex(text: String): String = sha256(text.toByteArray()).hex()

    fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        }.doFinal(ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            mac.update(t); mac.update(info.toByteArray()); mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** Constant-time comparison of two base64 signatures. */
    fun sameSig(a: String?, b: String): Boolean =
        a != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    // ------------------------------------------------------------------ ECDH

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()

    /** Raw uncompressed point 04 || X || Y, base64. */
    fun publicRaw(kp: KeyPair): String {
        val w = (kp.public as ECPublicKey).w
        fun p32(v: BigInteger) = v.toByteArray().let { if (it.size >= 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return b64(byteArrayOf(4) + p32(w.affineX) + p32(w.affineY))
    }

    /** Shared secret (the X coordinate, as Node's ECDH.computeSecret). */
    fun sharedSecret(kp: KeyPair, peerRawB64: String): ByteArray {
        val raw = unb64(peerRawB64)
        require(raw.size == 65 && raw[0] == 4.toByte()) { "bad public key" }
        val params = (kp.public as ECPublicKey).params
        val pt = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(pt, params))
        return KeyAgreement.getInstance("ECDH").apply { init(kp.private); doPhase(peer, true) }.generateSecret()
    }

    // ------------------------------------------------------------------ pairing keys

    class PairKeys(val confirm: ByteArray, val enc: ByteArray, val link: ByteArray)

    fun pairKeys(z: ByteArray, pairId: String, clientPub: String, receiverPub: String, deviceId: String): PairKeys {
        val salt = sha256("absplayer-pair-v3|$pairId|$clientPub|$receiverPub|$deviceId".toByteArray())
        return PairKeys(hkdf(z, salt, "confirm"), hkdf(z, salt, "enc"), hkdf(z, salt, "link"))
    }

    fun proof(keys: PairKeys, side: String, code: String): String = b64(hmac(keys.confirm, "$side|${code.trim()}"))

    /** AES-256-GCM: base64(iv || ciphertext || tag); [aad] = "client" / "receiver". */
    fun seal(key: ByteArray, aad: String, plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad.toByteArray())
        }
        return b64(iv + c.doFinal(plain.toByteArray()))
    }

    fun open(key: ByteArray, aad: String, box: String): String {
        val raw = unb64(box)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12)); updateAAD(aad.toByteArray())
        }
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    // ------------------------------------------------------------------ requests

    fun requestSig(link: ByteArray, method: String, path: String, time: Long, nonce: String, body: String): String =
        b64(hmac(link, "req\n${method.uppercase()}\n$path\n$time\n$nonce\n${sha256Hex(body)}"))

    fun responseSig(link: ByteArray, nonce: String, status: Int, body: String): String =
        b64(hmac(link, "resp\n$nonce\n$status\n${sha256Hex(body)}"))

    /** Link of a pairing made with protocol v2 (a shared secret): both sides derive the same id and key. */
    fun legacyLink(secret: String): Pair<String, String> =
        sha256Hex("absplayer-v2|$secret").take(16) to b64(hkdf(secret.toByteArray(), ByteArray(0), "link-v2"))
}
