package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.10 part 1 screenshots: the menu at fixed frames of its morph, below, above and clamped at the edge. */
class E110ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    /** A menu opened by a touch-down needs a couple of frames to start its spring before time can be advanced. */
    private fun settleMenu() {
        advance(16)
        advance(16)
        advance(1200)
    }

    /** Frames of the large-title page's "..." menu. */
    @Test
    fun morphFramesFromTheLargeTitleOverflow() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.LargeTitle) }
            snap("e1.10-menu-closed", dark)
            compose.onNodeWithTag("toolbar-overflow").performClick()
            advance(40)
            snap("e1.10-menu-+40ms", dark)
            advance(60)
            snap("e1.10-menu-+100ms", dark)
            advance(1200)
            snap("e1.10-menu-open-below", dark)
        }
    }

    @Test
    fun menuAbove() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
            compose.onNodeWithTag("menu-trigger-bottom").performClick()
            settleMenu()
            snap("e1.10-menu-open-above", dark)
        }
    }

    @Test
    fun menuClampedAtTheEdge() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
            compose.onNodeWithTag("menu-trigger-edge").performClick()
            settleMenu()
            snap("e1.10-menu-clamped-edge", dark)
        }
    }
}
