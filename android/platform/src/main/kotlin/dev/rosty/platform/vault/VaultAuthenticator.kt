package dev.rosty.platform.vault

import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.crypto.Cipher
import kotlin.coroutines.resume

/** Shows the system prompt that authorizes one Keystore operation on [Cipher]. */
interface VaultAuthenticator {
    suspend fun authenticate(
        cipher: Cipher,
        auth: VaultKeyAuth,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit,
    ): VaultAuthResult
}

/** What the prompt answered. */
sealed interface VaultAuthResult {
    /** The person authenticated; [cipher] may now do its one operation. */
    class Authorized(
        val cipher: Cipher,
    ) : VaultAuthResult

    data object Cancelled : VaultAuthResult

    data object LockedOut : VaultAuthResult

    /** Nothing enrolled or no screen lock for the allowed authenticators. */
    data object NoCredential : VaultAuthResult

    /** No window to show the prompt in, or another error ([code] of `BiometricPrompt`). */
    data class Error(
        val code: Int,
        val message: String,
    ) : VaultAuthResult
}

/**
 * The window the prompt opens over: the app's activity attaches itself while it exists ([attach], [detach]). A
 * process without a window cannot prompt, so nothing is unlocked there.
 */
class VaultPromptHost {
    @Volatile private var current: WeakReference<FragmentActivity>? = null

    fun attach(activity: FragmentActivity) {
        current = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (current?.get() === activity) current = null
    }

    val activity: FragmentActivity? get() = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
}

/** The `BiometricPrompt` authenticators a key of [auth] allows (plan 3, 13.2). */
fun promptAuthenticators(auth: VaultKeyAuth): Int =
    when (auth) {
        VaultKeyAuth.BiometricOrCredential -> Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL

        VaultKeyAuth.Biometric -> Authenticators.BIOMETRIC_STRONG

        VaultKeyAuth.Credential -> Authenticators.DEVICE_CREDENTIAL

        // K_stay is never prompted for (round 7c).
        VaultKeyAuth.UnlockedDevice -> 0
    }

/** A `BiometricPrompt` error code as a [VaultAuthResult]. */
fun authErrorOf(
    code: Int,
    message: String,
): VaultAuthResult =
    when (code) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_TIMEOUT,
        -> VaultAuthResult.Cancelled

        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> VaultAuthResult.LockedOut

        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        -> VaultAuthResult.NoCredential

        else -> VaultAuthResult.Error(code, message)
    }

/** The real prompt: androidx `BiometricPrompt` with a `CryptoObject`, over [host]'s activity. */
class BiometricVaultAuthenticator(
    private val host: VaultPromptHost,
) : VaultAuthenticator {
    override suspend fun authenticate(
        cipher: Cipher,
        auth: VaultKeyAuth,
        prompt: VaultPrompt,
        onFailedAttempt: () -> Unit,
    ): VaultAuthResult =
        withContext(Dispatchers.Main.immediate) {
            val activity = host.activity ?: return@withContext VaultAuthResult.Error(NO_WINDOW, "no window")
            suspendCancellableCoroutine<VaultAuthResult> { done ->
                val callback =
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val authorized = result.cryptoObject?.cipher ?: cipher
                            if (done.isActive) done.resume(VaultAuthResult.Authorized(authorized))
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (done.isActive) done.resume(authErrorOf(errorCode, errString.toString()))
                        }

                        // A finger or face that did not match; the prompt stays up.
                        override fun onAuthenticationFailed() = onFailedAttempt()
                    }
                val biometricPrompt =
                    BiometricPrompt(activity, activity.mainExecutor, callback)
                val info =
                    BiometricPrompt.PromptInfo
                        .Builder()
                        .setTitle(prompt.title)
                        .setSubtitle(prompt.subtitle)
                        .setAllowedAuthenticators(promptAuthenticators(auth))
                        .setConfirmationRequired(false)
                        .apply {
                            // The screen lock brings its own way out; a biometric-only prompt needs a button.
                            if (auth == VaultKeyAuth.Biometric) setNegativeButtonText(prompt.negative)
                        }.build()
                done.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
                try {
                    biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
                } catch (e: IllegalArgumentException) {
                    if (done.isActive) done.resume(VaultAuthResult.Error(BAD_REQUEST, e.message.orEmpty()))
                }
            }
        }

    private companion object {
        const val NO_WINDOW = -100
        const val BAD_REQUEST = -101
    }
}
