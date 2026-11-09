package dev.rovsti.contacts.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTags
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.15 on the real shell (a tall window shows the whole page at once): the groups, their order, and what each row does. */
@Config(sdk = [35], qualifiers = "w411dp-h3600dp")
class SettingsScreenTest : ShotTest() {
    private fun root(h: SettingsHarness) {
        composeFrame { HostedSettings(h) { ContactsSettingsPage(h.ui, h.common, h.links(), h::on) } }
        advance(700)
    }

    private fun text(name: String): SemanticsNodeInteraction = compose.onAllNodesWithText(name).onFirst()

    private fun top(name: String): Dp = text(name).getUnclippedBoundsInRoot().top

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        advance(300)
    }

    @Test fun theGroupsComeInTheAgreedOrder() {
        root(SettingsHarness(SettingsSample.state(accounts = true)))
        val order =
            listOf(
                "Display",
                "New Contacts",
                "Summary",
                "Birthdays",
                "Contacts File",
                "Tools",
                "WhatsApp",
                "Appearance",
                "Haptics",
            )
        val tops = order.map { top(it) }
        assertEquals("the groups run top to bottom in this order", tops.sorted(), tops)
        assertTrue("no two group titles share a line", tops.distinct().size == tops.size)
    }

    @Test fun appearanceAndHapticsRowsAreThereWithTheirValues() {
        root(SettingsHarness())
        text("Appearance").assertIsDisplayed()
        text("Haptics").assertIsDisplayed()
        text("Follows the system").assertExists()
        text("Normal").assertExists()
    }

    @Test fun theSaveToRowShowsOnlyWithSeveralAccounts() {
        root(SettingsHarness(SettingsSample.state(accounts = false)))
        compose.onNodeWithTag(SettingsTags.SaveTo, useUnmergedTree = true).assertDoesNotExist()
        val h = SettingsHarness(SettingsSample.state(accounts = true))
        root(h)
        tap(SettingsTags.SaveTo)
        assertEquals(listOf("save-to"), h.opened)
        text("Google (ayse@example.org)").assertExists()
    }

    @Test fun displayRowsShowTheirValueAndOpenTheirPage() {
        val h = SettingsHarness()
        root(h)
        text("First Last").assertExists()
        text("First Name").assertExists()
        tap(SettingsTags.NameFormat)
        tap(SettingsTags.SortBy)
        assertEquals(listOf("name-format", "sort-by"), h.opened)
    }

    @Test fun theTimeRowAppearsOnlyWhileRemindersAreOn() {
        val h = SettingsHarness()
        root(h)
        compose.onNodeWithTag(SettingsTags.ReminderTime, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.Reminders, useUnmergedTree = true).assertIsNotSelected()
        tap(SettingsTags.Reminders)
        assertEquals(listOf<SettingsAction>(SettingsAction.SetReminders(true)), h.actions)
        compose.onNodeWithTag(SettingsTags.Reminders, useUnmergedTree = true).assertIsSelected()
        text("9:00 AM").assertExists()
        tap(SettingsTags.ReminderTime)
        assertEquals(listOf("reminder-time"), h.opened)
    }

    @Test fun exportRowsAreOffForAnEmptyBook() {
        val h = SettingsHarness(SettingsSample.state().copy(hasContacts = false))
        root(h)
        tap(SettingsTags.ExportVcf)
        tap(ContactsTags.ExportCsv)
        assertTrue("nothing to export: the rows do nothing", h.opened.isEmpty())
        tap(ContactsTags.ImportCsv)
        assertEquals(listOf("import-csv"), h.opened)
    }

    @Test fun everyToolRowGoesWhereItsNameSays() {
        val h = SettingsHarness()
        root(h)
        tap(ContactsTags.FindDuplicates)
        tap(ContactsTags.CleanUp)
        tap(ContactsTags.ScanQr)
        tap(ContactsTags.PasteContact)
        tap(SettingsTags.Emergency)
        tap(SettingsTags.Trash)
        tap(SettingsTags.WhatsApp)
        tap(ContactsTags.ImportFile)
        tap(SettingsTags.ExportVcf)
        assertEquals(
            listOf(
                "duplicates",
                "clean-up",
                "scan-qr",
                "paste",
                "emergency",
                "trash",
                "whatsapp",
                "import-file",
                "export-vcf",
            ),
            h.opened,
        )
    }

    @Test fun aChoicePageTicksTheCurrentChoiceAndWritesTheOtherOne() {
        val h = SettingsHarness()
        composeFrame {
            HostedSettings(h, ContactsRoute.SettingsNameFormat) {
                ChoicePage(
                    title = "Name Format",
                    options = nameFormatOptions { if (it == NameFormat.FirstLast) "First Last" else "Last, First" },
                    selected = h.ui.nameFormat,
                    onSelect = { h.on(SettingsAction.SetNameFormat(it)) },
                )
            }
        }
        advance(700)
        compose.onNodeWithTag(SettingsTags.choice(0), useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithTag(SettingsTags.choice(1), useUnmergedTree = true).assertIsNotSelected().performClick()
        advance(300)
        assertEquals(listOf<SettingsAction>(SettingsAction.SetNameFormat(NameFormat.LastFirst)), h.actions)
        compose.onNodeWithTag(SettingsTags.choice(1), useUnmergedTree = true).assertIsSelected()
        // The ticked row writes nothing again.
        compose.onNodeWithTag(SettingsTags.choice(1), useUnmergedTree = true).performClick()
        assertEquals(1, h.actions.size)
        assertEquals(SortBy.FirstName, h.ui.sortBy)
    }

    @Test fun theSummaryListsTheTotalsAndEachAccount() {
        val h = SettingsHarness()
        composeFrame { HostedSettings(h, ContactsRoute.SettingsSummary) { SummaryPage(h.ui.summary) } }
        advance(700)
        text("With a birthday").assertIsDisplayed()
        text("64").assertIsDisplayed()
        text("Google (ayse@example.org)").assertIsDisplayed()
        text("188").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.Reminders, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anEmptySummaryExplainsWhy() {
        composeFrame { HostedSettings(SettingsHarness(), ContactsRoute.SettingsSummary) { SummaryPage(SummaryUi()) } }
        advance(700)
        text("Nothing to count yet. Allow Contacts access on the Contacts tab.").assertIsDisplayed()
    }
}
