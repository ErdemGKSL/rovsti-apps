package dev.rosty.shell

import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.wa.vault.SharedWaMigrationMark
import dev.rosty.wa.vault.VaultOutcome
import dev.rosty.wa.vault.WaMigrationResult
import dev.rosty.wa.vault.WaPlaintextMigration
import dev.rosty.wa.vault.WaVaultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * How every app (`ShellContainer`) runs the upgrade migration (`plans/wa-vault.md` 8, round 6): the plaintext link of
 * an earlier build stays until its vault is committed, the wipe follows the first unlock, and a finished migration
 * costs one preference read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = VaultTestApp::class)
class WaMigrationWiringTest {
    private val app get() = RuntimeEnvironment.getApplication() as VaultTestApp
    private val link = VaultLink("https://gw.example", "inst1", "rwk_" + "A".repeat(43))

    private fun wipes(container: VaultContainer) = container.fakeVault.calls.count { it == "vault.wipe_plaintext" }

    @Test
    fun thePlaintextLinkGoesOnlyAfterItsVaultIsCommitted() {
        val container = app.container
        container.fakeVault.legacyLink = link
        runBlocking {
            assertEquals(WaMigrationResult.Waiting, container.migratePlaintext())
            assertEquals(WaVaultState.NeedsSetup, container.waVault.state.value)
            assertEquals(0, wipes(container))

            val vault = container.waVault
            val offer = vault.startSetup().getOrNull()!!
            assertEquals(VaultOutcome.Done, vault.confirm(offer, offer.ask.map { offer.groups[it] }))
            assertEquals(WaVaultState.Unlocked, vault.state.value)
            // The unlock started it in the background; this run or that one wiped, never both.
            val result = container.migratePlaintext()
            assertTrue(result.toString(), result is WaMigrationResult.Done || result == WaMigrationResult.NotDue)
        }
        assertEquals(1, wipes(container))
        assertNull("the plaintext link is gone", container.fakeVault.legacyLink)
        assertTrue(SharedWaMigrationMark(app).done)
    }

    @Test
    fun aLockedVaultWithTheIdentityFileWaitsForTheUnlock() {
        val container = app.container
        container.fakeVault.openVault(link)
        container.fakeVault.lockVault()
        val identity = File(app.noBackupFilesDir, WaPlaintextMigration.IDENTITY_FILE).apply { writeText("{}") }
        runBlocking { assertEquals(WaMigrationResult.Waiting, container.migratePlaintext()) }
        assertEquals(0, wipes(container))
        identity.delete()
        runBlocking { assertTrue(container.migratePlaintext() is WaMigrationResult.Done) }
        assertEquals(1, wipes(container))
    }

    @Test
    fun aFinishedMigrationTouchesNeitherTheVaultNorTheCore() {
        val container = app.container
        SharedWaMigrationMark(app).done = true
        runBlocking { assertEquals(WaMigrationResult.NotDue, container.migratePlaintext()) }
        assertTrue(container.fakeVault.calls.isEmpty())
        assertTrue(container.fakeRust.calls.isEmpty())
        assertFalse(app.getSharedPreferences(SharedWaMigrationMark.NAME, 0).all.isEmpty())
    }
}
