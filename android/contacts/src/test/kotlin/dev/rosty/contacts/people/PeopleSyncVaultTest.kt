package dev.rosty.contacts.people

import android.content.Context
import dev.rosty.contacts.ContactsContainer
import dev.rosty.core.FakeRustCore
import dev.rosty.core.FakeVaultOps
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.ops.VaultOps
import dev.rosty.platform.calllog.CallLogRepository
import dev.rosty.platform.calllog.FakeCallLogRepository
import dev.rosty.platform.contacts.ContactsRepository
import dev.rosty.platform.contacts.FakeContactsRepository
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.vault.FakeVaultKeystore
import dev.rosty.platform.vault.VaultKeystore
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Contacts on fakes with its WhatsApp vault in memory. */
private class VaultContactsContainer(
    context: Context,
) : ContactsContainer(context) {
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

    override fun createContacts(): ContactsRepository = FakeContactsRepository()

    override fun createCallLog(): CallLogRepository = FakeCallLogRepository()

    override fun createSpecialAccess(): SpecialAccess = FakeSpecialAccess()

    override fun createVaultOps(): VaultOps = fakeVault

    override fun createVaultKeystore(): VaultKeystore = FakeVaultKeystore()

    override fun startsDevLogAtCreate(): Boolean = false
}

/**
 * Contacts' people sync and its WhatsApp vault (`plans/wa-vault.md` 13.3): a run that finds the vault locked (the
 * 12-hourly work, its continuation, the app in front) makes no request and is deferred to the next unlock, which runs
 * the foreground trigger. The sync's gateway address comes from the vault, never from `whatsapp-link.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeopleSyncVaultTest {
    private fun container() = VaultContactsContainer(RuntimeEnvironment.getApplication())

    @Test
    fun aRunWhileLockedHasNoSyncToRunAndAsksNothing() =
        runBlocking {
            val c = container()
            c.fakeVault.openVault()
            c.fakeVault.lockVault()
            assertNull("the worker gets no sync while locked", c.peopleSyncWhenUnlocked())
            assertEquals(0, c.gatewayAsks)
            assertTrue("no people op ran", c.fakeRust.calls.none { it.op.startsWith("people") })
            assertTrue("no link file is read", c.fakeRust.calls.none { it.op == "wa.link_read" })
        }

    @Test
    fun theUnlockedVaultHandsTheSyncOver() =
        runBlocking {
            val c = container()
            c.fakeVault.openVault()
            assertSame(c.peopleSync, c.peopleSyncWhenUnlocked())
        }

    @Test
    fun theSyncsGatewayAddressComesFromTheVault() =
        runBlocking {
            val c = container()
            c.fakeVault.openVault()
            val link = (c.waLinkSource.read() as Outcome.Ok).value
            assertEquals("https://rosty.erdemdev.tr", link?.gatewayUrl)
            assertEquals("the key stays in the vault", "", link?.key)
            c.fakeVault.lockVault()
            assertNull((c.waLinkSource.read() as Outcome.Ok).value)
            assertTrue(c.fakeRust.calls.none { it.op == "wa.link_read" })
        }
}
