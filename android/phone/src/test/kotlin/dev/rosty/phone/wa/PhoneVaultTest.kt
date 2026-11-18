package dev.rosty.phone.wa

import android.app.Notification
import android.app.NotificationManager
import dev.rosty.core.FakeRustCore
import dev.rosty.core.FakeVaultOps
import dev.rosty.core.RustCore
import dev.rosty.core.ops.VaultOps
import dev.rosty.phone.PhoneContainer
import dev.rosty.platform.vault.FakeVaultKeystore
import dev.rosty.platform.vault.VaultKeystore
import dev.rosty.platform.wa.WaIncoming
import dev.rosty.platform.wa.WaPush
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Phone on fakes with its WhatsApp vault in memory. */
private class VaultPhoneContainer : PhoneContainer(RuntimeEnvironment.getApplication()) {
    val fakeRust = FakeRustCore()
    val fakeVault = FakeVaultOps()
    val fakeWa = FakeWaRepository()
    var gatewayAsks = 0

    init {
        fakeWa.gatewayOpener = {
            gatewayAsks += 1
            null
        }
    }

    override fun createRust(): RustCore = fakeRust

    override fun createWa(): WaRepository = fakeWa

    override fun createVaultOps(): VaultOps = fakeVault

    override fun createVaultKeystore(): VaultKeystore = FakeVaultKeystore()

    override fun startsDevLogAtCreate(): Boolean = false

    override suspend fun awaitRustReady() = Unit
}

/**
 * Phone's WhatsApp vault (`plans/wa-vault.md` 13.3, round 7b): a call that rings while it is locked shows from the push
 * alone, named only as the push allows, on the locked-call page; nothing reaches the gateway while locked; the LID table lives in the vault cache only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneVaultTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun aCallWhileLockedShowsFromThePushAloneAndNamesNobody() {
        // The app (`RostyPhoneApp`) installed its lock: a fresh process is locked, its vault was never opened.
        assertTrue(WaPush.locked())
        WaPush.onMessage(
            app,
            mapOf(
                "kind" to "call",
                "call_id" to "phone-locked-1",
                "sender" to "100000000001@s.whatsapp.net",
                "name" to "Ayse",
            ),
        )
        val manager = app.getSystemService(NotificationManager::class.java)
        val shown =
            manager.activeNotifications
                .first { it.id == WaIncoming.notificationId("phone-locked-1") }
                .notification
        val person = shown.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        assertEquals("WhatsApp call", person?.name.toString())
        val words = shown.extras.toString()
        assertFalse(words, "Ayse" in words || "100000000001" in words)
        // Round 7b: the ring opens the locked-call page over the lock screen (caller, Answer, Decline only), never the
        // app's pages; an unsealed push is named by a device contact only, so here by nobody.
        val full = shadowOf(shown.fullScreenIntent)
        assertEquals(WaLockedCallActivity::class.java.name, full.savedIntent.component?.className)
        assertEquals("WhatsApp call", full.savedIntent.getStringExtra(WaIncoming.EXTRA_TITLE))
        WaIncoming.cancel(app, "phone-locked-1")
    }

    @Test
    fun aCallActionNamesItsCallForTheAnswerTiming() {
        assertEquals("c1", dev.rosty.phone.waCallIdOf("""{"call_id":"c1","action":"answer"}"""))
        assertNull(dev.rosty.phone.waCallIdOf("not json"))
        assertNull(dev.rosty.phone.waCallIdOf("""{"action":"answer"}"""))
    }

    @Test
    fun lockedPhoneMakesNoRequestForThePeopleSync() =
        runBlocking {
            val c = VaultPhoneContainer()
            c.fakeVault.openVault()
            c.fakeVault.lockVault()
            assertNull("the worker gets no sync while locked", c.standaloneSync())
            assertEquals(0, c.gatewayAsks)
            assertTrue("no link file is read", c.fakeRust.calls.none { it.op == "wa.link_read" })
        }

    @Test
    fun theIdentityTableAsksForTheVaultCache() =
        runBlocking {
            val c = VaultPhoneContainer()
            c.fakeVault.openVault()
            c.waIdentity.open("inst1")
            val asked = c.fakeRust.calls.filter { it.op.startsWith("wa_idbook.") }
            assertTrue(asked.isNotEmpty())
            for (call in asked) assertEquals(call.op, JsonPrimitive(true), call.request.jsonObject["vault"])
        }

    @Test
    fun lockingForgetsWhatPhoneHeldInMemory() =
        runBlocking {
            val c = VaultPhoneContainer()
            c.fakeVault.openVault()
            c.waVault.refresh()
            c.waVault.lock()
            assertTrue(
                c.waIdentity.numbers.value
                    .isEmpty(),
            )
            assertTrue("vault.lock" in c.fakeVault.calls)
        }
}
