package dev.rosty.phone.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rosty.phone.HostedPhone
import dev.rosty.phone.PhoneHarness
import dev.rosty.phone.PhoneRoute
import dev.rosty.phone.PhoneSample
import dev.rosty.phone.settings.backup.BackupPreviewContent
import dev.rosty.phone.settings.backup.BackupScreen
import dev.rosty.phone.settings.backup.BackupTags
import dev.rosty.phone.settings.data.DataAction
import dev.rosty.phone.settings.data.DataScreen
import dev.rosty.phone.settings.data.DataTags
import dev.rosty.phone.settings.sim.SimChoicesAction
import dev.rosty.phone.settings.sim.SimChoicesScreen
import dev.rosty.phone.settings.sim.SimChoicesTags
import dev.rosty.phone.settings.sim.SimChoicesUi
import dev.rosty.phone.settings.speeddial.SpeedDialAction
import dev.rosty.phone.settings.speeddial.SpeedDialScreen
import dev.rosty.phone.settings.speeddial.SpeedDialTags
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.9b: the stateless Settings pages on the real shell, driven by taps. */
@Config(sdk = [35])
class SettingsScreensTest : ShotTest() {
    private fun show(
        route: PhoneRoute,
        page: @Composable () -> Unit,
    ) {
        composeFrame { HostedPhone(PhoneHarness(), PhoneSample.setup(), push = route, extra = page) }
        advance(500)
    }

    @Test fun speedDialKeyOneIsFixedAndAnEmptyKeyOpensThePicker() {
        val picked = mutableListOf<Int>()
        val actions = mutableListOf<SpeedDialAction>()
        var shared = 0
        show(PhoneRoute.SpeedDialPage) {
            SpeedDialScreen(SettingsFixtures.speedDial(), { actions += it }, { picked += it }, { shared++ })
        }
        compose.onNodeWithTag(SpeedDialTags.row(1)).performClick()
        compose.onNodeWithTag(SpeedDialTags.row(4)).performClick()
        compose.onNodeWithTag(SpeedDialTags.row(3)).performClick()
        assertEquals("key 1 never opens the picker", listOf(4, 3), picked)
        compose.onNodeWithTag(SpeedDialTags.Share).performClick()
        assertEquals(1, shared)
        compose.onNodeWithTag(SpeedDialTags.ClearAll).performClick()
        assertEquals(listOf<SpeedDialAction>(SpeedDialAction.ClearAll), actions)
    }

    @Test fun simChoicesListEachNumberAndForgetAllAsks() {
        val actions = mutableListOf<SimChoicesAction>()
        show(PhoneRoute.SimChoices) { SimChoicesScreen(SettingsFixtures.sims(), { actions += it }) }
        compose.onNodeWithTag(SimChoicesTags.row("+90 555 333 44 55")).assertIsDisplayed()
        compose.onNodeWithTag(SimChoicesTags.ForgetAll).performClick()
        assertEquals(listOf<SimChoicesAction>(SimChoicesAction.ForgetAll), actions)
    }

    @Test fun simChoicesSaySoOnASingleSimPhone() {
        show(PhoneRoute.SimChoices) { SimChoicesScreen(SimChoicesUi(visible = false), {}) }
        compose.onNodeWithTag(SimChoicesTags.Empty).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag(SimChoicesTags.ForgetAll)).fetchSemanticsNodes().size)
    }

    @Test fun dataRowsAskForTheirExportsAndTheChooser() {
        val actions = mutableListOf<DataAction>()
        var deleteAll = 0
        show(PhoneRoute.Data) { DataScreen(SettingsFixtures.data(), { actions += it }, { deleteAll++ }) }
        compose.onNodeWithTag(DataTags.ExportHistory).performClick()
        compose.onNodeWithTag(DataTags.ExportNotes).performClick()
        compose.onNodeWithTag(DataTags.ExportContacts).performClick()
        compose.onNodeWithTag(DataTags.DeleteOlder).performClick()
        compose.onNodeWithTag(DataTags.DeleteAll).performClick()
        assertEquals(
            listOf(DataAction.ExportHistory, DataAction.ExportNotes, DataAction.ExportContacts, DataAction.ChooseOlder),
            actions,
        )
        assertEquals(1, deleteAll)
    }

    @Test fun deleteAllIsOffWhileThereAreNoCalls() {
        var deleteAll = 0
        show(PhoneRoute.Data) {
            DataScreen(SettingsFixtures.data().copy(calls = 0), {}, { deleteAll++ })
        }
        compose.onNodeWithTag(DataTags.DeleteAll).performClick()
        assertEquals(0, deleteAll)
    }

    @Test fun backupRowsAskForExportAndRestore() {
        var export = 0
        var restore = 0
        show(PhoneRoute.Backup) { BackupScreen({ export++ }, { restore++ }) }
        compose.onNodeWithTag(BackupTags.Export).performClick()
        compose.onNodeWithTag(BackupTags.Restore).performClick()
        assertEquals(1 to 1, export to restore)
    }

    @Test fun thePreviewShowsTheCountsAndRestoreAndCancelAnswer() {
        var applied = 0
        var cancelled = 0
        composeFrame {
            androidx.compose.foundation.layout.Column {
                BackupPreviewContent("backup.json", SettingsFixtures.preview(), { applied++ }, { cancelled++ })
            }
        }
        advance(300)
        compose.onNodeWithTag(BackupTags.Apply).performClick()
        compose.onNodeWithTag(BackupTags.Cancel).performClick()
        assertTrue(applied == 1 && cancelled == 1)
    }
}
