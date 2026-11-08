package dev.rovsti.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC3: with 50 rows scrolled to the end, the last row sits entirely above the bar. */
class ListEndAboveBarTest : ShotTest() {
    @Test
    fun lastRowEndsAtOrAboveTheBar() {
        composeFrame { TabBarPage() }
        compose.onNodeWithTag("tabbar-list").performScrollToIndex(CatalogRowCount)
        advance(500)
        val row = compose.onNodeWithTag("row-${CatalogRowCount - 1}")
        row.assertIsDisplayed()
        val bar = compose.onNodeWithTag("tab-bar").bounds
        val last = row.bounds
        assertTrue("row-49 bottom ${last.bottom} must be <= bar top ${bar.top}", last.bottom <= bar.top + 0.5f)
    }

    @Test
    fun barSitsEightDpAboveTheNavigationInset() {
        composeFrame { TabBarPage() }
        val root = compose.onNodeWithTag("tabbar-page").bounds
        val surface = compose.onNodeWithTag("tab-surface").bounds
        val density = compose.activity.resources.displayMetrics.density
        // The simulated gesture-navigation bar is 24 dp tall.
        val expected = root.bottom - (24f + 8f) * density
        assertTrue(
            "surface bottom ${surface.bottom} expected $expected",
            kotlin.math.abs(surface.bottom - expected) <= 1.5f,
        )
    }
}
