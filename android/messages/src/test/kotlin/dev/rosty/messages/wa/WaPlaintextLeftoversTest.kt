package dev.rosty.messages.wa

import dev.rosty.messages.TestMessagesApp
import dev.rosty.messages.spam.prefsWith
import dev.rosty.wa.vault.WaMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Messages' part of the upgrade migration (`plans/wa-vault.md` round 6): the WhatsApp drafts and mutes an earlier
 * build kept in `drafts.json` and `messages-prefs.json` go before Rust's wipe; the SMS ones and every other setting
 * stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestMessagesApp::class)
class WaPlaintextLeftoversTest {
    private val container get() = (RuntimeEnvironment.getApplication() as TestMessagesApp).container

    @Test fun theWhatsAppDraftsAndMutesGoAndTheSmsOnesStay() {
        val wa = "wa:100000000001@s.whatsapp.net"
        val prefs =
            prefsWith().copy(
                muted = listOf(wa, "sms:7", "wa:120000000000000001@g.us"),
                muteUntil = mapOf(wa to 1_900_000_000_000L, "sms:7" to 1_900_000_000_000L),
                quickReplies = listOf("On my way"),
            )
        val core =
            RekeyPrefsCore(
                prefs = prefs,
                drafts = mapOf(wa to "see you at six", "sms:7" to "ok"),
                core = container.fakeRust,
            )

        val result = runBlocking { container.migratePlaintext() }

        assertTrue(result.toString(), result is WaMigrationResult.Done)
        assertEquals(mapOf("sms:7" to "ok"), core.drafts)
        assertEquals(listOf("sms:7"), core.prefs.muted)
        assertEquals(mapOf("sms:7" to 1_900_000_000_000L), core.prefs.muteUntil)
        assertEquals(prefs.copy(muted = listOf("sms:7"), muteUntil = mapOf("sms:7" to 1_900_000_000_000L)), core.prefs)
        assertTrue("vault.wipe_plaintext" in container.fakeVault.calls)
        assertEquals(WaMigrationResult.NotDue, runBlocking { container.migratePlaintext() })
    }

    @Test fun anUnreadablePrefsFileIsLeftAloneAndTriedAgain() {
        // No `messages.prefs_load` answer: the store cannot read the file and never overwrites it.
        assertEquals(WaMigrationResult.Incomplete, runBlocking { container.migratePlaintext() })
        assertTrue(container.fakeRust.calls.none { it.op == "messages.prefs_save" })
        RekeyPrefsCore(prefs = prefsWith(), core = container.fakeRust)
        // The store remembers the failed read for the process (it stays read-only); the next start reads it again.
        assertEquals(WaMigrationResult.Incomplete, runBlocking { container.migratePlaintext() })
    }
}
