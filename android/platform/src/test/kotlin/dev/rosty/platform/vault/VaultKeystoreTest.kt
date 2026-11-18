package dev.rosty.platform.vault

import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Keystore boundary of the vault (plan 3, 13.2): key specs, aliases, framing, prompt choices, and the fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultKeystoreTest {
    @Test fun theHardwareKeyNeedsABiometricOrTheScreenLockForEveryUse() {
        val spec = AndroidVaultKeystore.spec("rosty.wa.k.a", VaultKeyAuth.BiometricOrCredential, strongBox = true)
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            spec.userAuthenticationType,
        )
        // A new enrolment must not make the vault unreadable: it only drops the daily key.
        assertFalse(spec.isInvalidatedByBiometricEnrollment)
        assertTrue(spec.isUnlockedDeviceRequired)
        assertTrue(spec.isStrongBoxBacked)
        assertEquals(256, spec.keySize)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        assertTrue(spec.isRandomizedEncryptionRequired)
    }

    @Test fun theDailyKeyTakesOnlyAStrongBiometricWhereOneIsEnrolled() {
        val spec = AndroidVaultKeystore.spec("rosty.wa.d.b.a", VaultKeyAuth.Biometric, strongBox = false)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertFalse(spec.isStrongBoxBacked)
        val pin = AndroidVaultKeystore.spec("rosty.wa.d.c.a", VaultKeyAuth.Credential, strongBox = false)
        assertEquals(KeyProperties.AUTH_DEVICE_CREDENTIAL, pin.userAuthenticationType)
        assertFalse(pin.isInvalidatedByBiometricEnrollment)
        assertTrue(pin.isUnlockedDeviceRequired)
    }

    @Test fun aliasesNameTheirKindAndAuth() {
        val strong = VaultDevice(secureLockScreen = true, strongBiometric = true, strongBox = false)
        val pinOnly = strong.copy(strongBiometric = false)
        assertEquals(VaultKeyAuth.Biometric, VaultAlias.dailyAuth(strong))
        assertEquals(VaultKeyAuth.Credential, VaultAlias.dailyAuth(pinOnly))
        val daily = VaultAlias.daily(VaultKeyAuth.Credential, "x")
        assertEquals(VaultKeyKind.Daily, VaultAlias.kindOf(daily))
        assertEquals(VaultKeyAuth.Credential, VaultAlias.authOf(daily))
        assertEquals(VaultKeyAuth.Biometric, VaultAlias.authOf(VaultAlias.daily(VaultKeyAuth.Biometric, "x")))
        assertEquals(VaultKeyKind.Hardware, VaultAlias.kindOf(VaultAlias.hardware()))
        assertEquals(VaultKeyAuth.BiometricOrCredential, VaultAlias.authOf(VaultAlias.hardware()))
        // The fixtures' aliases.
        assertEquals(VaultKeyKind.Hardware, VaultAlias.kindOf("rosty.wa.k1"))
        assertEquals(VaultKeyKind.Daily, VaultAlias.kindOf("rosty.wa.d1"))
        assertNull(VaultAlias.kindOf("other"))
        assertTrue(VaultAlias.hardware() != VaultAlias.hardware())
    }

    @Test fun aSealedBlobIsVersionNonceAndCiphertext() {
        val iv = ByteArray(12) { it.toByte() }
        val body = ByteArray(40) { (it + 100).toByte() }
        val framed = VaultSealed.frame(iv, body)
        val (readIv, readBody) = VaultSealed.unframe(framed)!!
        assertArrayEquals(iv, readIv)
        assertArrayEquals(body, readBody)
        assertNull(VaultSealed.unframe("not base64!"))
        assertNull(
            VaultSealed.unframe(
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ByteArray(10)),
            ),
        )
        val other =
            java.util.Base64
                .getDecoder()
                .decode(framed)
                .also { it[0] = 2 }
        assertNull(
            VaultSealed.unframe(
                java.util.Base64
                    .getEncoder()
                    .encodeToString(other),
            ),
        )
    }

    @Test fun thePromptAllowsWhatTheKeyAllows() {
        assertEquals(
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            promptAuthenticators(VaultKeyAuth.BiometricOrCredential),
        )
        assertEquals(Authenticators.BIOMETRIC_STRONG, promptAuthenticators(VaultKeyAuth.Biometric))
        assertEquals(Authenticators.DEVICE_CREDENTIAL, promptAuthenticators(VaultKeyAuth.Credential))
    }

    @Test fun promptErrorsAreSorted() {
        assertEquals(VaultAuthResult.Cancelled, authErrorOf(BiometricPrompt.ERROR_USER_CANCELED, ""))
        assertEquals(VaultAuthResult.Cancelled, authErrorOf(BiometricPrompt.ERROR_NEGATIVE_BUTTON, ""))
        assertEquals(VaultAuthResult.LockedOut, authErrorOf(BiometricPrompt.ERROR_LOCKOUT, ""))
        assertEquals(VaultAuthResult.LockedOut, authErrorOf(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, ""))
        assertEquals(VaultAuthResult.NoCredential, authErrorOf(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, ""))
        assertTrue(authErrorOf(BiometricPrompt.ERROR_VENDOR, "x") is VaultAuthResult.Error)
    }

    @Test fun theFakeSealsPerAliasAndAnswersWhatItIsTold() =
        runTest {
            val keystore = FakeVaultKeystore()
            val prompt = VaultPrompt("Unlock", negative = "Cancel")
            val alias = keystore.newAlias(VaultKeyKind.Hardware)
            val sealed = (keystore.seal(alias, "abc", prompt) as VaultCrypto.Done).b64
            assertEquals(VaultCrypto.Done("abc"), keystore.open(alias, sealed, prompt))
            val other = keystore.newAlias(VaultKeyKind.Hardware)
            keystore.seal(other, "x", prompt)
            assertTrue(keystore.open(other, sealed, prompt) is VaultCrypto.Failed)
            keystore.next = VaultCrypto.Cancelled
            assertEquals(VaultCrypto.Cancelled, keystore.open(alias, sealed, prompt))
            keystore.delete(alias)
            assertEquals(VaultCrypto.Invalidated, keystore.open(alias, sealed, prompt))
            var failures = 0
            val y = (keystore.seal(other, "y", prompt) as VaultCrypto.Done).b64
            keystore.failedAttempts = 2
            keystore.open(other, y, prompt) { failures++ }
            assertEquals(2, failures)
        }
}
