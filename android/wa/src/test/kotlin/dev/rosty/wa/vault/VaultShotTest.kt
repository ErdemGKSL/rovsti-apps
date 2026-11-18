package dev.rosty.wa.vault

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The vault screens (plan 4, 5, 13.2), layout only: setup (shown, then two groups typed back), unlock (biometric,
 * passphrase), a new passphrase, "Forgot passphrase" and the broken vault; light, dark and Turkish. Also: every
 * vault page keeps the window secure, and the passphrase field asks the keyboard not to learn.
 */
@Config(sdk = [35])
class VaultShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val groups = listOf("7KQ2", "M9XD", "4TPR", "H1ZB", "C8WN", "0FJ5", "R3VE")

    private fun snap(
        name: String,
        modes: List<Boolean> = listOf(false, true),
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        for (dark in modes) {
            composeFrame(dark = dark, fontScale = fontScale, content = content)
            advance(300)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Composable
    private fun Setup(
        step: SetupStep,
        change: Boolean = false,
        problem: VaultProblem? = null,
        typed: List<TextFieldState> = listOf(TextFieldState(), TextFieldState()),
    ) = VaultSetupScreen(
        groups = groups,
        ask = listOf(2, 5),
        step = step,
        change = change,
        busy = false,
        problem = problem,
        typed = typed,
        onCopy = {},
        onSaved = {},
        onShowAgain = {},
        onConfirm = {},
        onCancel = if (change) ({}) else null,
    )

    @Composable
    private fun Unlock(
        state: WaVaultState.Locked,
        problem: VaultProblem? = null,
        entry: PassphraseEntry = PassphraseEntry(),
    ) = VaultUnlockScreen(
        title = "WhatsApp",
        state = state,
        usePassphrase = false,
        busy = false,
        problem = problem,
        entry = entry,
        onBiometric = {},
        onUsePassphrase = {},
        onUnlock = {},
        onForgot = {},
    )

    @Test fun setupShowsThePassphraseOnce() = snap("vault-setup") { Setup(SetupStep.Show) }

    @Test fun setupLargeFont() =
        snap(
            "vault-setup-fs2",
            modes = listOf(false),
            fontScale = 2f,
        ) { Setup(SetupStep.Show) }

    @Test fun setupAsksForTwoGroups() =
        snap("vault-setup-confirm") {
            Setup(
                SetupStep.Confirm,
                problem = VaultProblem.GroupsDiffer,
                typed = listOf(TextFieldState("4TPR"), TextFieldState("0FJ")),
            )
        }

    @Test fun unlockWithTheBiometric() =
        snap(
            "vault-unlock-biometric",
        ) { Unlock(WaVaultState.Locked(biometric = true)) }

    @Test fun unlockWithThePassphrase() =
        snap("vault-unlock-passphrase") {
            Unlock(WaVaultState.Locked(false, PassphraseReason.Rebooted), problem = VaultProblem.WrongPassphrase)
        }

    @Test fun aNewPassphrase() = snap("vault-change") { Setup(SetupStep.Show, change = true) }

    @Test fun forgotPassphrase() =
        snap("vault-forgot") {
            VaultForgotScreen(broken = false, busy = false, onReset = {}, onCancel = {})
        }

    @Test fun aBrokenVault() = snap("vault-broken") { VaultForgotScreen(broken = true, busy = false, onReset = {}) }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun setupTurkish() = snap("vault-setup-tr", modes = listOf(false)) { Setup(SetupStep.Show) }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun unlockTurkish() =
        snap("vault-unlock-passphrase-tr", modes = listOf(false)) {
            Unlock(WaVaultState.Locked(false, PassphraseReason.Idle))
        }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun forgotTurkish() =
        snap("vault-forgot-tr", modes = listOf(false)) {
            VaultForgotScreen(false, false, {}, onCancel = {})
        }

    @Test
    @Config(sdk = [35], qualifiers = "+tr")
    fun settingsTurkish() =
        snap("vault-settings-tr", modes = listOf(false)) {
            VaultSettingsSection(60_000L, onLockNow = {}, onAutoLock = {}, onChange = {})
        }

    @Test fun settingsSection() =
        snap("vault-settings") { VaultSettingsSection(60_000L, onLockNow = {}, onAutoLock = {}, onChange = {}) }

    // ---- FLAG_SECURE (plan 6): every vault page, and the gate's content while the vault is open

    private fun secure(): Boolean = SecureWindows.isSecure(compose.activity.window)

    @Test fun everyVaultPageIsSecure() {
        composeFrame { Setup(SetupStep.Show) }
        assertTrue("setup", secure())
        composeFrame { Unlock(WaVaultState.Locked(biometric = true)) }
        assertTrue("unlock", secure())
        composeFrame { VaultForgotScreen(broken = true, busy = false, onReset = {}) }
        assertTrue("forgot", secure())
        composeFrame {
            androidx.compose.foundation.layout
                .Box {}
        }
        assertFalse("released when the page goes", secure())
    }

    @Test fun theGateKeepsUnlockedContentSecureAndTheLinkFlowNot() {
        val flow = VaultFlow(vaultForGate(), kotlinx.coroutines.MainScope()) { true }
        composeFrame {
            WaVaultGate(
                WaVaultState.Unlocked,
                flow,
                "WhatsApp",
            ) {
                androidx.compose.foundation.layout
                    .Box {}
            }
        }
        assertTrue("unlocked content", secure())
        composeFrame {
            WaVaultGate(
                WaVaultState.NoLink,
                flow,
                "WhatsApp",
            ) {
                androidx.compose.foundation.layout
                    .Box {}
            }
        }
        assertFalse("the link flow shows no WhatsApp content", secure())
        composeFrame {
            WaVaultGate(
                WaVaultState.Broken,
                flow,
                "WhatsApp",
            ) {
                androidx.compose.foundation.layout
                    .Box {}
            }
        }
        assertTrue("broken", secure())
    }

    @Test fun thePassphraseFieldAsksTheKeyboardNotToLearn() {
        val entry = PassphraseEntry()
        composeFrame { Unlock(WaVaultState.Locked(false, null), entry = entry) }
        compose.onNodeWithTag(VaultTags.PassphraseField).assertExists()
        val field = findEditText(compose.activity.window.decorView)
        assertNotNull(field)
        val options = field!!.imeOptions
        assertTrue(options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(EditorInfo.IME_ACTION_DONE, options and EditorInfo.IME_MASK_ACTION)
        assertEquals(PASSPHRASE_INPUT_TYPE, field.inputType)
        assertTrue(View.AUTOFILL_HINT_PASSWORD in field.autofillHints.orEmpty())
        compose.runOnUiThread { field.setText("ABCD-EFGH") }
        advance(100)
        assertEquals("ABCD-EFGH", entry.text)
        compose.runOnUiThread { entry.clear() }
        assertEquals("", field.text.toString())
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findEditText(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun vaultForGate(): WaVault =
        WaVault(
            ops = dev.rosty.core.FakeVaultOps(),
            keystore =
                dev.rosty.platform.vault
                    .FakeVaultKeystore(),
            prefs = MemoryWaVaultPrefs(),
            env = WaVaultEnv({ 1L }, { 0L }),
            prompts =
                dev.rosty.platform.vault
                    .VaultPrompt("t", negative = "n")
                    .let { VaultPrompts(it, it, it, it) },
            onLock = {},
            onUnlocked = {},
        )
}
