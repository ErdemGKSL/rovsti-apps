package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.12 screenshots: a popover below its button, above a low one, and a tall one that scrolls. */
class E112ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snapOpen(
        name: String,
        trigger: String,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Popover) }
            compose.onNodeWithTag(trigger).performClick()
            advance(1200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test
    fun below() = snapOpen("e1.12-popover-below", "pop-date")

    @Test
    fun above() = snapOpen("e1.12-popover-above", "pop-low")

    @Test
    fun tallScrolling() = snapOpen("e1.12-popover-tall-scrolling", "pop-long")
}
