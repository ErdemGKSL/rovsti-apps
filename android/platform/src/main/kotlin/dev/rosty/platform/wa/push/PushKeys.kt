package dev.rosty.platform.wa.push

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.PushKey
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * This install's push key (round 7b): the gateway seals what a WhatsApp push says to its public half, and only this
 * key opens it. [ensure] gives the public half for the FCM token registration; [rotate] drops the key (a vault reset, a
 * new link): the next registration sends a new one, and a push sealed to the old key falls back to the content-free
 * notice.
 */
interface PushKeyStore : PushKeyAgreement {
    /** The public half of the current key, made first when there is none; null when the Keystore refuses. */
    fun ensure(): PushKey?

    /** The id of the current key, or null when there is none. Never creates one. */
    fun currentId(): String?

    /** Deletes every push key of this install. */
    fun rotate()
}

/**
 * [PushKeyStore] in Android Keystore: an EC P-256 key for key agreement (`PURPOSE_AGREE_KEY`, Android 12+; the apps'
 * minSdk is 34), non-exportable, with no user-authentication or unlocked-device requirement, so a push opens while the
 * phone and the vault are locked. Not StrongBox: StrongBox key agreement is not on every phone, and this key guards
 * only what the notification shows anyway. The key id is the alias suffix (`rosty.wa.push.<id>`), so nothing else is
 * stored. Never logs a key or anything a push says.
 */
class AndroidPushKeyStore : PushKeyStore {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun aliases(store: KeyStore): List<String> =
        store
            .aliases()
            .toList()
            .filter { it.startsWith(ALIAS_PREFIX) }
            .sorted()

    @Synchronized
    override fun ensure(): PushKey? =
        try {
            val alias = aliases(keyStore()).firstOrNull() ?: create()
            keyStore().getCertificate(alias)?.publicKey?.let {
                PushKey(
                    keyId = alias.removePrefix(ALIAS_PREFIX),
                    version = SealedPush.KEY_VERSION,
                    publicKey = Base64.getEncoder().encodeToString(it.encoded),
                )
            }
        } catch (e: GeneralSecurityException) {
            RostyLog.w(TAG, "no push key (${e.javaClass.simpleName})")
            null
        } catch (e: RuntimeException) {
            RostyLog.w(TAG, "no push key (${e.javaClass.simpleName})")
            null
        }

    override fun currentId(): String? =
        try {
            aliases(keyStore()).firstOrNull()?.removePrefix(ALIAS_PREFIX)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    @Synchronized
    override fun rotate() {
        try {
            val store = keyStore()
            aliases(store).forEach(store::deleteEntry)
        } catch (e: GeneralSecurityException) {
            RostyLog.w(TAG, "push key not deleted (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            RostyLog.w(TAG, "push key not deleted (${e.javaClass.simpleName})")
        }
    }

    override fun devicePoint(keyId: String): ByteArray? =
        try {
            keyStore().getCertificate(ALIAS_PREFIX + keyId)?.publicKey?.let(SealedPush::pointOf)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    override fun agree(
        keyId: String,
        peer: PublicKey,
    ): ByteArray? =
        try {
            val private = keyStore().getKey(ALIAS_PREFIX + keyId, null) as? PrivateKey
            private?.let {
                KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE).run {
                    init(it)
                    doPhase(peer, true)
                    generateSecret()
                }
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** A new key under a random id; returns its alias. */
    private fun create(): String {
        val id = ByteArray(ID_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val alias = ALIAS_PREFIX + id
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
        return alias
    }

    companion object {
        const val ALIAS_PREFIX = "rosty.wa.push."
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CURVE = "secp256r1"
        private const val ID_BYTES = 8
        private const val TAG = "wa-push"
    }
}

/** The push key store of the process: Android Keystore unless a test installs another. */
object PushKeys {
    @Volatile private var installed: PushKeyStore? = null

    val store: PushKeyStore
        get() = installed ?: synchronized(this) { installed ?: AndroidPushKeyStore().also { installed = it } }

    /** Tests: use [store] instead of the Keystore (null goes back to it). */
    fun install(store: PushKeyStore?) {
        installed = store
    }
}
