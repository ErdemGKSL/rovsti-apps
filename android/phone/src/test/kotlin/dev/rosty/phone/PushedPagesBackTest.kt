package dev.rosty.phone

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import dev.rosty.core.model.phone.HapticsLevel
import dev.rosty.core.model.stops.Appearance
import dev.rosty.phone.settings.SettingsFixtures
import dev.rosty.phone.settings.backup.BackupScreen
import dev.rosty.phone.settings.blocked.BlockedContent
import dev.rosty.phone.settings.blocked.BlockedSample
import dev.rosty.phone.settings.data.DataScreen
import dev.rosty.phone.settings.replies.QuickRepliesScreen
import dev.rosty.phone.settings.sim.SimChoicesScreen
import dev.rosty.phone.settings.speeddial.SpeedDialScreen
import dev.rosty.shell.settings.AboutInfo
import dev.rosty.shell.settings.AboutPage
import dev.rosty.shell.settings.AppearancePage
import dev.rosty.shell.settings.HapticsPage
import dev.rosty.shell.settings.LicenceData
import dev.rosty.shell.settings.LicenceDetailPage
import dev.rosty.shell.settings.LicencesPage
import dev.rosty.shell.settings.NoticesParser
import dev.rosty.shell.settings.OwnLicenceId
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

/**
 * F16: every pushed Phone page, on the real shell host and without wiring a back action of its own, shows exactly one
 * glass back button and keeps the full four-tab bar.
 */
@Config(sdk = [35])
class PushedPagesBackTest : ShotTest() {
    private val licences =
        LicenceData(
            notices = NoticesParser.parse(File("../THIRD_PARTY_NOTICES.md").readText()),
            gplText = File("../../LICENSE").readLines(),
        )

    private val about = AboutInfo("Rosty", "0.9.0", 42, "0.3.1", "Nov 9, 2026")

    private val pages: List<Pair<PhoneRoute, @Composable () -> Unit>> =
        listOf(
            PhoneRoute.Settings to {},
            PhoneRoute.Diagnostics to {},
            PhoneRoute.QuickReplies to { QuickRepliesScreen(SettingsFixtures.replies(), {}, {}) },
            PhoneRoute.SpeedDialPage to { SpeedDialScreen(SettingsFixtures.speedDial(), {}, {}, {}) },
            PhoneRoute.SimChoices to { SimChoicesScreen(SettingsFixtures.sims(), {}) },
            PhoneRoute.Data to { DataScreen(SettingsFixtures.data(), {}, {}) },
            PhoneRoute.Backup to { BackupScreen({}, {}) },
            PhoneRoute.Appearance to { AppearancePage(Appearance.entries.first(), {}) },
            PhoneRoute.Haptics to { HapticsPage(HapticsLevel.entries.first(), {}) },
            PhoneRoute.About to { AboutPage(about, onOpenLicences = {}) },
            PhoneRoute.Licences to { LicencesPage(licences, onOpen = {}) },
            PhoneRoute.LicenceDetail(OwnLicenceId) to { LicenceDetailPage(OwnLicenceId, licences) },
            // E5.9: the pages Settings opens (the others join with their steps).
            PhoneRoute.Blocked to {
                BlockedContent(BlockedSample.ui(), onAction = {}, onPaste = {}, onPrefix = {}, onImport = {})
            },
        )

    @Test fun everyPushedPhonePageHasOneBackButtonAndTheFullTabBar() {
        val setup = PhoneSample.setup()
        val labels = listOf("Favorites", "Recents", "Contacts", "Keypad")
        for ((route, page) in pages) {
            composeFrame { HostedPhone(PhoneHarness(), setup, push = route, extra = page) }
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
