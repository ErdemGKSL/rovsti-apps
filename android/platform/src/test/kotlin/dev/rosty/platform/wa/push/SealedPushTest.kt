package dev.rosty.platform.wa.push

import dev.rosty.platform.wa.OpenedPush
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A software stand-in for the Keystore key: a test-only key pair, used for one test run. */
class SoftPushKeys(
    private val keyId: String,
    private val private: PrivateKey,
    private val public: PublicKey,
) : PushKeyAgreement {
    override fun devicePoint(keyId: String): ByteArray? = if (keyId == this.keyId) SealedPush.pointOf(public) else null

    override fun agree(
        keyId: String,
        peer: PublicKey,
    ): ByteArray? =
        if (keyId != this.keyId) {
            null
        } else {
            KeyAgreement.getInstance("ECDH").run {
                init(private)
                doPhase(peer, true)
                generateSecret()
            }
        }

    /** Seals [json] to this key as the gateway does (scheme 1), for tests of what the app does with it. */
    fun seal(
        json: String,
        clear: Map<String, String>,
    ): Map<String, String> {
        val ephemeral =
            KeyPairGenerator
                .getInstance("EC")
                .apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()
        val epk = SealedPush.pointOf(ephemeral.public)!!
        val z =
            KeyAgreement.getInstance("ECDH").run {
                init(ephemeral.private)
                doPhase(public, true)
                generateSecret()
            }
        val key = SealedPush.deriveKey(z, epk, SealedPush.pointOf(public)!!)
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("rosty-wa-push/1|$keyId".toByteArray())
        val ct = nonce + cipher.doFinal(json.toByteArray())
        val b64 = Base64.getEncoder()
        return clear +
            mapOf("v" to "1", "kid" to keyId, "epk" to b64.encodeToString(epk), "ct" to b64.encodeToString(ct))
    }

    companion object {
        /** A fresh test-only key pair. */
        fun generate(keyId: String = "k1"): SoftPushKeys {
            val pair =
                KeyPairGenerator
                    .getInstance(
                        "EC",
                    ).apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            return SoftPushKeys(keyId, pair.private, pair.public)
        }
    }
}

/**
 * Round 7b: the app opens a push the gateway sealed (the fixture is sealed by `services/wa_gateway` to a test-only key
 * pair, `the_app_fixture_is_a_push_sealed_by_this_code`), and nothing else.
 */
class SealedPushTest {
    private val fixture =
        JSONObject(
            javaClass.getResourceAsStream("/dev/rosty/platform/wa/push/sealed-push-fixture.json")!!.reader().readText(),
        )

    private val keys: SoftPushKeys by lazy {
        val factory = KeyFactory.getInstance("EC")
        val b64 = Base64.getDecoder()
        SoftPushKeys(
            fixture.getString("key_id"),
            factory.generatePrivate(PKCS8EncodedKeySpec(b64.decode(fixture.getString("private_key_pkcs8")))),
            factory.generatePublic(X509EncodedKeySpec(b64.decode(fixture.getString("public_key_spki")))),
        )
    }

    private val data: Map<String, String> by lazy {
        val d = fixture.getJSONObject("data")
        d.keys().asSequence().associateWith { d.getString(it) }
    }

    @Test fun aPushTheGatewaySealedOpens() {
        assertTrue(SealedPush.isSealed(data))
        assertEquals("testkey01", SealedPush.keyIdOf(data))
        val content = SealedPush.open(data, keys)
        assertNotNull(content)
        content!!
            .also { assertEquals(2, it.count) }
        val preview = fixture.getJSONObject("preview")
        assertEquals(
            PushContent(
                chat = preview.getString("chat"),
                chatName = preview.getString("chat_name"),
                name = preview.getString("name"),
                text = preview.getString("text"),
                id = preview.getString("id"),
                ts = preview.getLong("ts"),
                count = 2,
            ),
            content,
        )
        // A log line or a crash report never prints what it says.
        assertFalse(content.toString().contains("Merhaba"))
    }

    @Test fun anythingElseDoesNotOpen() {
        // Another install's key, an unknown key id, another version, a changed byte.
        assertNull(SealedPush.open(data, SoftPushKeys.generate("testkey01")))
        assertNull(SealedPush.open(data + ("kid" to "other"), keys))
        assertNull(SealedPush.open(data + ("v" to "2"), keys))
        val ct = Base64.getDecoder().decode(data.getValue("ct"))
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        assertNull(SealedPush.open(data + ("ct" to Base64.getEncoder().encodeToString(ct)), keys))
        assertNull(SealedPush.open(data + ("epk" to "AAAA"), keys))
        assertNull(SealedPush.open(mapOf("kind" to "message", "chat" to "c", "count" to "1"), keys))
    }

    @Test fun anOpenedPushReadsLikeTheContentFreeOne() {
        val opened = OpenedPush.open(data, keys)
        assertTrue(opened.sealed)
        assertEquals("100000000042@s.whatsapp.net", opened.data["chat"])
        assertEquals("2", opened.data["count"])
        // What may be broadcast or logged: only what was in clear.
        assertEquals(mapOf("kind" to "message"), opened.routing)
        assertFalse(opened.toString().contains("100000000042"))

        val failed = OpenedPush.open(data, SoftPushKeys.generate())
        assertTrue(failed.sealed)
        assertNull(failed.content)
        assertEquals(mapOf("kind" to "message"), failed.data)

        val clear = mapOf("kind" to "message", "chat" to "c", "count" to "3")
        assertEquals(clear, OpenedPush.open(clear, keys).data)
        assertFalse(OpenedPush.open(clear, keys).sealed)
    }

    @Test fun aSealedCallKeepsItsIdInClear() {
        val keys = SoftPushKeys.generate()
        val data =
            keys.seal(
                """{"sender":"100000000005@s.whatsapp.net","name":"Ada"}""",
                mapOf("kind" to "call", "call_id" to "c1"),
            )
        val opened = OpenedPush.open(data, keys)
        assertEquals("c1", opened.data["call_id"])
        assertEquals("100000000005@s.whatsapp.net", opened.data["sender"])
        assertEquals("Ada", opened.data["name"])
        assertEquals(mapOf("kind" to "call", "call_id" to "c1"), opened.routing)
    }

    @Test fun theKeyPointIsTheEndOfItsSubjectPublicKeyInfo() {
        val keys = SoftPushKeys.generate()
        val point = keys.devicePoint("k1")!!
        assertEquals(65, point.size)
        assertEquals(4, point[0].toInt())
        assertEquals(point.toList(), SealedPush.pointOf(SealedPush.publicKeyOf(point))!!.toList())
    }
}
