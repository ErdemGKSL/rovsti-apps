package dev.rosty.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.design.transition.TabNavigator
import dev.rosty.platform.vault.VaultCrypto
import dev.rosty.shell.permissions.LocalPermissionController
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.testing.ShotTest
import dev.rosty.wa.vault.SecureWindows
import dev.rosty.wa.vault.VaultOutcome
import dev.rosty.wa.vault.VaultTags
import dev.rosty.wa.vault.WaVaultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * FLAG_SECURE on the Messages window (`plans/wa-vault.md` 6): the whole window while the vault is open (WhatsApp text
 * can show in the merged inbox, search and stars), the WhatsApp tab's vault pages while it is locked, and nothing
 * while there is no WhatsApp at all.
 */
@Config(sdk = [35], application = TestMessagesApp::class)
class MessagesSecureWindowTest : ShotTest() {
    private val container get() = (RuntimeEnvironment.getApplication() as TestMessagesApp).container

    private fun secure(): Boolean = SecureWindows.isSecure(compose.activity.window)

    @Test fun theWindowIsSecureWheneverWhatsAppCanShow() {
        val navigator = TabNavigator(MessagesTabs, MessagesRoots)
        val vault = container.waVault
        runBlocking { vault.refresh() }
        assertEquals(WaVaultState.NoLink, vault.state.value)
        composeFrame {
            CompositionLocalProvider(
                LocalPermissionController provides container.permissions,
                LocalSpecialAccess provides container.specialAccess,
            ) {
                MessagesApp(container = container, navigator = navigator)
            }
        }
        advance(800)
        assertFalse("no WhatsApp: screenshots of SMS stay possible", secure())

        // A plaintext link waits for its vault; the chats show no WhatsApp yet.
        container.fakeVault.legacyLink = VaultLink("https://gw.example", "inst1", "rwk_" + "a".repeat(40))
        runBlocking { vault.refresh() }
        advance(800)
        assertEquals(WaVaultState.NeedsSetup, vault.state.value)
        assertFalse(secure())

        // Set up and open: every page is secure.
        runBlocking {
            val offer = vault.startSetup().getOrNull()!!
            assertEquals(VaultOutcome.Done, vault.confirm(offer, offer.ask.map { offer.groups[it] }))
        }
        advance(800)
        assertEquals(WaVaultState.Unlocked, vault.state.value)
        assertTrue("the merged inbox may show WhatsApp text", secure())

        // Locked: the chats are plain again, the WhatsApp tab's unlock page is secure.
        runBlocking { vault.lock() }
        advance(800)
        assertEquals(WaVaultState.Locked(biometric = true), vault.state.value)
        assertFalse(secure())
        container.fakeKeystore.next = VaultCrypto.Cancelled
        compose.runOnUiThread { navigator.select(WhatsAppTab) }
        advance(800)
        compose.onNodeWithTag(VaultTags.Unlock).assertExists()
        assertTrue(secure())
    }
}
