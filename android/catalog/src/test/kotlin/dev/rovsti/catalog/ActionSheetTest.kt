package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.14 action sheet and destructive confirmations. */
class ActionSheetTest : ShotTest() {
    private val haptics = RecordingHaptics()

    private fun show(tag: String) {
        composeFrame {
            CompositionLocalProvider(
                LocalRovstiHaptics provides haptics,
            ) { CatalogApp(initialPage = CatalogPage.Dialogs) }
        }
        compose.onNodeWithTag(tag).performClick()
        advance(1000)
    }

    private fun result() =
        compose
            .onNodeWithTag("dialog-result", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun label(tag: String) =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .let { node ->
                node.children
                    .firstNotNullOfOrNull {
                        it.config.getOrNull(
                            SemanticsProperties.Text,
                        )
                    }?.joinToString { it.text }
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            }

    @Test
    fun twoTapActionCommitsOnlyOnTheSecondTapWithinThreeSeconds() {
        show("dialog-two-tap")
        compose.onNodeWithTag("sheet-action-0").performClick()
        advance(100)
        compose.onNodeWithTag("action-sheet").assertIsDisplayed()
        assertEquals("Nothing chosen yet", result())
        assertEquals("Tap again to delete", label("sheet-action-0"))
        advance(2000)
        compose.onNodeWithTag("sheet-action-0").performClick()
        advance(1000)
        advance(100)
        compose.onNodeWithTag("action-sheet").assertDoesNotExist()
        assertEquals("Two-tap: deleted", result())
        assertTrue(HapticEvent.Confirm in haptics.events)
        assertTrue(HapticEvent.Reject !in haptics.events)
    }

    @Test
    fun theArmedStateLapsesAfterThreeSecondsWithARejectHaptic() {
        show("dialog-two-tap")
        compose.onNodeWithTag("sheet-action-0").performClick()
        advance(2900)
        assertEquals("Tap again to delete", label("sheet-action-0"))
        advance(300)
        assertEquals("Delete all history", label("sheet-action-0"))
        assertTrue(HapticEvent.Reject in haptics.events)
        // A tap after it lapsed only arms again.
        compose.onNodeWithTag("sheet-action-0").performClick()
        advance(100)
        compose.onNodeWithTag("action-sheet").assertIsDisplayed()
        assertEquals("Nothing chosen yet", result())
        assertTrue(HapticEvent.Confirm !in haptics.events)
    }

    @Test
    fun plainConfirmCommitsOnOneTapWithAConfirmHaptic() {
        show("dialog-confirm")
        compose.onNodeWithTag("sheet-action-0").performClick()
        advance(1000)
        assertEquals("Confirm: deleted", result())
        assertTrue(HapticEvent.Confirm in haptics.events)
    }

    @Test
    fun cancelRowAndScrimAndBackAllCancel() {
        show("dialog-sheet")
        compose.onNodeWithTag("sheet-cancel").performClick()
        advance(1000)
        advance(100)
        assertEquals("Sheet: cancelled", result())
        compose.onNodeWithTag("dialog-sheet").performClick()
        advance(1000)
        advance(100)
        compose.onNodeWithTag("overlay-scrim").performClick()
        advance(1000)
        advance(100)
        advance(100)
        compose.onNodeWithTag("action-sheet").assertDoesNotExist()
        assertEquals("Sheet: cancelled", result())
        compose.onNodeWithTag("dialog-sheet").performClick()
        advance(1000)
        advance(100)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1000)
        advance(100)
        advance(100)
        compose.onNodeWithTag("action-sheet").assertDoesNotExist()
    }

    @Test
    fun theSheetIsAtMost500DpWideWith56DpRowsAndTheCancelGroupSeparate() {
        show("dialog-sheet")
        val density = compose.activity.resources.displayMetrics.density
        val sheet = compose.onNodeWithTag("action-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(sheet.width <= 500f * density + 1f)
        val row = compose.onNodeWithTag("sheet-action-1").fetchSemanticsNode().boundsInRoot
        assertEquals(56f * density, row.height, 1f)
        val group = compose.onNodeWithTag("action-group").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("action-cancel-group").fetchSemanticsNode().boundsInRoot
        assertEquals("8 dp between groups", 8f * density, cancel.top - group.bottom, 1f)
        val window = compose.activity.resources.displayMetrics.heightPixels
        assertTrue("above the navigation bar", cancel.bottom <= window - 24f * density - 8f * density + 1f)
    }
}
