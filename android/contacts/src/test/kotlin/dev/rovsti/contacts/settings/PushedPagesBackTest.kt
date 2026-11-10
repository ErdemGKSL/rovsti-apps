package dev.rovsti.contacts.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.shell.settings.AboutInfo
import dev.rovsti.shell.settings.AboutPage
import dev.rovsti.shell.settings.AppearancePage
import dev.rovsti.shell.settings.HapticsPage
import dev.rovsti.shell.settings.LicenceData
import dev.rovsti.shell.settings.LicenceDetailPage
import dev.rovsti.shell.settings.LicencesPage
import dev.rovsti.shell.settings.NoticesParser
import dev.rovsti.shell.settings.OwnLicenceId
import dev.rovsti.testing.ShotTest
import dev.rovsti.wa.settings.WhatsAppSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

/**
 * F16: every pushed settings page, on the real shell host and without wiring a back action of its own, shows exactly
 * one glass back button and keeps the full four-tab bar (the folded circle read as a lone gear).
 */
@Config(sdk = [35])
class PushedPagesBackTest : ShotTest() {
    private val licences =
        LicenceData(
            notices = NoticesParser.parse(File("../THIRD_PARTY_NOTICES.md").readText()),
            gplText = File("../../LICENSE").readLines(),
        )

    private val about = AboutInfo("Rovsti", "0.9.0", 42, "0.3.1", "Nov 9, 2026")

    private fun pages(h: SettingsHarness): List<Pair<ContactsRoute, @Composable () -> Unit>> =
        listOf(
            ContactsRoute.SettingsNameFormat to {
                ChoicePage("Name Format", nameFormatOptions { it.name }, NameFormat.FirstLast, {})
            },
            ContactsRoute.SettingsSortBy to {
                ChoicePage("Sort By", sortOptions { it.name }, h.ui.sortBy, {})
            },
            ContactsRoute.SettingsSaveTo to {
                ChoicePage("Save To", listOf(ChoiceOption("", "On this phone")), "", {})
            },
            ContactsRoute.SettingsSummary to { SummaryPage(h.ui.summary) },
            ContactsRoute.SettingsReminderTime to {
                ChoicePage("Time", listOf(ChoiceOption(9, "9:00 AM")), 9, {})
            },
            ContactsRoute.WhatsAppSettings to { WhatsAppSettingsScreen(stateOf(fixtureView("")), {}) },
            ContactsRoute.Appearance to { AppearancePage(Appearance.entries.first(), {}) },
            ContactsRoute.Haptics to { HapticsPage(HapticsLevel.entries.first(), {}) },
            ContactsRoute.About to { AboutPage(about, onOpenLicences = {}) },
            ContactsRoute.Licences to { LicencesPage(licences, onOpen = {}) },
            ContactsRoute.LicenceDetail(OwnLicenceId) to { LicenceDetailPage(OwnLicenceId, licences) },
        )

    @Test fun everyPushedSettingsPageHasOneBackButtonAndTheFullTabBar() {
        val h = SettingsHarness()
        val labels = listOf("Contacts", "Favorites", "Groups", "Settings")
        for ((route, page) in pages(h)) {
            composeFrame { HostedSettings(h, route) { page() } }
            advance(900)
            assertEquals(
                "$route: one back button",
                1,
                compose.onAllNodesWithTag("back-button").fetchSemanticsNodes().size,
            )
            for (label in labels) {
                compose
                    .onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag("tab-bar")), useUnmergedTree = true)
                    .onFirst()
                    .assertIsDisplayed()
            }
        }
    }
}
