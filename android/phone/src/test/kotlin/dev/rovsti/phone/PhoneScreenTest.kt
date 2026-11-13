package dev.rovsti.phone

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.rovsti.core.model.phone.ItemState
import dev.rovsti.core.model.phone.SetupItem
import dev.rovsti.phone.setup.SetupAct
import dev.rovsti.phone.setup.SetupAction
import dev.rovsti.phone.setup.SetupSheetScreen
import dev.rovsti.phone.setup.SetupTags
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.1 screens as a person meets them: the sheet's buttons, the notice line, the toolbar, the version row. */
@Config(sdk = [35])
class PhoneScreenTest : ShotTest() {
    @Test fun theSetupSheetShowsOneCardPerRowWithItsButtons() =
        screenTest {
            val setup = PhoneSample.setup()
            composeFrame { SetupSheetScreen(setup, onAction = {}) }
            for (row in setup.rows) compose.onNodeWithTag(SetupTags.row(row.item)).assertExists()
            compose.onNodeWithText("Finish setup").assertExists()
            compose.onNodeWithText("2 of 5").assertExists()
            compose.onNodeWithTag(SetupTags.button(SetupItem.DefaultPhoneApp, SetupAct.RequestRole)).assertExists()
            compose.onNodeWithTag(SetupTags.button(SetupItem.DefaultPhoneApp, SetupAct.AppInfo)).assertExists()
            compose.onNodeWithTag(SetupTags.button(SetupItem.PhoneAndCallLog, SetupAct.Allow)).assertDoesNotExist()
        }

    @Test fun aButtonSendsItsRowAndAct() =
        screenTest {
            val setup = PhoneSample.setup()
            val seen = mutableListOf<SetupAction>()
            composeFrame { SetupSheetScreen(setup, onAction = { seen += it }) }
            compose.onNodeWithTag(SetupTags.button(SetupItem.Contacts, SetupAct.Allow)).performClick()
            compose
                .onNodeWithTag(
                    SetupTags.button(SetupItem.FullScreenNotifications, SetupAct.FullScreen),
                ).performClick()
            val presses = seen.filterIsInstance<SetupAction.Press>()
            assertEquals(listOf(SetupItem.Contacts, SetupItem.FullScreenNotifications), presses.map { it.item })
            assertEquals(listOf(SetupAct.Allow, SetupAct.FullScreen), presses.map { it.button.act })
        }

    @Test fun aCompleteChecklistSaysYoureAllSet() =
        screenTest {
            composeFrame { SetupSheetScreen(PhoneSample.setupComplete(), onAction = {}) }
            compose.onNodeWithText("You're all set").assertExists()
            compose.onNodeWithText("5 of 5").assertExists()
        }

    @Test fun aLostRowReplacesTheTitle() =
        screenTest {
            composeFrame {
                SetupSheetScreen(
                    PhoneSample.setup(changedTitle = "Something changed: Notifications"),
                    onAction = {},
                )
            }
            compose.onNodeWithText("Something changed: Notifications").assertExists()
            compose.onNodeWithText("Finish setup").assertDoesNotExist()
        }

    @Test fun everyRowIsOneSpeakableNodeWithItsState() =
        screenTest {
            val setup = PhoneSample.setup()
            composeFrame { SetupSheetScreen(setup, onAction = {}) }
            val done = setup.rows.first { it.state == ItemState.Done }
            compose.onNodeWithTag(SetupTags.row(done.item)).assertContentDescriptionEquals("${done.title}, done")
        }

    @Test fun theNoticeLineOpensTheSheetAndGoesWhenTheRoleIsHeld() =
        screenTest {
            val h = PhoneHarness()
            composeFrame { HostedPhone(h, PhoneSample.setup(roleHeld = false)) }
            advance(400)
            compose.onNodeWithTag(PhoneTags.Notice).assertIsDisplayed()
            compose.onNodeWithText("Rovsti is not your phone app").performClick()
            assertEquals(listOf("setup"), h.opened)

            val held = PhoneHarness()
            composeFrame { HostedPhone(held, PhoneSample.setup(roleHeld = true)) }
            advance(400)
            compose.onNodeWithTag(PhoneTags.Notice).assertDoesNotExist()
        }

    @Test fun theNoticeLineSitsOnRecentsOnly() =
        screenTest {
            val h = PhoneHarness()
            composeFrame { HostedPhone(h, PhoneSample.setup(), tab = FavoritesTab) }
            advance(400)
            compose.onNodeWithTag(PhoneTags.Notice).assertDoesNotExist()
        }

    @Test fun theRootsHaveTheGearAndTheShieldWhileSetupIsOpen() =
        screenTest {
            val h = PhoneHarness()
            composeFrame { HostedPhone(h, PhoneSample.setup()) }
            advance(400)
            compose.onNodeWithContentDescription("Setup").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            assertEquals(listOf("setup", "settings"), h.opened)
        }

    @Test fun onceSetupIsCompleteOnlyTheGearIsLeft() =
        screenTest {
            val h = PhoneHarness()
            composeFrame { HostedPhone(h, PhoneSample.setupComplete()) }
            advance(400)
            compose.onNodeWithContentDescription("Setup").assertDoesNotExist()
            compose.onNodeWithContentDescription("Settings").assertExists()
        }

    @Test fun theKeypadHasTheGearInTheCornerAndNoLargeTitle() =
        screenTest {
            val h = PhoneHarness()
            composeFrame { HostedPhone(h, PhoneSample.setup(roleHeld = true), tab = KeypadTab) }
            advance(400)
            compose.onNodeWithTag(PhoneTags.SettingsButton).assertIsDisplayed()
            compose.onNodeWithTag(PhoneTags.SettingsButton).performClick()
            assertEquals(listOf("settings"), h.opened)
        }

    @Test fun theVersionRowCountsTaps() =
        screenTest {
            val h = PhoneHarness()
            composeFrame { HostedPhone(h, PhoneSample.setup(), push = PhoneRoute.Settings) }
            advance(600)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag(PhoneTags.VersionRow, useUnmergedTree = true).performScrollTo()
            compose.mainClock.autoAdvance = false
            repeat(3) { compose.onNodeWithTag(PhoneTags.VersionRow, useUnmergedTree = true).performClick() }
            assertEquals(3, h.taps)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag(PhoneTags.SetupRow, useUnmergedTree = true).performScrollTo()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag(PhoneTags.SetupRow, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(PhoneTags.WhatsAppRow, useUnmergedTree = true).performClick()
            assertTrue(h.opened.containsAll(listOf("setup", "whatsapp")))
        }

    @Test fun diagnosticsListsEverySectionAndCopyAllWorks() =
        screenTest {
            val h = PhoneHarness()
            composeFrame { HostedPhone(h, PhoneSample.setup(), push = PhoneRoute.Diagnostics) }
            advance(600)
            compose.onAllNodesWithTag(dev.rovsti.phone.diagnostics.DiagnosticsTags.Page).assertCountEquals(1)
            compose.onNodeWithText("Calls rejected by the block guard").assertExists()
            compose.onNodeWithTag(dev.rovsti.phone.diagnostics.DiagnosticsTags.CopyAll).performClick()
            assertEquals(listOf("copy"), h.opened)
        }
}
