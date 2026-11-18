package dev.rosty.platform.wa.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What a sealed WhatsApp push says (round 7b; `docs/whatsapp-gateway.md`, "Sealed push"). Only ever in memory: it goes
 * into a notification and, while the vault is open, nowhere else (the event socket brings the message itself).
 * Absent fields are null; a call carries [sender] and [name] only.
 */
@Serializable
data class PushContent(
    /** The chat JID (a message push). */
    val chat: String? = null,
    /** The other form of [chat] (the phone JID next to a LID), when the gateway knows it. */
    @SerialName("chat_alt") val chatAlt: String? = null,
    /** The chat's name on the gateway (a group's subject, a person's saved or push name). */
    @SerialName("chat_name") val chatName: String? = null,
    val group: Boolean = false,
    /** The author in a group; the caller of a call. */
    val sender: String? = null,
    @SerialName("sender_alt") val senderAlt: String? = null,
    /** The author's or caller's WhatsApp name. */
    val name: String? = null,
    /** The preview: the text, else the caption, else a media label; cut to fit FCM. */
    val text: String? = null,
    /** The WhatsApp message id. */
    val id: String? = null,
    /** When the message was sent (epoch ms). */
    val ts: Long? = null,
    /** The messages this push stands for (a message push). */
    val count: Int? = null,
) {
    /** Never the content: a log or a crash report shows only which fields are there. */
    override fun toString(): String = "PushContent(group=$group, count=$count)"
}

/** One step of opening a sealed push that needs this install's Keystore key. */
interface PushKeyAgreement {
    /** The uncompressed point (65 bytes) of the public half of [keyId], or null when this install has no such key. */
    fun devicePoint(keyId: String): ByteArray?

    /** ECDH of the private half of [keyId] with [peer]: the 32-byte x coordinate, or null when it cannot. */
    fun agree(
        keyId: String,
        peer: PublicKey,
    ): ByteArray?
}

/**
 * Opens a push the gateway sealed to this install's push key (scheme 1): ephemeral-static ECDH on P-256, HKDF-SHA256
 * (salt = the ephemeral point, info = `"rosty-wa-push/1" | device point`), AES-256-GCM with the nonce in front of the
 * ciphertext and `"rosty-wa-push/1|" kid` as associated data. Pure except for [PushKeyAgreement], so a JVM test opens
 * a push the Rust gateway sealed to a test key.
 */
object SealedPush {
    const val VERSION = "1"
    const val KEY_VERSION = 1
    const val VERSION_KEY = "v"
    const val KEY_ID_KEY = "kid"
    const val EPHEMERAL_KEY = "epk"
    const val CIPHERTEXT_KEY = "ct"

    private const val LABEL = "rosty-wa-push/1"
    private const val POINT_LEN = 65
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_LEN = 32

    /** The DER prefix of a P-256 `SubjectPublicKeyInfo` with an uncompressed point (the gateway's `P256_SPKI_PREFIX`). */
    private val SPKI_PREFIX =
        byteArrayOf(
            0x30,
            0x59,
            0x30,
            0x13,
            0x06,
            0x07,
            0x2a,
            0x86.toByte(),
            0x48,
            0xce.toByte(),
            0x3d,
            0x02,
            0x01,
            0x06,
            0x08,
            0x2a,
            0x86.toByte(),
            0x48,
            0xce.toByte(),
            0x3d,
            0x03,
            0x01,
            0x07,
            0x03,
            0x42,
            0x00,
        )

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether [data] is a sealed push (from a gateway that knew this install's key). */
    fun isSealed(data: Map<String, String>): Boolean = data[VERSION_KEY] != null && data[CIPHERTEXT_KEY] != null

    /** The key id a sealed push was made for, or null. */
    fun keyIdOf(data: Map<String, String>): String? = data[KEY_ID_KEY]?.takeIf { isSealed(data) }

    /** The 65-byte point of an EC P-256 public key ([PublicKey.getEncoded] is its `SubjectPublicKeyInfo`). */
    fun pointOf(key: PublicKey): ByteArray? {
        val der = key.encoded ?: return null
        if (der.size != SPKI_PREFIX.size + POINT_LEN ||
            !der.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)
        ) {
            return null
        }
        return der.copyOfRange(SPKI_PREFIX.size, der.size)
    }

    /** The P-256 public key of an uncompressed [point]. */
    fun publicKeyOf(point: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(SPKI_PREFIX + point),
        )

    /**
     * The content of the sealed push [data], or null when it is not sealed, is of another version, names a key this
     * install does not have (rotated or deleted) or does not open. Never throws; nothing is logged.
     */
    fun open(
        data: Map<String, String>,
        keys: PushKeyAgreement,
    ): PushContent? {
        if (!isSealed(data) || data[VERSION_KEY] != VERSION) return null
        val keyId = data[KEY_ID_KEY]?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val epk = Base64.getDecoder().decode(data[EPHEMERAL_KEY] ?: return null)
            val sealed = Base64.getDecoder().decode(data[CIPHERTEXT_KEY] ?: return null)
            if (epk.size != POINT_LEN || sealed.size < NONCE_LEN + TAG_BITS / 8) return null
            val device = keys.devicePoint(keyId) ?: return null
            val z = keys.agree(keyId, publicKeyOf(epk)) ?: return null
            val key = deriveKey(z, epk, device)
            z.fill(0)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_LEN),
            )
            key.fill(0)
            cipher.updateAAD("$LABEL|$keyId".toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(sealed, NONCE_LEN, sealed.size - NONCE_LEN)
            try {
                json.decodeFromString(PushContent.serializer(), plain.toString(Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** HKDF-SHA256 (RFC 5869) of the shared secret into the 32-byte AES key. */
    internal fun deriveKey(
        z: ByteArray,
        epk: ByteArray,
        devicePoint: ByteArray,
    ): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(epk, "HmacSHA256"))
        val prk = extract.doFinal(z)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        prk.fill(0)
        expand.update(LABEL.toByteArray(Charsets.UTF_8))
        expand.update(devicePoint)
        expand.update(1.toByte())
        return expand.doFinal().copyOf(KEY_LEN)
    }
}
