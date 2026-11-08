// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/floatingtabbar/FoldingTabGroup.kt (geometry numbers cited in the tests; test code is Rovsti's own).
package dev.rovsti.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E1.29 part A: the accessory slot of the tab bar (Convx FoldingTabBar, child 6; here a generic slot). The pill sits
 * above the tab row, as wide as the bar, and `LocalContentInsets.bottom` grows by its height, so the last list row
 * still ends above both.
 */
class TabBarAccessoryTest : ShotTest() {
    private fun lastRowBottom(showAccessory: Boolean): Float {
        composeFrame { TabBarPage(imeVisible = { false }, showAccessory = showAccessory) }
        compose.onNodeWithTag("tabbar-list").performScrollToIndex(CatalogRowCount)
        advance(600)
        return compose.onNodeWithTag("row-${CatalogRowCount - 1}").bounds.bottom
    }

    @Test
    fun accessorySitsAboveTheRowAndMatchesTheBarWidth() {
        composeFrame { TabBarPage(imeVisible = { false }, showAccessory = true) }
        advance(600)
        val accessory = compose.onNodeWithTag("tab-accessory").also { it.assertIsDisplayed() }.bounds
        val surface = compose.onNodeWithTag("tab-surface").bounds
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(
            "accessory ${accessory.bottom} must end above the row ${surface.top}",
            accessory.bottom <= surface.top + 0.5f,
        )
        // Convx FoldingTabBar: expanded, the accessory is as wide as the bar and one componentSpacing (8 dp) above the row.
        assertEquals(surface.left, accessory.left, 1.5f)
        assertEquals(surface.right, accessory.right, 1.5f)
        assertEquals(8f * density, surface.top - accessory.bottom, 1.5f)
        // Convx FloatingTabBar.kt ExpandedAccessoryFallbackHeight (54 dp) is the pill's height in the expanded bar.
        assertEquals(54f * density, accessory.height, 1.5f)
    }

    @Test
    fun theAccessoryRaisesTheContentInsetByItsHeightPlusTheGap() {
        val without = lastRowBottom(showAccessory = false)
        val with = lastRowBottom(showAccessory = true)
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("the end of the list moves up by pill + gap", (54f + 8f) * density, without - with, 2f)
    }

    @Test
    fun theLastRowStaysAboveTheAccessory() {
        composeFrame { TabBarPage(imeVisible = { false }, showAccessory = true) }
        compose.onNodeWithTag("tabbar-list").performScrollToIndex(CatalogRowCount)
        advance(600)
        val row = compose.onNodeWithTag("row-${CatalogRowCount - 1}").bounds
        val accessory = compose.onNodeWithTag("tab-accessory").bounds
        assertTrue(
            "row bottom ${row.bottom} must be <= accessory top ${accessory.top}",
            row.bottom <= accessory.top + 0.5f,
        )
    }

    @Test
    fun foldedTheAccessoryStaysAlignedWithTheCircleAndTheBarEdge() {
        composeFrame { TabBarPage(imeVisible = { false }, showAccessory = true) }
        compose.onNodeWithTag("tabbar-list").performTouchInput { swipeUp(durationMillis = 120) }
        advance(1500)
        val accessory = compose.onNodeWithTag("tab-accessory").also { it.assertIsDisplayed() }.bounds
        val bar = compose.onNodeWithTag("tab-bar").bounds
        val density = compose.activity.resources.displayMetrics.density
        // Convx FoldingTabBar: folded, the pill is 48 dp (InlineRowHeight) and ends at the right edge of the bar.
        assertEquals(48f * density, accessory.height, 1.5f)
        // The page gutter (16 dp) insets the bar; the pill ends at the bar's right edge.
        assertEquals(bar.right - 16f * density, accessory.right, 1.5f)
        // The circle is 48 dp wide at the left edge, then one 8 dp gap, then the pill.
        assertEquals(bar.left + (16f + 48f + 8f) * density, accessory.left, 2f)
    }
}
