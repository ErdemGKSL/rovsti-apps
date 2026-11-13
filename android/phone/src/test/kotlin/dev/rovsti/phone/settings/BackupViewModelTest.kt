package dev.rovsti.phone.settings

import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.phone.SpeedDialEntry
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.phone.settings.backup.BackupAction
import dev.rovsti.phone.settings.backup.BackupEffect
import dev.rovsti.phone.settings.backup.BackupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.9b Backup on the REAL core: export, the preview before anything is applied, apply, Undo, a refused file. */
class BackupViewModelTest {
    private val host by lazy { HostRust("rovsti-backup") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() = UnconfinedMain.install()

    @After fun stop() = scope.cancel()

    private suspend fun BackupViewModel.effectNow() = withTimeout(10_000) { effect.first() }

    private suspend fun rigWithData(): Pair<SettingsRig, BackupViewModel> {
        val rig = SettingsRig(host, scope)
        rig.store.load()
        rig.store.updatePrefs {
            it.copy(
                quickReplies = listOf("On my way", "Call you later"),
                speedDial = mapOf(2 to SpeedDialEntry("k-3", "+905553334455", "Deniz Aksoy")),
                settings = it.settings.copy(haptics = HapticsLevel.Off, showFrequent = false),
                favoritesOrder = listOf("k-3", "k-1"),
            )
        }
        return rig to BackupViewModel(rig.store, rig.ops, "0.1.0", clock = { 1_700_000_000_000L })
    }

    @Test fun roundTripRestoresWhatWasBackedUpAfterAPreview() =
        runBlocking {
            val (rig, model) = rigWithData()
            val saved = rig.store.prefs.value
            model.onAction(BackupAction.Export)
            val export = (model.effectNow() as BackupEffect.Export).file
            assertEquals("application/json", export.mime)
            assertEquals(SavedWhat.Backup, export.what)
            assertTrue(export.fileName.startsWith("rovsti-phone-backup-") && export.fileName.endsWith(".json"))

            // The user changes things, then restores the file.
            rig.store.updatePrefs {
                it.copy(
                    quickReplies = emptyList(),
                    speedDial = emptyMap(),
                    settings = it.settings.copy(haptics = HapticsLevel.Normal, showFrequent = true),
                    favoritesOrder = emptyList(),
                )
            }
            val changed = rig.store.prefs.value
            model.onAction(BackupAction.Picked(export.fileName, export.text))
            val pending = model.state.until { it.pending != null }.pending!!
            assertEquals(export.fileName, pending.fileName)
            assertEquals(2, pending.preview.quickReplies)
            assertEquals(1, pending.preview.speedDial)
            assertEquals(2, pending.preview.favorites)
            assertTrue("two settings differ", pending.preview.settingsChanged >= 2)
            assertEquals("nothing is applied before Apply", changed, rig.store.prefs.value)

            model.onAction(BackupAction.Apply)
            val applied = model.effectNow() as BackupEffect.Applied
            assertEquals(changed, applied.previous)
            assertEquals(saved.quickReplies, rig.store.prefs.value.quickReplies)
            assertEquals(saved.speedDial, rig.store.prefs.value.speedDial)
            assertEquals(saved.settings, rig.store.prefs.value.settings)
            assertEquals(saved.favoritesOrder, rig.store.prefs.value.favoritesOrder)
            assertNull(model.state.value.pending)

            model.onAction(BackupAction.Undo(applied.previous))
            model.state.until { true }
            kotlinx.coroutines.delay(100)
            assertEquals(changed, rig.store.prefs.value)
        }

    @Test fun cancelThrowsThePreviewAwayAndChangesNothing() =
        runBlocking {
            val (rig, model) = rigWithData()
            model.onAction(BackupAction.Export)
            val export = (model.effectNow() as BackupEffect.Export).file
            val before = rig.store.prefs.value
            model.onAction(BackupAction.Picked("b.json", export.text))
            assertNotNull(model.state.until { it.pending != null }.pending)
            model.onAction(BackupAction.Cancel)
            assertNull(model.state.until { it.pending == null }.pending)
            assertEquals(before, rig.store.prefs.value)
        }

    @Test fun aFileThatIsNoBackupIsRefusedWithoutAPreview() =
        runBlocking {
            val (rig, model) = rigWithData()
            val before = rig.store.prefs.value
            model.onAction(BackupAction.Picked("notes.txt", "this is not json"))
            assertTrue(model.effectNow() is BackupEffect.Invalid)
            assertNull(model.state.value.pending)
            assertEquals(before, rig.store.prefs.value)
        }
}
