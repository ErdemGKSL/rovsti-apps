package dev.rovsti.catalog

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.12: the popover keeps the page where it is, scrolls when tall and flips above when anchored low. */
@OptIn(ExperimentalTestApi::class)
class PopoverTest : ShotTest() {
    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun open() = composeFrame { CatalogApp(initialPage = CatalogPage.Popover) }

    private fun settleClosed() {
        repeat(6) { advance(300) }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settleClosed()
    }

    @Test
    fun openingNeverMovesThePageContent() {
        open()
        val tags = listOf("pop-node-0", "pop-node-1", "pop-node-2")
        val before = tags.map(::bounds)
        compose.onNodeWithTag("pop-date").performClick()
        for (ms in listOf(16L, 84L, 200L, 900L)) {
            advance(ms)
            assertEquals("after +$ms ms", before, tags.map(::bounds))
        }
        compose.onNodeWithTag("popover-card").assertIsDisplayed()
        back()
        assertEquals("after close", before, tags.map(::bounds))
        compose.onNodeWithTag("popover-card").assertDoesNotExist()
    }

    @Test
    fun theCardIsAtMost320DpWideAndOpensBelowFlushWithItsButton() {
        open()
        val density = compose.activity.resources.displayMetrics.density
        val trigger = bounds("pop-date")
        compose.onNodeWithTag("pop-date").performClick()
        advance(1200)
        val card = bounds("popover-card")
        assertTrue("at most 320 dp: ${card.width / density}", card.width <= 320f * density + 1f)
        assertEquals(trigger.top, card.top, 1f)
        assertTrue(card.bottom > trigger.bottom)
    }

    @Test
    fun tallContentScrollsToItsLastRow() {
        open()
        compose.onNodeWithTag("pop-long").performClick()
        advance(1200)
        val rootHeight = compose.activity.resources.displayMetrics.heightPixels
        assertTrue("card fits the window", bounds("popover-card").height < rootHeight)
        compose.onNodeWithTag("pop-row-${LongRows - 1}").assertIsNotDisplayed()
        // The scroll action animates, so the clock has to run by itself while it does.
        compose.mainClock.autoAdvance = true
        try {
            compose.onNodeWithTag("popover-scroll").performScrollToNode(hasTestTag("pop-row-${LongRows - 1}"))
        } finally {
            compose.mainClock.autoAdvance = false
        }
        advance(100)
        compose.onNodeWithTag("pop-row-${LongRows - 1}").assertIsDisplayed()
    }

    @Test
    fun anchoredLowThePopoverSitsAboveItsButton() {
        open()
        val trigger = bounds("pop-low")
        compose.onNodeWithTag("pop-low").performClick()
        advance(1200)
        val card = bounds("popover-card")
        assertEquals(trigger.bottom, card.bottom, 1f)
        assertTrue(card.top < trigger.top)
    }

    @Test
    fun aTapOutsideClosesItAndTheScrimEatsTheTap() {
        open()
        compose.onNodeWithTag("pop-date").performClick()
        advance(1200)
        compose.onNodeWithTag("overlay-scrim").performClick()
        settleClosed()
        compose.onNodeWithTag("popover-card").assertDoesNotExist()
    }
}
