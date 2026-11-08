package dev.rovsti.catalog

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** AC3: opening an overlay never moves or resizes the trigger. */
class TriggerBoundsTest : ShotTest() {
    private fun trigger(): Rect =
        compose.onNodeWithTag("present-first", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun boundsAreIdenticalBeforeDuringAndAfterPresent() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Overlays) }
        val rest = trigger()
        compose.onNodeWithTag("present-first").performClick()
        advance(16)
        assertEquals("+16 ms", rest, trigger())
        advance(184)
        assertEquals("+200 ms", rest, trigger())
        advance(800)
        assertEquals("settled", rest, trigger())
        compose.onNodeWithTag("overlay-close-1").performClick()
        advance(1500)
        assertEquals("after dismiss", rest, trigger())
    }
}
