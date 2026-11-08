package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.15 screenshots: the toast above the tab bar, the undo pill mid-countdown and a long toast at font scale 2. */
class E115ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        trigger: String,
        wait: Long,
        fontScale: Float = 1f,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, fontScale = fontScale) { CatalogApp(initialPage = CatalogPage.Toasts) }
            compose.onNodeWithTag(trigger).performClick()
            advance(wait)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test
    fun toastAboveBar() = snap("e1.15-toast-above-bar", "toast-show", 800)

    @Test
    fun undoPill() = snap("e1.15-undo-pill", "toast-undo", 2000)

    @Test
    fun longToastFontScale2() = snap("e1.15-toast-long-fs2", "toast-long", 800, fontScale = 2f)
}
