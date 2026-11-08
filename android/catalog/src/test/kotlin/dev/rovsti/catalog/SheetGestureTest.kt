package dev.rovsti.catalog

import androidx.activity.BackEventCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetMetrics
import dev.rovsti.design.overlay.backPreviewOffset
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** E1.13: sheet gestures on a manual clock, against a host built here so the controller and the sheet are in reach. */
@OptIn(ExperimentalTestApi::class)
class SheetGestureTest : ShotTest() {
    private val controller = OverlayController()
    private val haptics = RecordingHaptics()
    private var taps by mutableIntStateOf(0)
    private var focused = false
    private val focusRequester = FocusRequester()

    private val all = persistentListOf(Detent.Peek(), Detent.Medium, Detent.Large)

    private fun start() {
        composeFrame {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                OverlayHost(controller) {
                    Column(Modifier.fillMaxSize().background(Color(0xFFF2F2F7)).padding(top = 80.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clickable { taps++ }
                                .testTag("page-button"),
                        ) {
                            RovstiText("Taps: $taps")
                        }
                    }
                }
            }
        }
    }

    private fun present(
        initial: Detent,
        modal: Boolean = true,
        dismissible: Boolean = modal,
        detents: kotlinx.collections.immutable.ImmutableList<Detent> = all,
        withField: Boolean = false,
    ): SheetEntry {
        val entry =
            SheetEntry(
                title = "Test sheet",
                detents = detents,
                initialDetent = initial,
                modal = modal,
                dismissible = dismissible,
            ) { _ ->
                if (withField) {
                    Box(
                        Modifier
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .height(40.dp)
                            .fillMaxWidth()
                            .testTag("sheet-field"),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("sheet-list")) {
                    items((0 until 40).toList()) { i ->
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("item-$i")) { RovstiText("Row $i") }
                    }
                }
            }
        compose.runOnUiThread { controller.present(entry) }
        run(1500)
        return entry
    }

    /** Advances in 100 ms steps, so animations started by an effect get their frames. */
    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun top(entry: SheetEntry) = entry.sheet!!.offset.value

    private fun detent(
        entry: SheetEntry,
        index: Int,
    ) = entry.sheet!!.detentOffsets[index]

    private fun assertAt(
        entry: SheetEntry,
        expected: Float,
    ) = assertEquals(expected, top(entry), 3f)

    /** Drag the grabber [dp] px upwards (negative: downwards), slowly enough that the flick adds nothing. */
    private fun slowDrag(
        tag: String,
        dp: Float,
    ) {
        val ms = (abs(dp) / 70f * 1000f).toLong().coerceAtLeast(300L)
        compose.onNodeWithTag(tag).performTouchInput {
            val from = Offset(centerX, centerY)
            swipe(from, from + Offset(0f, -dp * density), ms)
        }
        run(1500)
    }

    private fun backEvent(progress: Float) = BackEventCompat(0.05f, 0.5f, progress, BackEventCompat.EDGE_LEFT)

    @Test
    fun swipingMovesTheSheetToEachDetent() {
        start()
        val entry = present(Detent.Peek())
        val peek = detent(entry, 2)
        val medium = detent(entry, 1)
        val large = detent(entry, 0)
        assertAt(entry, peek)
        slowDrag("sheet-grabber", (peek - medium) / density)
        assertAt(entry, medium)
        slowDrag("sheet-grabber", (medium - large) / density)
        assertAt(entry, large)
        slowDrag("sheet-grabber", -(medium - large) / density)
        assertAt(entry, medium)
        slowDrag("sheet-grabber", -(peek - medium) / density)
        assertAt(entry, peek)
    }

    @Test
    fun theDetentsAreAscendingFromThePageTop() {
        start()
        val entry = present(Detent.Medium)
        val o = entry.sheet!!.detentOffsets
        assertEquals(3, o.size)
        assertTrue(o[0] < o[1] && o[1] < o[2])
        assertEquals(compose.activity.resources.displayMetrics.heightPixels / 2f, o[1], 1f)
    }

    @Test
    fun aFastFlingDownDismissesAndAnUpwardOneExpands() {
        start()
        val entry = present(Detent.Medium)
        compose.onNodeWithTag("sheet-grabber").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY - 300f * density), 120)
        }
        run(1500)
        assertAt(entry, detent(entry, 0))
        compose.onNodeWithTag("sheet-grabber").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY + 500f * density), 100)
        }
        run(2000)
        compose.onNodeWithTag("sheet").assertDoesNotExist()
        assertEquals(0, controller.pushBackCount)
        assertFalse(controller.hasEntries)
    }

    @Test
    fun theListScrollsFirstAndHandsTheLeftoverToTheSheet() {
        start()
        val entry = present(Detent.Large)
        val large = detent(entry, 0)
        val medium = detent(entry, 1)

        fun itemTop() =
            compose
                .onNodeWithTag("item-5", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.top - top(entry)
        val before = itemTop()
        // Up inside the list: the list scrolls, the sheet stays.
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(Offset(centerX, centerY + 200f * density), Offset(centerX, centerY), 2800)
        }
        run(800)
        assertAt(entry, large)
        assertTrue("list scrolled", itemTop() < before - 100f * density)
        // Down by more than it scrolled: the list returns to its top and the rest pulls the sheet down.
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(Offset(centerX, centerY - 200f * density), Offset(centerX, centerY + 340f * density), 7700)
        }
        run(1500)
        assertEquals(before, itemTop(), 2f)
        assertAt(entry, medium)
    }

    @Test
    fun scrollingUpFirstLiftsASheetThatWasPulledDown() {
        start()
        val entry = present(Detent.Medium)
        val large = detent(entry, 0)

        fun itemTop() =
            compose
                .onNodeWithTag("item-0", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val before = itemTop() - top(entry)
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(Offset(centerX, centerY + 150f * density), Offset(centerX, centerY - 150f * density), 4300)
        }
        run(1500)
        assertAt(entry, large)
        // The sheet took the drag; the list did not scroll.
        assertEquals(before, itemTop() - top(entry), 2f)
    }

    @Test
    fun stackedSheetsKeepThePageBackUntilTheLastOneCloses() {
        start()
        val first = present(Detent.Medium)
        val second = present(Detent.Medium)
        assertEquals(2, controller.pushBackCount)
        assertEquals(1f, controller.pushBackProgress(), 0.02f)
        compose.runOnUiThread { controller.dismiss(second) }
        var lowest = 1f
        repeat(40) {
            advance(32)
            lowest = minOf(lowest, controller.pushBackProgress())
        }
        assertEquals(1, controller.pushBackCount)
        assertTrue("page stayed back: $lowest", lowest > 0.98f)
        assertEquals(second.state.targetState, false)
        compose.runOnUiThread { controller.dismiss(first) }
        run(2000)
        assertEquals(0, controller.pushBackCount)
        assertEquals(0f, controller.pushBackProgress(), 0.001f)
        assertFalse(controller.hasEntries)
    }

    @Test
    fun thePageScalesToNinetyOneAndBack() {
        start()
        val before = compose.onNodeWithTag("page-button", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val entry = present(Detent.Medium)
        val after = compose.onNodeWithTag("page-button", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(0.91f, after.width / before.width, 0.01f)
        compose.runOnUiThread { controller.dismiss(entry) }
        run(2000)
        val end = compose.onNodeWithTag("page-button", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before, end)
    }

    @Test
    fun predictiveBackAtFiftyPercentPreviewsThenCancelSettlesBack() {
        start()
        val entry = present(Detent.Medium)
        val start = top(entry)
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        advance(50)
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(backEvent(0.5f)) }
        advance(50)
        val away = entry.sheet!!.containerHeight
        assertEquals(backPreviewOffset(start, away, 0.5f), top(entry), 2f)
        assertTrue(top(entry) > start)
        assertTrue("35 % at most", top(entry) - start <= (away - start) * SheetMetrics.BackPreview + 1f)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        run(1500)
        assertAt(entry, start)
        assertTrue(entry.state.targetState)
    }

    @Test
    fun aCompletedBackGestureClosesTheSheet() {
        start()
        val entry = present(Detent.Medium)
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        advance(50)
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(backEvent(0.6f)) }
        advance(50)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        run(2000)
        assertFalse(entry.state.targetState)
        assertFalse(controller.hasEntries)
    }

    @Test
    fun backOnlyClosesTheTopSheet() {
        start()
        val first = present(Detent.Medium)
        val second = present(Detent.Medium)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        run(2000)
        assertTrue(first.state.targetState)
        assertFalse(second.state.targetState)
        assertEquals(1, controller.pushBackCount)
    }

    @Test
    fun aNonModalSheetPassesTapsThroughAndDrawsNoScrim() {
        start()
        val entry = present(Detent.Peek(), modal = false)
        compose.onNodeWithTag("overlay-scrim").assertDoesNotExist()
        assertEquals(0, controller.pushBackCount)
        assertEquals(0, taps)
        compose.onNodeWithTag("page-button").performClick()
        compose.onNodeWithTag("page-button").performClick()
        assertEquals(2, taps)
        // The page was not scaled.
        val b = compose.onNodeWithTag("page-button").fetchSemanticsNode().boundsInRoot
        assertEquals(
            compose.activity.resources.displayMetrics.widthPixels
                .toFloat(),
            b.width,
            1f,
        )
        // It cannot be flung away, it settles back on its lowest detent, and Back is left to the page.
        compose.onNodeWithTag("sheet-grabber").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY + 400f * density), 100)
        }
        run(1500)
        assertAt(entry, detent(entry, 2))
        assertTrue(entry.state.targetState)
        // At the medium detent it does take Back, and goes down to peek instead of closing.
        entry.sheet!!.animateToDetent(1)
        run(1500)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        run(1500)
        assertAt(entry, detent(entry, 2))
        assertTrue(entry.state.targetState)
    }

    @Test
    fun tappingTheScrimClosesAModalSheet() {
        start()
        val entry = present(Detent.Medium)
        compose.onNodeWithTag("overlay-scrim").performTouchInput { click(Offset(20f, 20f)) }
        run(2000)
        assertFalse(entry.state.targetState)
        assertFalse(controller.hasEntries)
    }

    @Test
    fun aNewDetentTicksOnceAndStayingPutDoesNot() {
        start()
        val entry = present(Detent.Peek())
        assertTrue(haptics.events.none { it == HapticEvent.Tick })
        slowDrag("sheet-grabber", (detent(entry, 2) - detent(entry, 1)) / density)
        assertEquals(1, haptics.events.count { it == HapticEvent.Tick })
        // A short pull that returns to the same detent is silent.
        slowDrag("sheet-grabber", 40f)
        assertAt(entry, detent(entry, 1))
        assertEquals(1, haptics.events.count { it == HapticEvent.Tick })
    }

    @Test
    fun closingClearsTheFocus() {
        start()
        val entry = present(Detent.Large, withField = true)
        compose.runOnUiThread { focusRequester.requestFocus() }
        advance(100)
        assertTrue(focused)
        compose.runOnUiThread { controller.dismiss(entry) }
        advance(100)
        assertFalse(focused)
        run(2000)
    }

    @Test
    fun dismissingFromCodeUsesTheSameExit() {
        start()
        val entry = present(Detent.Medium)
        val before = top(entry)
        compose.runOnUiThread { controller.dismiss(entry) }
        run(200)
        assertTrue("moving down", top(entry) > before + 5f)
        assertTrue("not finished", entry.state.targetState.not() && controller.hasEntries)
        run(2000)
        compose.onNodeWithTag("sheet").assertDoesNotExist()
        assertFalse(controller.hasEntries)
    }

    @Test
    fun aGrabbedSheetFollowsTheFingerWhileItIsStillSettling() {
        start()
        val entry = present(Detent.Peek())
        entry.sheet!!.animateToDetent(0)
        advance(100)
        compose.onNodeWithTag("sheet-grabber").performTouchInput {
            down(Offset(centerX, centerY))
            moveBy(Offset(0f, 60f * density))
        }
        run(100)
        val held = top(entry)
        // The spring toward Large was cancelled by the finger: the sheet stays where the finger put it.
        run(400)
        assertEquals(held, top(entry), 0.5f)
        assertTrue("well below Large: $held", held > detent(entry, 0) + 100f)
        compose.onNodeWithTag("sheet-grabber").performTouchInput { up() }
        run(2000)
        assertAt(entry, entry.sheet!!.detentOffsets.minByOrNull { abs(it - held) }!!)
    }
}
