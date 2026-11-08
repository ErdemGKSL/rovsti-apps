package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

class PressPageTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(name: String) {
        compose.onRoot().captureRoboImage(filePath = "screenshots/$name.png", roborazziOptions = options)
    }

    /**
     * Finger down in the button's right third and dragged a little further right, held 250 ms, lifted. The frames are
     * taken at rest, 60 ms after the touch and 120 ms after the lift.
     */
    @Test
    fun pressTimeline() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Press) }
            snap("e1.4-press-rest-$theme")
            compose.onNodeWithTag("press-target").performTouchInput {
                down(Offset(width * 5f / 6f, height / 2f))
                moveBy(Offset(90f, 0f))
            }
            advance(60)
            snap("e1.4-press-down-+60ms-$theme")
            advance(190)
            compose.onNodeWithTag("press-target").performTouchInput { up() }
            advance(120)
            snap("e1.4-press-release-+120ms-$theme")
            advance(500)
        }
    }

    @Test
    fun pressedScaleCardAndRowAreCaptured() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Press) }
            val scale =
                compose
                    .onNodeWithTag("press-target-scale")
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            val row =
                compose
                    .onNodeWithTag("press-target-row")
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            // Two fingers: one on the card, one on the row.
            compose.onRoot().performTouchInput {
                down(0, scale)
                down(1, row)
            }
            advance(250)
            snap("e1.4-press-scale-row-held-$theme")
            compose.onRoot().performTouchInput {
                up(0)
                up(1)
            }
            advance(500)
        }
    }

    @Test
    fun tappingStillClicks() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Press) }
        compose.onNodeWithText("Text below the button. Taps: 0").assertIsDisplayed()
        compose.onNodeWithTag("press-visual").performClick()
        advance(100)
        compose.onNodeWithText("Text below the button. Taps: 1").assertIsDisplayed()
    }
}
