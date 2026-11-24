package dev.rosty.contacts.cards

import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.testing.ShotTest
import dev.rosty.testing.ToggleThumbProbe
import dev.rosty.testing.toggleThumbProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * X.4 step 2: the Card Studio switch, measured in the sheet over the real root backdrop (API 35, default settings, the
 * rig of the other Card Studio tests). The thumb is a 40 x 24 dp capsule in a 64 x 28 dp track, centred, and white at rest
 * whatever the sheet or its scrim put behind it.
 */
@Config(sdk = [35])
class CardStudioToggleShapeTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun probe(dark: Boolean): ToggleThumbProbe {
        val r = CardStudioRig(this)
        r.show(dark)
        r.open()
        // Let the held-scale spring and the sheet settle fully before measuring.
        repeat(5) { advance(400) }
        val density = compose.activity.resources.displayMetrics.density
        val p = compose.toggleThumbProbe(CardStudioTags.Animated, density)
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/x4-studio-toggle-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
        return p
    }

    private fun assertShape(
        p: ToggleThumbProbe,
        name: String,
    ) {
        assertEquals("$name thumb width", 40f, p.thumbWidthDp, 1f)
        assertEquals("$name thumb height", 24f, p.thumbHeightDp, 1f)
        assertEquals("$name track width", 64f, p.trackWidthDp, 1f)
        assertEquals("$name track height", 28f, p.trackHeightDp, 1f)
        assertEquals("$name thumb centred vertically", 0f, p.centreOffsetYDp, 0.5f)
        assertTrue("$name thumb centre must be white at rest, was $p", p.minChannel >= 250)
    }

    @Test fun lightSheetToggleIsAWhiteCapsule() = assertShape(probe(dark = false), "light")

    @Test fun darkSheetToggleIsAWhiteCapsule() = assertShape(probe(dark = true), "dark")
}
