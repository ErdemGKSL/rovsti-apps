package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.7 screenshots: tab bar at rest, mid-drag, minimised, folded, list end, font scale 2. */
class E17ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(name: String) {
        compose.onRoot().captureRoboImage(filePath = "screenshots/$name.png", roborazziOptions = options)
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    @Test
    fun rest() {
        shot("e1.7-tabbar-rest") { TabBarPage(imeVisible = { false }) }
    }

    @Test
    fun dragMid() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { TabBarPage(imeVisible = { false }) }
            val g = BarGeometry(this)
            val from = g.slotCentre(0f) - g.surface.topLeft
            compose.onNodeWithTag("tab-surface").performTouchInput { down(from) }
            advance(200)
            val total = 1.5f * g.slot
            repeat(10) {
                compose.onNodeWithTag("tab-surface").performTouchInput { moveBy(Offset(total / 10, 0f)) }
                advance(16)
            }
            advance(100)
            snap("e1.7-tabbar-drag-mid-${theme(dark)}")
            compose.onNodeWithTag("tab-surface").performTouchInput { up() }
            advance(1000)
        }
    }

    @Test
    fun minimised() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { TabBarPage(imeVisible = { false }) }
            compose.onNodeWithTag("tabbar-list").performTouchInput { swipeUp(durationMillis = 120) }
            advance(1200)
            snap("e1.7-tabbar-minimised-${theme(dark)}")
        }
    }

    @Test
    fun folded() {
        shot("e1.7-tabbar-folded") { FoldedTabBarPage() }
    }

    @Test
    fun listEndAboveBar() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { TabBarPage(imeVisible = { false }) }
            compose.onNodeWithTag("tabbar-list").performScrollToIndex(CatalogRowCount)
            advance(600)
            snap("e1.7-list-end-above-bar-${theme(dark)}")
        }
    }

    /** E1.29: the accessory slot with the Phone "On call 02:14" pill, expanded (the second image folded). */
    @Test
    fun accessory() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { TabBarPage(imeVisible = { false }, showAccessory = true) }
            compose.onNodeWithTag("tabbar-list").performScrollToIndex(CatalogRowCount)
            advance(600)
            snap("e1.29-tabbar-accessory-${theme(dark)}")
            composeFrame(dark = dark) { TabBarPage(imeVisible = { false }, showAccessory = true) }
            compose.onNodeWithTag("tabbar-list").performTouchInput { swipeUp(durationMillis = 120) }
            advance(1200)
            snap("e1.29-tabbar-accessory-folded-${theme(dark)}")
        }
    }

    @Test
    fun fontScaleTwo() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, fontScale = 2f) { TabBarPage(imeVisible = { false }) }
            snap("e1.7-tabbar-fs2-${theme(dark)}")
        }
    }
}
