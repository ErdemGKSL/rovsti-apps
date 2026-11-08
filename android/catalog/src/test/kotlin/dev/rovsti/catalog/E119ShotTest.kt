package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.list.AlphabetRailTags
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.19 screenshots: the lists page at rest and with a finger holding the rail on M. */
class E119ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun open(dark: Boolean) {
        composeFrame(dark = dark) {
            CompositionLocalProvider(LocalRovstiHaptics provides RecordingHaptics()) {
                OverlayHost(remember { OverlayController() }) { ListsPage(imeVisible = { false }) }
            }
        }
        advance(400)
    }

    @Test
    fun listsPage() {
        for (dark in listOf(false, true)) {
            open(dark)
            compose.onRoot().captureRoboImage(
                "screenshots/e1.19-lists-page-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test
    fun scrubbing() {
        for (dark in listOf(false, true)) {
            open(dark)
            val rail = compose.onNodeWithTag(AlphabetRailTags.Rail)
            val h = rail.fetchSemanticsNode().size.height

            fun y(slot: Int) = h / 28f * (slot + 0.5f)
            rail.performTouchInput {
                down(Offset(centerX, y(1)))
                for (slot in 2..13) moveTo(Offset(centerX, y(slot)))
            }
            advance(400)
            compose.onRoot().captureRoboImage(
                "screenshots/e1.19-alphabet-rail-scrubbing-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            rail.performTouchInput { up() }
            advance(400)
        }
    }
}
