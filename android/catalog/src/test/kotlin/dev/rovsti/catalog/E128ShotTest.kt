// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/IosPageSheet.kt
// (the screen recorded here)
package dev.rovsti.catalog

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.Test

/** The E1.28 screenshot: Convx's full-screen page sheet over the catalog page. */
class E128ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    @Test
    fun pageSheet() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Sheets) }
            compose.onNodeWithTag("sheet-page").performClick()
            repeat(20) { advance(100) }
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e1.28-page-sheet-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }
}
