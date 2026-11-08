package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.design.controls.SegmentedControl
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E1.17: the segmented control on the LiquidBottomTabs pill. */
class SegmentedTest : ShotTest() {
    private val haptics = RecordingHaptics()
    private var selected = 0
    private var picks = mutableListOf<Int>()
    private var scroll = { 0 }
    private var enabledFlag by mutableStateOf(true)
    private val items = persistentListOf("Calls", "Messages", "Contacts")

    @Before
    fun debugOn() {
        BackdropDebug.clear()
        BackdropDebug.enabled = true
    }

    @After
    fun debugOff() {
        BackdropDebug.enabled = false
        BackdropDebug.clear()
    }

    private fun show(
        initial: Int = 0,
        scrolling: Boolean = false,
    ) {
        selected = initial
        picks = mutableListOf()
        enabledFlag = true
        composeFrame {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                val state = rememberScrollState()
                scroll = { state.value }
                var current by remember { mutableIntStateOf(initial) }
                Column(
                    Modifier
                        .padding(
                            horizontal = 16.dp,
                        ).then(if (scrolling) Modifier.verticalScroll(state) else Modifier),
                ) {
                    Box(Modifier.fillMaxWidth().height(if (scrolling) 300.dp else 20.dp))
                    SegmentedControl(
                        items,
                        current,
                        {
                            current = it
                            selected = it
                            picks += it
                        },
                        Modifier.testTag("segmented"),
                        enabled = enabledFlag,
                    )
                    RovstiText("neighbour", modifier = Modifier.testTag("neighbour"))
                    if (scrolling) Box(Modifier.fillMaxWidth().height(2000.dp))
                }
            }
        }
    }

    private fun control() = compose.onNodeWithTag("segmented")

    private fun segment(text: String) = compose.onNode(hasText(text), useUnmergedTree = false)

    /** x of the centre of slot [index] in the control, in px. */
    private fun slotX(index: Int): Float {
        val width =
            control()
                .fetchSemanticsNode()
                .size.width
                .toFloat()
        val inset = 2f * 3f
        val slot = (width - 2 * inset) / items.size
        return inset + slot * (index + 0.5f)
    }

    @Test
    fun tapSelectsTheSegmentWithOneHaptic() {
        show()
        control().performTouchInput {
            down(Offset(slotX(2), center.y))
            up()
        }
        advance(800)
        assertEquals(listOf(2), picks)
        assertEquals(listOf(HapticEvent.Selection), haptics.events)
        segment("Contacts").assertIsSelected()
        segment("Calls").assertIsNotSelected()
    }

    @Test
    fun tappingTheSelectedSegmentChangesNothing() {
        show(initial = 1)
        control().performTouchInput {
            down(Offset(slotX(1), center.y))
            up()
        }
        advance(800)
        assertTrue(picks.isEmpty())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun draggingThePillLandsOnTheNearestSegment() {
        show()
        control().performTouchInput { down(Offset(slotX(0), center.y)) }
        advance(200)
        // 1.4 slots to the right: nearest is slot 1.
        val slot = slotX(1) - slotX(0)
        control().performTouchInput { moveTo(Offset(slotX(0) + slot * 0.7f, center.y)) }
        advance(50)
        control().performTouchInput { moveTo(Offset(slotX(0) + slot * 1.4f, center.y)) }
        advance(50)
        control().performTouchInput { up() }
        advance(800)
        assertEquals(listOf(1), picks)
        assertEquals(1, haptics.events.size)
    }

    @Test
    fun draggingPastTheEndStopsOnTheLastSegment() {
        show()
        control().performTouchInput { down(Offset(slotX(0), center.y)) }
        advance(200)
        control().performTouchInput { moveTo(Offset(slotX(0) + 2000f, center.y)) }
        advance(50)
        control().performTouchInput { up() }
        advance(800)
        assertEquals(listOf(2), picks)
    }

    @Test
    fun aShortDragThatFallsBackOnTheSameSegmentSelectsNothing() {
        show()
        control().performTouchInput { down(Offset(slotX(0), center.y)) }
        advance(200)
        val slot = slotX(1) - slotX(0)
        control().performTouchInput { moveTo(Offset(slotX(0) + slot * 0.3f, center.y)) }
        advance(50)
        control().performTouchInput { up() }
        advance(800)
        assertTrue(picks.isEmpty())
    }

    @Test
    fun aVerticalSwipeInAScrollingPageScrollsAndDoesNotSelect() {
        show(scrolling = true)
        control().performTouchInput {
            down(Offset(slotX(2), center.y))
            moveBy(Offset(0f, -60f), delayMillis = 16)
            moveBy(Offset(0f, -60f), delayMillis = 16)
            moveBy(Offset(0f, -60f), delayMillis = 16)
            up()
        }
        advance(800)
        assertTrue("the page should have scrolled, was ${scroll()}", scroll() > 0)
        assertTrue(picks.isEmpty())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun aGestureCancelledMidDragGoesBack() {
        show(initial = 1)
        control().performTouchInput { down(Offset(slotX(1), center.y)) }
        advance(200)
        control().performTouchInput { moveTo(Offset(slotX(2), center.y)) }
        advance(50)
        enabledFlag = false
        advance(800)
        assertTrue(picks.isEmpty())
        segment("Messages").assertIsSelected()
    }

    @Test
    fun eachSegmentIsATabNodeAndExactlyOneIsSelected() {
        show(initial = 1)
        for ((i, text) in items.withIndex()) {
            val node = segment(text).fetchSemanticsNode()
            assertEquals(Role.Tab, node.config[SemanticsProperties.Role])
            assertEquals(i == 1, node.config[SemanticsProperties.Selected])
        }
        segment("Contacts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        advance(800)
        assertEquals(listOf(2), picks)
    }

    @Test
    fun theTouchTargetIsAtLeast48dpAndEverySegmentIs48dpWide() {
        show()
        assertTrue(control().heightDp() >= 48f)
        assertTrue(control().widthDp() / items.size >= 48f)
    }

    @Test
    fun pressingMovesNothingAround() {
        show()
        val rest = boundsOf("segmented")
        val neighbour = boundsOf("neighbour")
        control().performTouchInput { down(Offset(slotX(0), center.y)) }
        advance(300)
        assertEquals(rest, boundsOf("segmented"))
        assertEquals(neighbour, boundsOf("neighbour"))
        control().performTouchInput { moveTo(Offset(slotX(2), center.y)) }
        advance(100)
        assertEquals(rest, boundsOf("segmented"))
        assertEquals(neighbour, boundsOf("neighbour"))
        control().performTouchInput { up() }
        advance(800)
        assertEquals(rest, boundsOf("segmented"))
        assertEquals(neighbour, boundsOf("neighbour"))
    }

    @Test
    fun theHeldPillDrawsThroughTheRealBackdropEffect() {
        show()
        control().performTouchInput { down(Offset(slotX(0), center.y)) }
        advance(300)
        compose.onRoot().captureToImage()
        assertThumbDrewRealGlass("segmented.puck")
        onNodeWithTextExists()
    }

    private fun onNodeWithTextExists() {
        compose.onNodeWithText("Calls")
    }
}
