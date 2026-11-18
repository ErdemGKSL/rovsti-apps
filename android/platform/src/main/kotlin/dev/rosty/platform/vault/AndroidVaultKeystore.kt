package dev.rosty.platform.vault

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [VaultKeystore] over the AndroidKeyStore. Keys are generated inside the secure hardware (StrongBox when the phone
 * has it, else the TEE) and never leave it; this class only initializes a [Cipher] with one, has [authenticator] show
 * the prompt that authorizes that one operation, and runs it.
 */
class AndroidVaultKeystore(
    private val context: Context,
    private val authenticator: VaultAuthenticator,
) : VaultKeystore {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun device(): VaultDevice {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val strong =
            BiometricManager.from(context).canAuthenticate(Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        return VaultDevice(
            secureLockScreen = keyguard?.isDeviceSecure == true,
            strongBiometric = strong,
            strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
        )
    }

    override fun newAlias(kind: VaultKeyKind): String =
        when (kind) {
            VaultKeyKind.Hardware -> VaultAlias.hardware()
            VaultKeyKind.Daily -> VaultAlias.daily(VaultAlias.dailyAuth(device()))
        }

    override suspend fun seal(
        alias: String,
        plainB64: String,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit,
    ): VaultCrypto {
        val auth = VaultAlias.authOf(alias)
        val plain =
            try {
                Base64.getDecoder().decode(plainB64.trim())
            } catch (_: IllegalArgumentException) {
                return VaultCrypto.Failed("not base64")
            }
        try {
            val cipher =
                withContext(Dispatchers.IO) {
                    val key = existing(alias) ?: create(alias, auth)
                    Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
                }
            return when (val authorized = authenticator.authenticate(cipher, auth, prompt, onFailedAttempt)) {
                is VaultAuthResult.Authorized -> {
                    val sealed = withContext(Dispatchers.IO) { authorized.cipher.doFinal(plain) }
                    VaultCrypto.Done(VaultSealed.frame(authorized.cipher.iv, sealed))
                }

                else -> {
                    authorized.toCrypto()
                }
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            return VaultCrypto.Invalidated
        } catch (e: GeneralSecurityException) {
            // The key generator refuses a key that needs authentication on a phone without a screen lock.
            return if (!device().secureLockScreen) VaultCrypto.NoCredential else failed(e)
        } catch (e: IllegalStateException) {
            return if (!device().secureLockScreen) VaultCrypto.NoCredential else failed(e)
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun open(
        alias: String,
        sealedB64: String,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit,
    ): VaultCrypto {
        val (iv, sealed) = VaultSealed.unframe(sealedB64) ?: return VaultCrypto.Failed("not a sealed blob")
        try {
            val cipher =
                withContext(Dispatchers.IO) {
                    val key = existing(alias) ?: return@withContext null
                    Cipher.getInstance(TRANSFORMATION).apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(VaultSealed.TAG_BITS, iv))
                    }
                } ?: return VaultCrypto.Invalidated
            val auth = VaultAlias.authOf(alias)
            return when (val authorized = authenticator.authenticate(cipher, auth, prompt, onFailedAttempt)) {
                is VaultAuthResult.Authorized -> {
                    val plain = withContext(Dispatchers.IO) { authorized.cipher.doFinal(sealed) }
                    try {
                        VaultCrypto.Done(Base64.getEncoder().encodeToString(plain))
                    } finally {
                        plain.fill(0)
                    }
                }

                else -> {
                    authorized.toCrypto()
                }
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            return VaultCrypto.Invalidated
        } catch (_: AEADBadTagException) {
            return VaultCrypto.Failed("damaged")
        } catch (e: GeneralSecurityException) {
            return failed(e)
        }
    }

    override fun delete(alias: String) {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
            // Already gone, or the Keystore refused: the vault no longer names it either way.
        } catch (_: java.io.IOException) {
        }
    }

    override fun aliases(): List<String> =
        try {
            keyStore().aliases().toList().filter { it.startsWith(VaultAlias.PREFIX) }
        } catch (_: GeneralSecurityException) {
            emptyList()
        } catch (_: java.io.IOException) {
            emptyList()
        }

    private fun existing(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    /** A new non-exportable AES-256-GCM key: StrongBox first, the TEE when the phone has no StrongBox. */
    private fun create(
        alias: String,
        auth: VaultKeyAuth,
    ): SecretKey {
        val strongBox = device().strongBox
        if (strongBox) {
            try {
                return generate(spec(alias, auth, strongBox = true))
            } catch (_: StrongBoxUnavailableException) {
                // Advertised but busy or unable: the TEE holds it instead.
            }
        }
        return generate(spec(alias, auth, strongBox = false))
    }

    private fun generate(spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()

    private fun failed(e: Exception): VaultCrypto = VaultCrypto.Failed(e.javaClass.simpleName)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256

        /**
         * The key spec of plan 3 and 13.2: per-use authentication and an unlocked phone. Only the biometric daily key is
         * invalidated by a new enrolment (the vault then asks for the passphrase once and seals a new daily key); K_hw
         * survives it, so an enrolment never makes the vault unreadable.
         */
        fun spec(
            alias: String,
            auth: VaultKeyAuth,
            strongBox: Boolean,
        ): KeyGenParameterSpec {
            val types =
                when (auth) {
                    VaultKeyAuth.BiometricOrCredential -> {
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    }

                    VaultKeyAuth.Biometric -> {
                        KeyProperties.AUTH_BIOMETRIC_STRONG
                    }

                    VaultKeyAuth.Credential -> {
                        KeyProperties.AUTH_DEVICE_CREDENTIAL
                    }
                }
            return KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(true)
                // 0: every single operation needs its own authentication (a CryptoObject prompt).
                .setUserAuthenticationParameters(0, types)
                // Enrolling a biometric destroys only the biometric daily key; K_hw and a screen-lock key stay.
                .setInvalidatedByBiometricEnrollment(auth == VaultKeyAuth.Biometric)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        }

        /**
         * `Settings.Global.BOOT_COUNT`, the number of boots of the phone (the daily blob carries it, plan 13.2); null
         * when the setting is not readable.
         */
        fun bootCount(context: Context): Long? =
            try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
            } catch (_: Settings.SettingNotFoundException) {
                null
            }
    }
}

private fun VaultAuthResult.toCrypto(): VaultCrypto =
    when (this) {
        is VaultAuthResult.Authorized -> VaultCrypto.Failed("authorized without a result")
        VaultAuthResult.Cancelled -> VaultCrypto.Cancelled
        VaultAuthResult.LockedOut -> VaultCrypto.LockedOut
        VaultAuthResult.NoCredential -> VaultCrypto.NoCredential
        is VaultAuthResult.Error -> VaultCrypto.Failed("prompt error $code")
    }
