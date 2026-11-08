package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.13 screenshots: modal sheets at each detent, a stacked pair over the pushed-back page, and a non-modal sheet. */
class E113ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        vararg taps: String,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Sheets) }
            for (tag in taps) {
                compose.onNodeWithTag(tag).performClick()
                repeat(20) { advance(100) }
            }
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test
    fun peek() = snap("e1.13-sheet-peek", "sheet-peek")

    @Test
    fun medium() = snap("e1.13-sheet-medium", "sheet-medium")

    @Test
    fun large() = snap("e1.13-sheet-large", "sheet-large")

    @Test
    fun pushback() = snap("e1.13-sheet-pushback", "sheet-stacked", "sheet-open-second")

    @Test
    fun nonModalOverStripes() = snap("e1.13-sheet-nonmodal-over-stripes", "sheet-nonmodal")
}
