package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.14 alert: modal behaviour, back with and without a cancel action, the 150 ms exit, buttons by measurement. */
class AlertTest : ShotTest() {
    private val haptics = RecordingHaptics()

    private fun open() {
        composeFrame {
            CompositionLocalProvider(
                LocalRovstiHaptics provides haptics,
            ) { CatalogApp(initialPage = CatalogPage.Dialogs) }
        }
    }

    private fun show(tag: String) {
        open()
        compose.onNodeWithTag(tag).performClick()
        advance(800)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(40)
        advance(1000)
    }

    private fun result() =
        compose
            .onNodeWithTag("dialog-result", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    @Test
    fun focusMovesInsideAndThePageSemanticsAreClearedAndTheTitleIsAnnounced() {
        show("dialog-alert")
        compose.onNodeWithTag("alert").assertIsFocused()
        compose.onNodeWithTag("dialog-alert").assertDoesNotExist()
        compose
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Remove from favourites?"))
            .assertExists()
    }

    @Test
    fun backWithACancelActionRunsItAndClosesTheAlert() {
        show("dialog-alert")
        back()
        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertEquals("Alert: cancelled", result())
    }

    @Test
    fun backWithoutACancelActionDoesNothing() {
        show("dialog-alert-ok")
        back()
        compose.onNodeWithTag("alert").assertIsDisplayed()
        // The catalog page behind did not get Back either: it is still the dialogs page.
        compose.onNodeWithTag("home-pages").assertDoesNotExist()
        compose.onNodeWithTag("alert-button-OK").performClick()
        advance(1000)
        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertEquals("Alert: ok", result())
    }

    @Test
    fun aTapOnTheScrimDoesNothing() {
        show("dialog-alert")
        compose.onNodeWithTag("overlay-scrim").performClick()
        advance(1000)
        compose.onNodeWithTag("alert").assertIsDisplayed()
    }

    @Test
    fun theExitPlaysFor150MsThenTheAlertIsGone() {
        show("dialog-alert")
        compose.onNodeWithTag("alert-button-Remove").performClick()
        advance(60)
        compose.onNodeWithTag("alert").assertExists()
        advance(300)
        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertEquals("Alert: removed", result())
    }

    @Test
    fun theAlertIs270DpWideAtLeast110DpTallAndTwoButtonsShareARow() {
        show("dialog-alert")
        val density = compose.activity.resources.displayMetrics.density
        val card = compose.onNodeWithTag("alert").fetchSemanticsNode().boundsInRoot
        assertEquals(270f * density, card.width, 1f)
        assertTrue(card.height >= 110f * density - 1f)
        val a = compose.onNodeWithTag("alert-button-Cancel").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("alert-button-Remove").fetchSemanticsNode().boundsInRoot
        assertEquals("one row", a.top, b.top, 1f)
        assertEquals(a.height, b.height, 1f)
        assertTrue(a.height >= 44f * density - 1f)
    }

    @Test
    fun threeButtonsStackInAColumn() {
        show("dialog-alert-3")
        val a = compose.onNodeWithTag("alert-button-WhatsApp call").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("alert-button-Phone call").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("alert-button-Cancel").fetchSemanticsNode().boundsInRoot
        assertTrue(a.bottom <= b.top + 1f && b.bottom <= c.top + 1f)
        assertEquals(a.left, b.left, 1f)
    }

    @Test
    fun aLargeFontKeepsTheButtonsApart() {
        composeFrame(fontScale = 2f) {
            CompositionLocalProvider(
                LocalRovstiHaptics provides haptics,
            ) { CatalogApp(initialPage = CatalogPage.Dialogs) }
        }
        compose.onNodeWithTag("dialog-alert").performClick()
        advance(800)
        val a = compose.onNodeWithTag("alert-button-Cancel").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("alert-button-Remove").fetchSemanticsNode().boundsInRoot
        assertTrue("never side by side overlapping", a.bottom <= b.top + 1f || a.right <= b.left + 1f)
    }
}
