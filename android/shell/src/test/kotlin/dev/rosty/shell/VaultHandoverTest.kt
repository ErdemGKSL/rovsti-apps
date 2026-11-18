package dev.rosty.shell

import android.app.Application
import android.content.Context
import android.content.pm.Signature
import dev.rosty.core.FakeRustCore
import dev.rosty.core.FakeVaultOps
import dev.rosty.core.RustCore
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.ops.VaultOps
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.vault.FakeVaultKeystore
import dev.rosty.platform.vault.VaultKeystore
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaRepository
import dev.rosty.wa.share.SiblingAnswer
import dev.rosty.wa.share.SiblingLinks
import dev.rosty.wa.share.WaLinkHandover
import dev.rosty.wa.share.WaLinkProvider
import dev.rosty.wa.share.WaLinkShare
import dev.rosty.wa.vault.VaultOutcome
import dev.rosty.wa.vault.WaVaultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** A container with the vault's fakes: Rust's half in memory, the Keystore's half without hardware. */
class VaultContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.phone) {
    val fakeRust = FakeRustCore()
    val fakeWa = FakeWaRepository()
    val fakeVault = FakeVaultOps()
    val fakeKeystore = FakeVaultKeystore()

    /** What the siblings answer when this app asks (the receiving side). */
    var siblings: Map<String, SiblingAnswer> = emptyMap()

    override fun createRust(): RustCore = fakeRust

    override fun createWa(): WaRepository = fakeWa

    override fun createSpecialAccess(): SpecialAccess = FakeSpecialAccess()

    override fun startsDevLogAtCreate(): Boolean = false

    override fun createVaultOps(): VaultOps = fakeVault

    override fun createVaultKeystore(): VaultKeystore = fakeKeystore

    override fun createSiblingLinks(): SiblingLinks =
        object : SiblingLinks {
            override suspend fun offers(packageName: String): Boolean = siblings[packageName] is SiblingAnswer.Link

            override suspend fun ask(packageName: String): SiblingAnswer =
                siblings[packageName] ?: SiblingAnswer.NotInstalled
        }
}

class VaultTestApp :
    Application(),
    HasContainer<VaultContainer> {
    override val container: VaultContainer by lazy { VaultContainer(this) }
}

/**
 * The link hand-over between the vaults of two Rosty apps (`plans/wa-vault.md` 13.3): the sender answers only while
 * its vault is unlocked in this process and reads no link file; the receiver keeps the link in memory (Rust's pending
 * session) for its own setup, and no app writes `whatsapp-link.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = VaultTestApp::class)
class VaultHandoverTest {
    private val app get() = RuntimeEnvironment.getApplication() as VaultTestApp
    private val link = VaultLink("https://rosty.erdemdev.tr", "inst1", "rwk_" + "A".repeat(43))

    @Before
    fun setUp() {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).signatures =
            arrayOf(Signature("0a0b0c"))
        Robolectric.buildContentProvider(WaLinkProvider::class.java).create(WaLinkShare.authority(app.packageName))
    }

    private fun linkFile() = File(app.noBackupFilesDir, "whatsapp-link.json")

    private fun query(path: String = WaLinkShare.LINK_PATH) =
        app.contentResolver.query(
            android.net.Uri.parse("content://${WaLinkShare.authority(app.packageName)}/$path"),
            null,
            null,
            null,
            null,
        )!!

    @Test
    fun aLockedSenderHandsNothingOverAndReadsNoFile() {
        val container = app.container
        container.fakeVault.openVault(link)
        container.fakeVault.lockVault()
        runBlocking { container.waVault.refresh() }
        assertTrue(container.waVault.state.value is WaVaultState.Locked)
        query().use { assertEquals(0, it.count) }
        query(WaLinkShare.OFFER_PATH).use { assertEquals(0, it.count) }
        assertTrue("no export while locked", "vault.export_link" !in container.fakeVault.calls)
        assertTrue("no link file is read", container.fakeRust.calls.none { it.op == "wa.link_read" })
    }

    @Test
    fun aColdSenderHandsNothingOver() {
        val container = app.container
        container.fakeVault.openVault(link)
        // Nothing made the vault in this process (a provider that woke a cold process): no answer, no core.
        query().use { assertEquals(0, it.count) }
        assertTrue(container.fakeVault.calls.isEmpty())
        assertTrue(container.fakeRust.calls.isEmpty())
    }

    @Test
    fun anUnlockedSenderHandsItsVaultsLinkOverFromMemory() {
        val container = app.container
        container.fakeVault.openVault(link)
        runBlocking { container.waVault.refresh() }
        assertEquals(WaVaultState.Unlocked, container.waVault.state.value)
        query(WaLinkShare.OFFER_PATH).use { assertEquals(1, it.count) }
        query().use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(StoredLink(link.gatewayUrl, link.instanceId, link.key), WaLinkShare.linkOf(cursor))
        }
        assertTrue("vault.export_link" in container.fakeVault.calls)
        assertTrue("no link file is read", container.fakeRust.calls.none { it.op == "wa.link_read" })
        assertFalse(linkFile().exists())
    }

    @Test
    fun theReceiverKeepsTheLinkForItsSetupAndNoAppWritesALinkFile() {
        val container = app.container
        val given = StoredLink(link.gatewayUrl, link.instanceId, link.key)
        container.siblings = mapOf("dev.rosty.messages" to SiblingAnswer.Link(given))
        runBlocking { container.waVault.refresh() }
        assertEquals(WaVaultState.NoLink, container.waVault.state.value)
        runBlocking {
            assertEquals("dev.rosty.messages", container.waLinkHandover.offer())
            assertEquals(WaLinkHandover.Result.Adopted("dev.rosty.messages"), container.waLinkHandover.take())
        }
        // The link waits in Rust's uncommitted session: the setup screens follow; nothing is on disk.
        assertEquals(WaVaultState.NeedsSetup, container.waVault.state.value)
        assertEquals(listOf("vault.create"), container.fakeVault.calls.filter { it == "vault.create" })
        assertFalse(linkFile().exists())
        assertTrue(container.fakeRust.calls.none { it.op == "wa.link_write" })

        // The setup: the passphrase shown, two groups typed back, sealed and committed.
        runBlocking {
            val offer = (container.waVault.startSetup() as dev.rosty.core.Outcome.Ok).value
            val typed = offer.ask.map { offer.groups[it] }
            assertEquals(VaultOutcome.Done, container.waVault.confirm(offer, typed))
        }
        assertEquals(WaVaultState.Unlocked, container.waVault.state.value)
        assertFalse("no app writes whatsapp-link.json after the setup", linkFile().exists())
        assertTrue(container.fakeRust.calls.none { it.op == "wa.link_write" })
        // Taking again: this app has its vault now.
        runBlocking { assertEquals(WaLinkHandover.Result.AlreadyLinked, container.waLinkHandover.take()) }
    }

    @Test
    fun theLinkScreensLinkFlowGoesIntoTheVaultToo() {
        val container = app.container
        runBlocking { container.waVault.refresh() }
        val settings =
            dev.rosty.wa.vault
                .VaultSettingsRepository(container.wa, container.waVault)
        runBlocking {
            settings.relinkTo(StoredLink(link.gatewayUrl, link.instanceId, link.key))
        }
        assertEquals(WaVaultState.NeedsSetup, container.waVault.state.value)
        assertNull("the repository's own link file is not written", container.fakeWa.linked)
        assertFalse(linkFile().exists())
    }
}
