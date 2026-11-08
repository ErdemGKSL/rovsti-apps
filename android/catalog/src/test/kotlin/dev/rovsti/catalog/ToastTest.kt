package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.overlay.ToastEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.15: timer, replace semantics, swipe, undo-once, placement above the measured bar, semantics. */
class ToastTest : ShotTest() {
    private val haptics = RecordingHaptics()
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun page() {
        composeFrame {
            CompositionLocalProvider(
                LocalRovstiHaptics provides haptics,
            ) { CatalogApp(initialPage = CatalogPage.Toasts) }
        }
    }

    private fun toasts() = compose.onAllNodesWithTag("toast").fetchSemanticsNodes().size

    private fun result() =
        compose
            .onNodeWithTag("toast-result", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    /** Runs [block] on the UI thread, then lets the snapshot apply and the entry compose before time moves on. */
    private fun <T> ui(block: () -> T): T {
        var out: T? = null
        compose.runOnUiThread { out = block() }
        advance(50)
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /** A bare host with no bar, for the placement and counting tests. */
    private fun bareHost(): OverlayController {
        val controller = OverlayController()
        composeFrame {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                OverlayHost(controller) { Box(Modifier.fillMaxSize()) }
            }
        }
        return controller
    }

    @Test
    fun aToastLeavesByItselfAtThreeSeconds() {
        page()
        tap("toast-show")
        advance(500)
        compose.onNodeWithTag("toast").assertIsDisplayed()
        advance(2300)
        assertEquals("still there at 2.8 s", 1, toasts())
        advance(400)
        advance(400)
        assertEquals("gone after 3 s and the exit", 0, toasts())
    }

    @Test
    fun aNewToastReplacesTheOneOnShow() {
        page()
        tap("toast-replace")
        advance(1000)
        assertEquals(1, toasts())
        val text =
            compose
                .onNodeWithTag("toast-text", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString { it.text }
        assertEquals("Saved to Contacts", text)
        // The replacement keeps its own full three seconds.
        advance(2000)
        assertEquals(1, toasts())
        advance(1200)
        assertEquals(0, toasts())
    }

    @Test
    fun theTimerRestartsForTheReplacement() {
        page()
        tap("toast-show")
        advance(2500)
        tap("toast-long")
        advance(1500)
        assertEquals("the first one's deadline passed, the second lives on", 1, toasts())
        advance(2000)
        assertEquals(0, toasts())
    }

    @Test
    fun swipingDownThrowsTheToastAwayBeforeItsTimeout() {
        page()
        tap("toast-show")
        advance(600)
        compose.onNodeWithTag("toast").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY + 90f * density), 120)
        }
        advance(300)
        advance(700)
        assertEquals(0, toasts())
    }

    @Test
    fun swipingSidewaysAlsoDismisses() {
        page()
        tap("toast-show")
        advance(600)
        compose.onNodeWithTag("toast").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX + 200f * density, centerY), 150)
        }
        advance(300)
        advance(700)
        assertEquals(0, toasts())
    }

    @Test
    fun aShortSlowDragSpringsBackAndTheToastStays() {
        page()
        tap("toast-show")
        advance(600)
        val before = compose.onNodeWithTag("toast").bounds
        compose.onNodeWithTag("toast").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY + 24f * density), 1500)
        }
        advance(800)
        assertEquals(1, toasts())
        val after = compose.onNodeWithTag("toast").bounds
        assertEquals(before.top, after.top, 1.5f)
        assertEquals(before.left, after.left, 1.5f)
    }

    @Test
    fun theTimerHoldsWhileAFingerIsOnTheToast() {
        page()
        tap("toast-show")
        advance(600)
        compose.onNodeWithTag("toast").performTouchInput { down(center) }
        advance(4000)
        assertEquals("held by the finger", 1, toasts())
        compose.onNodeWithTag("toast").performTouchInput { up() }
        advance(3500)
        assertEquals(0, toasts())
    }

    @Test
    fun undoRunsOnceAndDismisses() {
        page()
        tap("toast-undo")
        advance(600)
        assertEquals("Deleted", result())
        tap("toast-action")
        tap("toast-action")
        advance(1000)
        assertEquals("Restored", result())
        assertEquals(0, toasts())
        assertEquals(1, haptics.events.count { it == HapticEvent.Confirm })
    }

    @Test
    fun theActionRunsExactlyOnceEvenWhenTappedTwiceInTheSameFrame() {
        var count = 0
        val controller = bareHost()
        ui { controller.showUndo("Contact deleted", onUndo = { count++ }) }
        advance(600)
        tap("toast-action")
        tap("toast-action")
        advance(1000)
        assertEquals(1, count)
    }

    @Test
    fun undoDoesNothingOnceTheFiveSecondsHavePassed() {
        var count = 0
        val controller = bareHost()
        lateinit var entry: ToastEntry
        entry = ui { controller.showUndo("Contact deleted", onUndo = { count++ }) }
        advance(4800)
        assertEquals(1, toasts())
        assertTrue("still live at 4.8 s", entry.state.targetState)
        // Step to the frame where the five seconds run out: the pill is fading, still composed, and its action is dead.
        var steps = 0
        while (entry.state.targetState && steps++ < 40) advance(16)
        assertFalse(entry.state.targetState)
        assertEquals("fading, not yet removed", 1, toasts())
        tap("toast-action")
        advance(1000)
        assertEquals(0, count)
        assertEquals(0, toasts())
    }

    @Test
    fun anUndoPillReplacedByAToastCannotUndoAnyMore() {
        var count = 0
        val controller = bareHost()
        ui { controller.showUndo("Deleted", onUndo = { count++ }) }
        advance(600)
        ui { controller.showToast("Something else") }
        advance(20)
        compose
            .onAllNodesWithTag("toast-action")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.let { tap("toast-action") }
        advance(1000)
        assertEquals(0, count)
    }

    @Test
    fun theToastSitsAtLeastEightDpAboveTheTabBar() {
        page()
        tap("toast-show")
        advance(700)
        val bar = compose.onNodeWithTag("tab-surface").bounds
        val toast = compose.onNodeWithTag("toast").bounds
        assertTrue("toast ${toast.bottom} bar ${bar.top}", toast.bottom <= bar.top - 8f * density + 1f)
        assertEquals("and no further than that", bar.top - 8f * density, toast.bottom, 2f)
    }

    @Test
    fun theLongToastStaysAboveTheBarAndKeepsTwoLines() {
        page()
        tap("toast-long")
        advance(700)
        val bar = compose.onNodeWithTag("tab-surface").bounds
        val toast = compose.onNodeWithTag("toast").bounds
        assertTrue(toast.bottom <= bar.top - 8f * density + 1f)
        val text = compose.onNodeWithTag("toast-text", useUnmergedTree = true).bounds
        assertTrue("text ${text.height} px", text.height <= 2 * 20f * density + 1f)
        assertTrue(toast.width <= 420f * density + 1f)
    }

    @Test
    fun withoutABarItSitsEightDpAboveTheNavigationInset() {
        val controller = bareHost()
        ui { controller.showToast("Copied") }
        advance(700)
        val toast = compose.onNodeWithTag("toast").bounds
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(window.bottom - (24f + 8f) * density, toast.bottom, 2f)
    }

    @Test
    fun theToastHasAPoliteLiveRegionAndIsNotModal() {
        page()
        tap("toast-show")
        advance(700)
        val config = compose.onNodeWithTag("toast").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, config.getOrNull(SemanticsProperties.LiveRegion))
        // The page behind keeps its semantics and takes touches.
        compose.onNodeWithTag("toast-show").assertIsDisplayed()
        tap("toast-undo")
        advance(100)
        assertEquals("Deleted", result())
    }

    @Test
    fun backGoesToThePageNotToTheToast() {
        val controller = bareHost()
        ui { controller.showToast("Copied") }
        advance(600)
        assertFalse("the host leaves Back alone", controller.dismissTop())
        assertEquals(1, toasts())
        assertNotNull(compose.onNodeWithTag("toast").fetchSemanticsNode())
    }
}
