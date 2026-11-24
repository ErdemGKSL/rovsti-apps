package dev.rosty.catalog

import dev.rosty.testing.ShotTest
import dev.rosty.testing.toggleThumbProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The same numbers as the Card Studio probe (contacts CardStudioToggleShapeTest), for the catalog's busy-backdrop toggle. */
@Config(sdk = [35])
class ControlsToggleShapeTest : ShotTest() {
    @Test fun controlsToggleIsAWhiteCapsule() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { CatalogApp(initialPage = CatalogPage.Controls) }
            advance(1000)
            val density = compose.activity.resources.displayMetrics.density
            val p = compose.toggleThumbProbe("busy-toggle-off", density)
            assertEquals(40f, p.thumbWidthDp, 1f)
            assertEquals(24f, p.thumbHeightDp, 1f)
            assertEquals(64f, p.trackWidthDp, 1f)
            assertEquals(28f, p.trackHeightDp, 1f)
            assertEquals(0f, p.centreOffsetYDp, 0.5f)
            assertTrue("dark=$dark thumb centre must be white at rest, was $p", p.minChannel >= 250)
        }
    }
}
