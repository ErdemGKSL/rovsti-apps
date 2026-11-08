// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/utils/IosOverscroll.kt
// (the behaviour recorded here)
package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.29 screenshot: Convx's rubber-band overscroll with the list held 80 dp past its top. */
class E129ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    @Test
    fun overscrollTop() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { LargeTitlePage() }
            val density = compose.activity.resources.displayMetrics.density
            compose.onNodeWithTag("scaffold-list").performTouchInput {
                down(center)
                moveBy(Offset(0f, 80f * density))
            }
            advance(100)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e1.29-overscroll-top-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            compose.onNodeWithTag("scaffold-list").performTouchInput { up() }
            advance(800)
        }
    }
}
