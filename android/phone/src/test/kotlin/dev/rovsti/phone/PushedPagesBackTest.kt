package dev.rovsti.phone

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.phone.settings.blocked.BlockedContent
import dev.rovsti.phone.settings.blocked.BlockedSample
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

    private val about = AboutInfo("Rovsti", "0.9.0", 42, "0.3.1", "Nov 9, 2026")

    private val pages: List<Pair<PhoneRoute, @Composable () -> Unit>> =
        listOf(
            PhoneRoute.Settings to {},
            PhoneRoute.Diagnostics to {},
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
