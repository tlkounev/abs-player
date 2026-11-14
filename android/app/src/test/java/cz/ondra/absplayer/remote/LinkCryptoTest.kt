package cz.ondra.absplayer.remote

import cz.ondra.absplayer.remote.LinkCrypto.hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/** LinkCrypto must give exactly what win/src/linkcrypto.js gives (vectors from win/test/gen-link-vectors.js). */
class LinkCryptoTest {
    private val v: Map<String, String> = run {
        val text = javaClass.classLoader!!.getResource("link-vectors.json")!!.readText()
        (Json.parseToJsonElement(text) as JsonObject).mapValues { it.value.jsonPrimitive.content }
    }

    private fun keyPair(privHex: String, pubB64: String): KeyPair {
        val params = (LinkCrypto.newKeyPair().public as ECPublicKey).params
        val raw = LinkCrypto.unb64(pubB64)
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), params))
        val priv = kf.generatePrivate(ECPrivateKeySpec(BigInteger(privHex, 16), params))
        return KeyPair(pub, priv)
    }

    @Test
    fun ecdhAndKeysMatchNode() {
        val client = keyPair(v.getValue("clientPriv"), v.getValue("clientPub"))
        val receiver = keyPair(v.getValue("receiverPriv"), v.getValue("receiverPub"))
        assertEquals(v["clientPub"], LinkCrypto.publicRaw(client))
        val z1 = LinkCrypto.sharedSecret(client, v.getValue("receiverPub"))
        val z2 = LinkCrypto.sharedSecret(receiver, v.getValue("clientPub"))
        assertEquals(v["z"], z1.hex())
        assertEquals(v["z"], z2.hex())
        val k = LinkCrypto.pairKeys(z1, v.getValue("pairId"), v.getValue("clientPub"), v.getValue("receiverPub"), v.getValue("deviceId"))
        assertEquals(v["confirm"], k.confirm.hex())
        assertEquals(v["enc"], k.enc.hex())
        assertEquals(v["link"], k.link.hex())
        assertEquals(v["clientProof"], LinkCrypto.proof(k, "client", v.getValue("code")))
        assertEquals(v["receiverProof"], LinkCrypto.proof(k, "receiver", " " + v.getValue("code") + " "))
        assertEquals(v["boxPlain"], LinkCrypto.open(k.enc, "client", v.getValue("box")))
        val mine = LinkCrypto.seal(k.enc, "receiver", "hello")
        assertEquals("hello", LinkCrypto.open(k.enc, "receiver", mine))
        assertFalse(runCatching { LinkCrypto.open(k.enc, "client", mine) }.isSuccess) // the aad is checked
    }

    @Test
    fun signaturesMatchNode() {
        val link = BigInteger(v.getValue("link"), 16).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        val time = v.getValue("time").toLong()
        assertEquals(v["requestSig"], LinkCrypto.requestSig(link, "POST", "/cmd", time, v.getValue("nonce"), v.getValue("body")))
        assertEquals(v["responseSig"], LinkCrypto.responseSig(link, v.getValue("nonce"), 200, "{\"ok\":true}"))
        assertTrue(LinkCrypto.sameSig(v["responseSig"], LinkCrypto.responseSig(link, v.getValue("nonce"), 200, "{\"ok\":true}")))
        assertFalse(LinkCrypto.sameSig(v["responseSig"], LinkCrypto.responseSig(link, v.getValue("nonce"), 200, "{\"ok\":false}")))
    }

    @Test
    fun legacyLinkMatchesNode() {
        val (id, key) = LinkCrypto.legacyLink(v.getValue("legacySecret"))
        assertEquals(v["legacyClientId"], id)
        assertEquals(v["legacyKey"], key)
    }
}
