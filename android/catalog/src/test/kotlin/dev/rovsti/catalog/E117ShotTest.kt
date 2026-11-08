package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/**
 * The E1.17 screenshots: switch, slider and segmented control in a settings section and over a busy backdrop, at rest
 * and held (the held thumb is lens glass).
 */
class E117ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun open(dark: Boolean) {
        composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Controls) }
        advance(600)
    }

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/e1.17-$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    /** x in px of the centre of segment [index] (0-based, fractions allowed) of the tagged segmented control. */
    private fun slotX(
        tag: String,
        index: Float,
    ): Float {
        val width =
            compose
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .size.width
                .toFloat()
        val inset = 2f * 3f
        return inset + (width - 2 * inset) / 3f * (index + 0.5f)
    }

    private fun sliderX(
        tag: String,
        fraction: Float,
    ): Float {
        val width =
            compose
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .size.width
                .toFloat()
        return 60f + (width - 120f) * fraction
    }

    @Test
    fun switchOffOn() {
        for (dark in listOf(false, true)) {
            open(dark)
            capture("switch-off-on", dark)
        }
    }

    @Test
    fun switchPressed() {
        for (dark in listOf(false, true)) {
            open(dark)
            // Held halfway across: the thumb is lens glass over the stripes, the track colour is mid-change.
            compose.onNodeWithTag("busy-toggle-off").performTouchInput { down(center) }
            advance(250)
            compose.onNodeWithTag("busy-toggle-off").performTouchInput { moveBy(Offset(36f, 0f)) }
            advance(400)
            // And the row switch held at rest, over the white surface.
            capture("switch-pressed", dark)
            compose.onNodeWithTag("busy-toggle-off").performTouchInput { up() }
            advance(600)
        }
    }

    @Test
    fun slider() {
        for (dark in listOf(false, true)) {
            open(dark)
            compose.onNodeWithTag("busy-slider").fetchSemanticsNode()
            val tag = "busy-slider"
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
                down(Offset(sliderX(tag, 0.6f), center.y))
            }
            advance(300)
            capture("slider", dark)
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { up() }
            advance(600)
        }
    }

    @Test
    fun segmented3() {
        for (dark in listOf(false, true)) {
            open(dark)
            for (tag in listOf("segmented-3", "busy-segmented")) {
                compose.onNodeWithTag(tag).performTouchInput {
                    down(Offset(slotX(tag, 2f), center.y))
                    up()
                }
                advance(700)
            }
            capture("segmented-3", dark)
        }
    }

    @Test
    fun segmentedDrag() {
        for (dark in listOf(false, true)) {
            open(dark)
            val tag = "busy-segmented"
            compose.onNodeWithTag(tag).performTouchInput { down(Offset(slotX(tag, 1f), center.y)) }
            advance(250)
            compose.onNodeWithTag(tag).performTouchInput { moveTo(Offset(slotX(tag, 1.5f), center.y)) }
            advance(400)
            capture("segmented-drag", dark)
            compose.onNodeWithTag(tag).performTouchInput { up() }
            advance(600)
        }
    }
}
