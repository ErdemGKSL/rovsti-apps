package dev.rovsti.contacts.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import dev.rovsti.contacts.R
import dev.rovsti.design.text.UiText
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** X.1b: the "WhatsApp Names and Photos" rows of the Contacts settings: when they show, what they say, what they do. */
@Config(sdk = [35], qualifiers = "w411dp-h3600dp")
class ContactsSettingsSyncRowTest : ShotTest() {
    private fun state(
        last: UiText? = UiText.Literal("Synced, 5 min. ago"),
        running: Boolean = false,
    ) = SettingsSample.state(accounts = true).copy(peopleSync = last?.let { PeopleSyncUi(it, running) })

    private fun state(sync: PeopleSyncUi) = SettingsSample.state(accounts = true).copy(peopleSync = sync)

    private fun page(h: SettingsHarness) {
        composeFrame { HostedSettings(h) { ContactsSettingsPage(h.ui, h.common, h.links(), h::on) } }
        advance(700)
    }

    private fun text(name: String): SemanticsNodeInteraction = compose.onAllNodesWithText(name).onFirst()

    private fun top(name: String): Dp = text(name).getUnclippedBoundsInRoot().top

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        advance(300)
    }

    @Test fun theRowsAreHiddenWhileWhatsAppIsNotLinked() {
        page(SettingsHarness(state(last = null)))
        compose.onNodeWithTag(SettingsTags.PeopleSync, useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("WhatsApp Names and Photos").assertCountEquals(0)
    }

    @Test fun theGroupSitsBetweenTheWhatsAppRowAndTheSharedRows() {
        page(SettingsHarness(state()))
        val order = listOf("WhatsApp Names and Photos", "Appearance")
        assertTrue("whatsapp row, then the sync group, then appearance", top("WhatsApp") < top(order[0]))
        assertTrue(top(order[0]) < top(order[1]))
        text("Last Sync").assertIsDisplayed()
        text("Synced, 5 min. ago").assertIsDisplayed()
        text("Sync Now").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.PeopleContinue, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun syncNowSyncsAtOnceWhenNothingIsResumable() {
        val h = SettingsHarness(state())
        page(h)
        tap(SettingsTags.PeopleSync)
        assertEquals(listOf<SettingsAction>(SettingsAction.SyncPeopleNow), h.actions)
        // The row stays in place and is "Stop" now.
        text("Stop").assertIsDisplayed()
    }

    @Test fun whileSyncingTheRowStopsTheRun() {
        val h =
            SettingsHarness(
                state(PeopleSyncUi(UiText.Literal("Checking numbers 30/120"), running = true, progress = 0.25f)),
            )
        page(h)
        text("Stop").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.PeopleProgress, useUnmergedTree = true).assertExists()
        tap(SettingsTags.PeopleSync)
        assertEquals(listOf<SettingsAction>(SettingsAction.StopPeople), h.actions)
    }

    @Test fun aResumableRunOffersContinueAndSyncNowAsksFirst() {
        val sync = PeopleSyncUi(UiText.Literal("Stopped at 30/120"), canContinue = true, confirmRestart = true)
        val h = SettingsHarness(state(sync))
        page(h)
        text("Continue Where It Left Off").assertIsDisplayed()
        tap(SettingsTags.PeopleSync)
        assertTrue("nothing yet: the alert asks", h.actions.isEmpty())
        text("Start Over?").assertIsDisplayed()
        text("Start Over").performClick()
        advance(500)
        assertEquals(listOf<SettingsAction>(SettingsAction.SyncPeopleNow), h.actions)
    }

    @Test fun cancellingTheAlertKeepsTheRun() {
        val sync = PeopleSyncUi(UiText.Literal("Stopped at 30/120"), canContinue = true, confirmRestart = true)
        val h = SettingsHarness(state(sync))
        page(h)
        tap(SettingsTags.PeopleSync)
        text("Cancel").performClick()
        advance(500)
        assertTrue(h.actions.isEmpty())
        tap(SettingsTags.PeopleContinue)
        assertEquals(listOf<SettingsAction>(SettingsAction.ContinuePeople), h.actions)
    }

    @Test fun duringWhatsappsLimitBothButtonsWait() {
        val sync =
            PeopleSyncUi(
                UiText.Literal("WhatsApp limit reached. Continues in 12 min"),
                canContinue = true,
                continueEnabled = false,
                syncEnabled = false,
                confirmRestart = true,
            )
        val h = SettingsHarness(state(sync))
        page(h)
        tap(SettingsTags.PeopleContinue)
        tap(SettingsTags.PeopleSync)
        assertTrue("no request during the penalty", h.actions.isEmpty())
    }

    @Test fun neverSyncedAndFailedAreWorded() {
        page(SettingsHarness(state(UiText.Res(R.string.settings_people_never))))
        text("Not synced yet").assertIsDisplayed()
        page(SettingsHarness(state(UiText.Res(R.string.settings_people_failed_auth))))
        text("Failed: the gateway rejected the connect key").assertIsDisplayed()
    }
}
