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
        last: UiText? = UiText.Literal("5 min. ago"),
        running: Boolean = false,
    ) = SettingsSample.state(accounts = true).copy(peopleSync = last?.let { PeopleSyncUi(it, running) })

    private fun page(h: SettingsHarness) {
        composeFrame { HostedSettings(h) { ContactsSettingsPage(h.ui, h.common, h.links(), h::on) } }
        advance(700)
    }

    private fun text(name: String): SemanticsNodeInteraction = compose.onAllNodesWithText(name).onFirst()

    private fun top(name: String): Dp = text(name).getUnclippedBoundsInRoot().top

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
        text("5 min. ago").assertIsDisplayed()
        text("Sync Now").assertIsDisplayed()
    }

    @Test fun syncNowAsksForASync() {
        val h = SettingsHarness(state())
        page(h)
        compose.onNodeWithTag(SettingsTags.PeopleSync, useUnmergedTree = true).performClick()
        advance(300)
        assertEquals(listOf<SettingsAction>(SettingsAction.SyncPeopleNow), h.actions)
        // The row stays in place and says what it does now.
        text("Syncing…").assertIsDisplayed()
    }

    @Test fun whileSyncingTheRowTakesNoTaps() {
        val h = SettingsHarness(state(running = true))
        page(h)
        text("Syncing…").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.PeopleSync, useUnmergedTree = true).performClick()
        advance(300)
        assertTrue("no second sync is started", h.actions.isEmpty())
    }

    @Test fun neverSyncedAndFailedAreWorded() {
        page(SettingsHarness(state(UiText.Res(R.string.settings_people_never))))
        text("Not synced yet").assertIsDisplayed()
        page(SettingsHarness(state(UiText.Res(R.string.settings_people_failed, listOf("2 hr. ago")))))
        text("Failed, 2 hr. ago").assertIsDisplayed()
    }
}
