package dev.rovsti.catalog

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** E1.9: back, exit-before-removal, modal semantics, pane titles and the input-eating scrim. */
class OverlayHostTest : ShotTest() {
    private fun open() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Overlays) }
    }

    private fun page(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun card(i: Int) = compose.onNodeWithTag("overlay-card-$i")

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(40)
        advance(1500)
    }

    private fun presentBoth() {
        compose.onNodeWithTag("present-first").performClick()
        advance(800)
        compose.onNodeWithTag("overlay-another-1").performClick()
        advance(800)
    }

    @Test
    fun backClosesOnlyTheTopEntryThenTheNext() {
        open()
        presentBoth()
        card(1).assertExists()
        card(2).assertExists()
        back()
        card(2).assertDoesNotExist()
        card(1).assertExists()
        back()
        card(1).assertDoesNotExist()
        // With no overlay left Back reaches the catalog: Overlays page -> Home.
        compose.onNodeWithTag("home-pages").assertDoesNotExist()
        back()
        compose.onNodeWithTag("home-pages").assertIsDisplayed()
    }

    @Test
    fun theExitPlaysBeforeTheEntryIsRemoved() {
        open()
        presentBoth()
        compose.onNodeWithTag("overlay-close-2").performClick()
        advance(40)
        card(2).assertExists()
        advance(1500)
        card(2).assertDoesNotExist()
        card(1).assertExists()
    }

    @Test
    fun theTopEntryAloneLeavesOnAScrimTap() {
        open()
        presentBoth()
        compose.onRoot().performTouchInput {
            click(
                topLeft +
                    androidx.compose.ui.geometry
                        .Offset(4f, 4f),
            )
        }
        advance(40)
        advance(1500)
        card(2).assertDoesNotExist()
        card(1).assertExists()
    }

    @Test
    fun theSemanticsOfThePageAreClearedWhileModalAndRestoredAfter() {
        open()
        compose.onNodeWithTag("present-first").assertExists()
        compose.onNodeWithTag("present-first").performClick()
        advance(800)
        compose.onNodeWithTag("present-first").assertDoesNotExist()
        page("present-first").assertExists() // still composed, only hidden from accessibility
        compose.onNodeWithTag("overlay-close-1").performClick()
        advance(1500)
        compose.onNodeWithTag("present-first").assertExists()
    }

    @Test
    fun eachEntryIsAPaneWithItsTitle() {
        open()
        presentBoth()
        compose
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Demo card 1"))
            .assertExists()
        compose
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Demo card 2"))
            .assertExists()
    }

    @Test
    fun theScrimBlocksTheListUnderneath() {
        open()
        val before = page("page-row-0").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("present-second").performClick()
        advance(800)
        compose.onRoot().performTouchInput {
            swipeUp(
                startY = height * 0.9f,
                endY = height * 0.2f,
                durationMillis = 200,
            )
        }
        advance(500)
        val after = page("page-row-0").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
    }
}
