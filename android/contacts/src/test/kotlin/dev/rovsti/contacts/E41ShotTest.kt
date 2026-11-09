package dev.rovsti.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.1 screenshots: the Contacts tab with permission granted and an empty book, light and dark. */
@Config(sdk = [35])
class E41ShotTest : ShotTest() {
    @Test fun tabsOverAnEmptyBook() {
        val fixture = AppFixture(granted = true)
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { fixture.Content() }
            // The book loads on real dispatchers: wait for the empty state instead of counting frames (a busy
            // machine made the capture race it).
            var tries = 0
            while (compose.onAllNodesWithTag(ContactsTags.EmptyBook).fetchSemanticsNodes().isEmpty() && tries++ < 100) {
                Thread.sleep(50)
                advance(100)
            }
            advance(600)
            compose.onNodeWithTag(ContactsTags.EmptyBook).assertIsDisplayed()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.1-tabs-empty-book-${if (dark) "dark" else "light"}.png",
                roborazziOptions =
                    RoborazziOptions(
                        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
                    ),
            )
        }
    }

    @Test fun settingsTab() {
        val fixture = AppFixture(granted = true)
        fixture.navigator.select(SettingsTab)
        shot("e4.1-settings") { fixture.Content() }
    }

    @Test fun permissionDeniedLeavesARowNotAnEmptyBook() {
        val fixture = AppFixture(granted = false)
        // The rationale sheet slides in: let it settle before the capture.
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { fixture.Content() }
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.1-permission-needed-${if (dark) "dark" else "light"}.png",
                roborazziOptions =
                    RoborazziOptions(
                        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
                    ),
            )
        }
    }
}
