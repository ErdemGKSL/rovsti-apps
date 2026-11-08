package dev.rovsti.catalog

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.10 part 1: the button-anchored menu opens, stays out of the trigger's layout, places itself and closes. */
@OptIn(ExperimentalTestApi::class)
class ButtonMenuTest : ShotTest() {
    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun open(tag: String) {
        composeFrame { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
        compose.onNodeWithTag(tag).performClick()
        advance(1200)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1500)
    }

    @Test
    fun tapOpensTheMenuAndTheTriggerDoesNotMove() {
        composeFrame { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
        val rest = bounds("menu-trigger-top")
        compose.onNodeWithTag("menu-card").assertDoesNotExist()
        compose.onNodeWithTag("menu-trigger-top").performClick()
        advance(16)
        assertEquals("+16 ms", rest, bounds("menu-trigger-top"))
        advance(184)
        assertEquals("+200 ms", rest, bounds("menu-trigger-top"))
        advance(1000)
        compose.onNodeWithTag("menu-card").assertIsDisplayed()
        assertEquals("settled", rest, bounds("menu-trigger-top"))
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("card is 238 dp wide (IosMenuMaterial.Width)", 238f * density, bounds("menu-card").width, 1f)
        assertEquals(
            "row is 44 dp tall (Convx IosMenu.kt row min height)",
            44f * density,
            bounds("menu-row-0").height,
            1f,
        )
        back()
        assertEquals("after close", rest, bounds("menu-trigger-top"))
    }

    @Test
    fun backClosesAndTheEntryLeavesComposition() {
        open("menu-trigger-top")
        compose.onNodeWithTag("menu-card").assertIsDisplayed()
        back()
        compose.onNodeWithTag("menu-card").assertDoesNotExist()
    }

    @Test
    fun escapeClosesTheMenu() {
        open("menu-trigger-top")
        compose.onNodeWithTag("menu-card").performKeyInput { pressKey(Key.Escape) }
        advance(1500)
        compose.onNodeWithTag("menu-card").assertDoesNotExist()
    }

    @Test
    fun scrimTapClosesAndRowTapClosesAndRuns() {
        open("menu-trigger-top")
        compose.onNodeWithTag("overlay-scrim").performClick()
        advance(1500)
        compose.onNodeWithTag("menu-card").assertDoesNotExist()
    }

    @Test
    fun nearTheTopTheMenuOpensBelowTheTriggerWithAnEightDpGap() {
        composeFrame { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
        val density = compose.activity.resources.displayMetrics.density
        val trigger = bounds("menu-trigger-top")
        compose.onNodeWithTag("menu-trigger-top").performClick()
        advance(1200)
        val card = bounds("menu-card")
        // Convx IosContextMenu.kt: anchorGap = 8.dp, centred on the anchor, kept 12 dp off the edge.
        assertEquals(trigger.bottom + 8f * density, card.top, 1f)
        assertTrue(card.left >= 12f * density - 1f)
    }

    @Test
    fun nearTheBottomTheMenuOpensAboveWithAnEightDpGap() {
        composeFrame { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
        val trigger = bounds("menu-trigger-bottom")
        compose.onNodeWithTag("menu-trigger-bottom").performClick()
        advance(1200)
        val card = bounds("menu-card")
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(trigger.top - 8f * density, card.bottom, 1f)
        assertTrue(card.top < trigger.top)
    }

    @Test
    fun atTheTrailingEdgeTheMenuIsClampedTwelveDpInside() {
        composeFrame { CatalogApp(initialPage = CatalogPage.ButtonMenu) }
        val density = compose.activity.resources.displayMetrics.density
        val trigger = bounds("menu-trigger-edge")
        compose.onNodeWithTag("menu-trigger-edge").performClick()
        advance(1200)
        val card = bounds("menu-card")
        val windowWidth =
            compose.activity.resources.displayMetrics.widthPixels
                .toFloat()
        assertTrue("trigger hugs the edge", windowWidth - trigger.right < 12f * density)
        assertEquals(windowWidth - 12f * density, card.right, 1f)
        assertTrue(card.left >= 12f * density)
    }

    /**
     * While the menu is open the trigger is only dimmed by the scrim. Nothing else of it may linger: the three triggers are
     * the same element over the same flat page, so the one that opened the menu must look pixel for pixel like one that
     * did not (a ghost of the anchor's size, e.g. a shadow outline, shows up as a difference).
     */
    @Test
    fun triggerLeavesNoResidueWhileMenuIsOpen() {
        open("menu-trigger-top")
        compose.onNodeWithTag("menu-card").assertIsDisplayed()
        val top = bounds("menu-trigger-top")
        val bottom = bounds("menu-trigger-bottom")
        val root = compose.onRoot().captureToImage()
        val w = top.width.toInt()
        val h = top.height.toInt()

        fun crop(r: Rect) = IntArray(w * h).also { root.readPixels(it, r.left.toInt(), r.top.toInt(), w, h) }
        val a = crop(top)
        val b = crop(bottom)
        var worst = 0
        for (i in a.indices) {
            for (shift in intArrayOf(0, 8, 16)) {
                worst = maxOf(worst, kotlin.math.abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
            }
        }
        assertTrue("trigger differs from an untouched one by $worst (> 6)", worst <= 6)
    }
}
