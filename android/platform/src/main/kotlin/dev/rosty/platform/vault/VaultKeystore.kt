package dev.rosty.platform.vault

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/**
 * The Keystore half of the WhatsApp vault (`_bmad-output/kotlin-ui/plans/wa-vault.md` 3, 13.2, 15). Kotlin never holds
 * the data key, the daily key or the connect key: it only seals and opens the opaque blobs Rust hands it (the key blob
 * with K_hw, the daily blob with K_daily) and gives the result back. Every operation needs the user: each seal and each
 * open shows the system prompt (biometric, or the screen lock where the key allows it), because both keys require
 * authentication per use.
 *
 * - K_hw ([VaultKeyKind.Hardware]): AES-256-GCM, non-exportable, StrongBox when the phone has it (else the TEE),
 *   usable only while the phone is unlocked, a Class 3 biometric or the screen lock per use, gone when a biometric is
 *   enrolled. One alias per passphrase: a passphrase change makes a new one and deletes the old.
 * - K_daily ([VaultKeyKind.Daily]): the same, but a Class 3 biometric only; the screen lock only on a phone with no
 *   Class 3 biometric enrolled. Its alias says which ([VaultKeyAuth]).
 *
 * The real one is [AndroidVaultKeystore]; JVM tests use `FakeVaultKeystore`.
 */
interface VaultKeystore {
    /** What the phone offers right now. */
    fun device(): VaultDevice

    /** A fresh alias for a new key of [kind]; nothing is created until the first [seal]. */
    fun newAlias(kind: VaultKeyKind): String

    /** Encrypts [plainB64] (base64) with the key [alias], creating it first when it does not exist. */
    suspend fun seal(
        alias: String,
        plainB64: String,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit = {},
    ): VaultCrypto

    /** Decrypts [sealedB64] (what [seal] answered) with the key [alias]. */
    suspend fun open(
        alias: String,
        sealedB64: String,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit = {},
    ): VaultCrypto

    /** Deletes the key [alias]; nothing happens when it does not exist. */
    fun delete(alias: String)

    /** Every vault alias in the Keystore (see [VaultAlias.PREFIX]). */
    fun aliases(): List<String>
}

/** Which key: K_hw (the passphrase vault) or K_daily (the biometric unlock). */
enum class VaultKeyKind { Hardware, Daily }

/** How a key is unlocked for one operation. */
enum class VaultKeyAuth {
    /** A Class 3 biometric or the screen lock (K_hw). */
    BiometricOrCredential,

    /** A Class 3 biometric only (K_daily on a phone with one enrolled). */
    Biometric,

    /** The screen lock only (K_daily on a phone without a Class 3 biometric). */
    Credential,
}

/** What the phone offers for the vault keys. */
data class VaultDevice(
    /** A PIN, pattern or password is set: without one no key can require authentication. */
    val secureLockScreen: Boolean,
    /** A Class 3 (strong) biometric is enrolled. */
    val strongBiometric: Boolean,
    /** The phone has a StrongBox Keymaster. */
    val strongBox: Boolean,
)

/** The words of the system prompt. [negative] is the button of a biometric-only prompt (the screen lock has its own). */
data class VaultPrompt(
    val title: String,
    val subtitle: String? = null,
    val negative: String,
)

/** The outcome of a seal or an open. */
sealed interface VaultCrypto {
    /** The result, base64. */
    data class Done(
        val b64: String,
    ) : VaultCrypto {
        override fun toString(): String = "Done(${b64.length} chars)"
    }

    /** The person closed the prompt or chose its other button. */
    data object Cancelled : VaultCrypto

    /** The key is gone or no longer usable (a biometric was enrolled, the screen lock was removed). */
    data object Invalidated : VaultCrypto

    /** No screen lock (or no biometric for a biometric-only key): the key cannot be used. */
    data object NoCredential : VaultCrypto

    /** Too many failed attempts: the system locked the biometric out for a while. */
    data object LockedOut : VaultCrypto

    /** The sealed bytes do not open (damaged), or the Keystore failed. [reason] is for the log only. */
    data class Failed(
        val reason: String,
    ) : VaultCrypto
}

/**
 * The alias naming: `rosty.wa.k.<id>` for K_hw; `rosty.wa.d.b.<id>` (biometric) and `rosty.wa.d.c.<id>` (screen lock)
 * for K_daily. Older or test aliases (`rosty.wa.k1`, `rosty.wa.d1`) read by their kind's prefix.
 */
object VaultAlias {
    const val PREFIX = "rosty.wa."
    private const val HARDWARE = "rosty.wa.k"
    private const val DAILY = "rosty.wa.d"
    private const val DAILY_BIOMETRIC = "rosty.wa.d.b."
    private const val DAILY_CREDENTIAL = "rosty.wa.d.c."
    private const val ID_BYTES = 8

    fun hardware(id: String = randomId()): String = "$HARDWARE.$id"

    fun daily(
        auth: VaultKeyAuth,
        id: String = randomId(),
    ): String = if (auth == VaultKeyAuth.Credential) "$DAILY_CREDENTIAL$id" else "$DAILY_BIOMETRIC$id"

    fun kindOf(alias: String): VaultKeyKind? =
        when {
            alias.startsWith(DAILY) -> VaultKeyKind.Daily
            alias.startsWith(HARDWARE) -> VaultKeyKind.Hardware
            else -> null
        }

    fun authOf(alias: String): VaultKeyAuth =
        when {
            alias.startsWith(DAILY_CREDENTIAL) -> VaultKeyAuth.Credential
            alias.startsWith(DAILY) -> VaultKeyAuth.Biometric
            else -> VaultKeyAuth.BiometricOrCredential
        }

    /** The auth a new daily key gets on a phone that [device] describes (plan 13.2). */
    fun dailyAuth(device: VaultDevice): VaultKeyAuth =
        if (device.strongBiometric) VaultKeyAuth.Biometric else VaultKeyAuth.Credential

    private fun randomId(): String {
        val bytes = ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The framing of a sealed blob: version 1, the 12-byte GCM nonce the Keystore chose, then ciphertext and tag. Pure, so
 * a JVM test covers it.
 */
object VaultSealed {
    const val VERSION: Byte = 1
    const val IV_BYTES = 12
    const val TAG_BITS = 128

    fun frame(
        iv: ByteArray,
        sealed: ByteArray,
    ): String {
        require(iv.size == IV_BYTES) { "the GCM nonce is $IV_BYTES bytes" }
        val out =
            ByteBuffer
                .allocate(1 + iv.size + sealed.size)
                .put(VERSION)
                .put(iv)
                .put(sealed)
                .array()
        return Base64.getEncoder().encodeToString(out)
    }

    /** The nonce and the ciphertext of [b64]; null when it is not a blob of this version. */
    fun unframe(b64: String): Pair<ByteArray, ByteArray>? {
        val bytes =
            try {
                Base64.getDecoder().decode(b64.trim())
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (bytes.size <= 1 + IV_BYTES + TAG_BITS / Byte.SIZE_BITS || bytes[0] != VERSION) return null
        return bytes.copyOfRange(1, 1 + IV_BYTES) to bytes.copyOfRange(1 + IV_BYTES, bytes.size)
    }
}
