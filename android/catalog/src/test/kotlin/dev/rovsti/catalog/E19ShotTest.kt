package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.9 screenshots: two overlay entries stacked over the dimmed page. */
class E19ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    @Test
    fun twoEntriesStacked() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Overlays) }
            compose.onNodeWithTag("present-first").performClick()
            advance(800)
            compose.onNodeWithTag("overlay-another-1").performClick()
            advance(1000)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e1.9-host-two-entries-stacked-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }
}
