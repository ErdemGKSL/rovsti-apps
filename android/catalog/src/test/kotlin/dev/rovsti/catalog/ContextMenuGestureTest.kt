package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.11: the gesture resolves tap, hold, scroll and child click; TalkBack gets the same actions; the preview drags. */
@OptIn(ExperimentalTestApi::class)
class ContextMenuGestureTest : ShotTest() {
    private val haptics = RecordingHaptics()

    private fun show() {
        composeFrame {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                CatalogApp(initialPage = CatalogPage.ContextMenu)
            }
        }
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun status(text: String) =
        compose.onNodeWithTag("ctx-status", useUnmergedTree = true).assertTextEquals("Last: $text")

    private fun panelOpen() = compose.onAllNodesWithTagCount("context-panel") > 0

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(
            androidx.compose.ui.test
                .hasTestTag(tag),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

    private fun hold(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { down(center) }
        advance(16)
        advance(600)
    }

    private fun lift(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { up() }
        advance(16)
        advance(300)
    }

    @Test
    fun aTapOpensTheRowAndNothingElse() {
        show()
        compose.onNodeWithTag("ctx-row-1").performClick()
        advance(16)
        advance(800)
        status("opened Can Demir")
        assertTrue(!panelOpen())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun aHoldOpensTheMenuWithALongPressHaptic() {
        show()
        hold("ctx-row-1")
        assertTrue("panel", panelOpen())
        assertEquals(listOf(HapticEvent.LongPress), haptics.events)
        status("nothing yet")
        lift("ctx-row-1")
        // The lift of the opening finger does not click anything under it.
        status("nothing yet")
        assertTrue(panelOpen())
    }

    @Test
    fun movingTwentyDpEarlyScrollsTheListAndOpensNothing() {
        show()
        val before = bounds("ctx-row-3").top
        compose.onNodeWithTag("ctx-row-3", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -10f * density))
            moveBy(Offset(0f, -10f * density))
        }
        advance(16)
        advance(700)
        compose.onNodeWithTag("ctx-row-3", useUnmergedTree = true).performTouchInput { up() }
        advance(16)
        advance(300)
        assertTrue("list scrolled", bounds("ctx-row-3").top < before - 1f * density)
        assertTrue(!panelOpen())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun theTrailingButtonKeepsItsClick() {
        show()
        compose.onNodeWithTag("ctx-more-Can Demir", useUnmergedTree = true).performClick()
        advance(16)
        advance(800)
        status("more Can Demir")
        assertTrue(!panelOpen())
    }

    @Test
    fun holdingTheTrailingButtonOpensNoMenu() {
        show()
        hold("ctx-more-Can Demir")
        assertTrue(!panelOpen())
        assertTrue(haptics.events.isEmpty())
        lift("ctx-more-Can Demir")
        assertTrue(!panelOpen())
    }

    @Test
    fun theAccessibilityLongClickOpensAndCustomActionsRun() {
        show()
        compose.onNodeWithTag("ctx-row-2").performSemanticsAction(SemanticsActions.OnLongClick)
        advance(16)
        advance(800)
        assertTrue(panelOpen())
        assertEquals(listOf(HapticEvent.LongPress), haptics.events)
    }

    @Test
    fun theRowOffersTheItemsAsCustomActions() {
        show()
        val node = compose.onNodeWithTag("ctx-row-4").fetchSemanticsNode()
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Message", "Call", "Mute", "Delete"), labels)
        assertTrue(SemanticsActions.OnLongClick in node.config)
    }

    @Test
    fun aCustomActionRunsItsItem() {
        show()
        val node = compose.onNodeWithTag("ctx-row-4").fetchSemanticsNode()
        val call = node.config[SemanticsActions.CustomActions].first { it.label == "Call" }
        compose.runOnUiThread { call.action() }
        advance(16)
        status("Call Furkan Şahin")
    }

    @Test
    fun theRowKeepsItsBoundsWhileTheMenuIsOpen() {
        show()
        val rest = bounds("ctx-row-4")
        hold("ctx-row-4")
        assertTrue(panelOpen())
        assertEquals("open", rest, bounds("ctx-row-4"))
        lift("ctx-row-4")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(16)
        advance(1500)
        assertTrue(!panelOpen())
        assertEquals("closed", rest, bounds("ctx-row-4"))
    }

    @Test
    fun aRowChosenFromThePanelRunsAndCloses() {
        show()
        hold("ctx-row-1")
        lift("ctx-row-1")
        compose.onNodeWithTag("menu-row-1", useUnmergedTree = true).performClick()
        advance(16)
        advance(1500)
        status("Call Can Demir")
        assertTrue(!panelOpen())
    }

    @Test
    fun draggingThePreviewEightyDpDownDismisses() {
        show()
        compose.onNodeWithTag("ctx-tile-left", useUnmergedTree = true).performTouchInput { down(center) }
        advance(16)
        advance(700)
        assertTrue(panelOpen())
        compose.onNodeWithTag("preview-card", useUnmergedTree = true).assertExists()
        val tile = compose.onNodeWithTag("ctx-tile-left", useUnmergedTree = true)
        tile.performTouchInput { moveBy(Offset(0f, 40f * density)) }
        advance(16)
        tile.performTouchInput { moveBy(Offset(0f, 40f * density)) }
        advance(16)
        advance(100)
        tile.performTouchInput { up() }
        advance(16)
        advance(1500)
        assertTrue("dismissed", !panelOpen())
        compose.onNodeWithTag("preview-card", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aShortDragReturnsTheCardAndKeepsTheMenu() {
        show()
        compose.onNodeWithTag("ctx-tile-right", useUnmergedTree = true).performTouchInput { down(center) }
        advance(16)
        advance(700)
        val rest = bounds("preview-card")
        val tile = compose.onNodeWithTag("ctx-tile-right", useUnmergedTree = true)
        tile.performTouchInput { moveBy(Offset(0f, 30f * density)) }
        advance(16)
        advance(100)
        assertTrue("card follows", bounds("preview-card").top > rest.top + 10f * density)
        tile.performTouchInput { up() }
        advance(16)
        advance(1500)
        assertTrue(panelOpen())
        assertEquals(rest.top, bounds("preview-card").top, 1f)
    }
}
