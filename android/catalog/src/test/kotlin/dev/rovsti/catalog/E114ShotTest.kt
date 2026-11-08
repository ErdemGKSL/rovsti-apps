package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.14 screenshots: an alert (also at font scale 2), the action sheet and the destructive confirmations. */
class E114ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snapOpen(
        name: String,
        trigger: String,
        fontScale: Float = 1f,
        before: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, fontScale = fontScale) { CatalogApp(initialPage = CatalogPage.Dialogs) }
            compose.onNodeWithTag(trigger).performClick()
            advance(1200)
            before()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test
    fun alert() = snapOpen("e1.14-alert", "dialog-alert")

    @Test
    fun alertFontScale2() = snapOpen("e1.14-alert-fs2", "dialog-alert", fontScale = 2f)

    @Test
    fun actionSheet() = snapOpen("e1.14-action-sheet", "dialog-sheet")

    @Test
    fun confirmDestructive() =
        snapOpen("e1.14-confirm-destructive", "dialog-two-tap") {
            compose.onNodeWithTag("sheet-action-0").performClick()
            advance(300)
        }
}
