package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.center
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.ios.component.IosToggle
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRowSwitch
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E1.17: the Convx-based switch. A screen px is 3 per dp here (xxhdpi), so the 20 dp thumb travel is 60 px and a drag
 * past half is more than 30 px.
 */
class ToggleTest : ShotTest() {
    private val haptics = RecordingHaptics()
    private var checked = false
    private var changes = mutableListOf<Boolean>()
    private var scroll = { 0 }
    private var enabledFlag by mutableStateOf(true)

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
        initial: Boolean = false,
        enabled: Boolean = true,
        scrolling: Boolean = false,
    ) {
        checked = initial
        enabledFlag = enabled
        changes = mutableListOf()
        composeFrame {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                val state = rememberScrollState()
                scroll = { state.value }
                var on by remember { mutableStateOf(initial) }
                Column(if (scrolling) Modifier.verticalScroll(state) else Modifier) {
                    Box(Modifier.fillMaxWidth().height(if (scrolling) 300.dp else 20.dp))
                    IosToggle(
                        checked = on,
                        onCheckedChange = {
                            on = it
                            checked = it
                            changes += it
                        },
                        enabled = enabledFlag,
                        modifier = Modifier.testTag("toggle"),
                    )
                    RovstiText("neighbour", modifier = Modifier.testTag("neighbour"))
                    if (scrolling) Box(Modifier.fillMaxWidth().height(2000.dp))
                }
            }
        }
    }

    private fun toggle() = compose.onNodeWithTag("toggle")

    @Test
    fun tapTurnsItOnWithOneHaptic() {
        show()
        toggle().assertIsOff()
        toggle().performTouchInput {
            down(center)
            up()
        }
        advance(800)
        toggle().assertIsOn()
        assertEquals(listOf(true), changes)
        assertEquals(listOf(HapticEvent.ToggleOn), haptics.events)
    }

    @Test
    fun tapTurnsItOffAgain() {
        show(initial = true)
        toggle().assertIsOn()
        toggle().performClick()
        advance(800)
        toggle().assertIsOff()
        assertEquals(listOf(HapticEvent.ToggleOff), haptics.events)
    }

    @Test
    fun dragPastHalfTurnsItOn() {
        show()
        toggle().performTouchInput { down(center) }
        advance(200)
        toggle().performTouchInput { moveBy(Offset(20f, 0f)) }
        advance(50)
        toggle().performTouchInput { moveBy(Offset(30f, 0f)) }
        advance(50)
        toggle().performTouchInput { up() }
        advance(800)
        toggle().assertIsOn()
        assertEquals(listOf(true), changes)
        assertEquals(1, haptics.events.size)
    }

    @Test
    fun dragNotPastHalfLeavesItOff() {
        show()
        toggle().performTouchInput { down(center) }
        advance(200)
        toggle().performTouchInput { moveBy(Offset(20f, 0f)) }
        advance(50)
        toggle().performTouchInput { up() }
        advance(800)
        toggle().assertIsOff()
        assertTrue(changes.isEmpty())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun dragBackBeforeReleaseKeepsItOn() {
        show(initial = true)
        toggle().performTouchInput { down(center) }
        advance(200)
        toggle().performTouchInput { moveBy(Offset(-50f, 0f)) }
        advance(50)
        toggle().performTouchInput { up() }
        advance(800)
        toggle().assertIsOff()
        assertEquals(listOf(false), changes)
    }

    @Test
    fun aVerticalSwipeInAScrollingPageScrollsAndDoesNotToggle() {
        show(scrolling = true)
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(0f, -60f), delayMillis = 16)
            moveBy(Offset(0f, -60f), delayMillis = 16)
            moveBy(Offset(0f, -60f), delayMillis = 16)
            up()
        }
        advance(800)
        assertTrue("the page should have scrolled, was ${scroll()}", scroll() > 0)
        toggle().assertIsOff()
        assertTrue(changes.isEmpty())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun aHeldTouchKeepsTheSwitchAndTheDragTurnsItOn() {
        show(scrolling = true)
        toggle().performTouchInput { down(center) }
        advance(200)
        toggle().performTouchInput { moveBy(Offset(60f, 40f)) }
        advance(50)
        toggle().performTouchInput { up() }
        advance(800)
        assertEquals("the page must not scroll under a held switch", 0, scroll())
        toggle().assertIsOn()
    }

    @Test
    fun aTapThatEndsFarAboveTheSwitchDoesNothing() {
        show(scrolling = true)
        toggle().performTouchInput { down(center) }
        advance(200)
        // Held, then lifted 120 px (40 dp) above the switch without moving sideways: not a drag, not near.
        toggle().performTouchInput { moveBy(Offset(0f, -200f)) }
        advance(50)
        toggle().performTouchInput { up() }
        advance(800)
        toggle().assertIsOff()
        assertTrue(changes.isEmpty())
    }

    /**
     * Compose reports a window cancel as a plain release, so the case that can be told apart is the gesture coroutine
     * being cancelled mid-hold (here: the switch is disabled by its screen). The thumb must go back and nothing changes.
     */
    @Test
    fun aGestureCancelledMidDragRestoresTheSwitch() {
        show(initial = true)
        toggle().performTouchInput { down(center) }
        advance(200)
        toggle().performTouchInput { moveBy(Offset(-50f, 0f)) }
        advance(50)
        enabledFlag = false
        advance(800)
        toggle().assertIsOn()
        assertTrue(changes.isEmpty())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun semanticsAreASwitchWithAState_andAClickAction() {
        show()
        val node = toggle().fetchSemanticsNode()
        assertEquals(Role.Switch, node.config[SemanticsProperties.Role])
        toggle().assertIsEnabled().assertIsOff()
        toggle().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        advance(800)
        toggle().assertIsOn()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun aDisabledSwitchIgnoresTouchAndIsDisabledInSemantics() {
        show(enabled = false)
        toggle().assertIsNotEnabled()
        toggle().performTouchInput {
            down(center)
            up()
        }
        advance(800)
        toggle().assertIsOff()
        assertTrue(changes.isEmpty())
    }

    @Test
    fun theTouchTargetIsAtLeast48dp() {
        show()
        assertTrue(toggle().heightDp() >= 48f)
        assertTrue(toggle().widthDp() >= 48f)
    }

    @Test
    fun pressingMovesNothingAround() {
        show()
        val rest = boundsOf("toggle")
        val neighbour = boundsOf("neighbour")
        toggle().performTouchInput { down(center) }
        advance(300)
        assertEquals(rest, boundsOf("toggle"))
        assertEquals(neighbour, boundsOf("neighbour"))
        toggle().performTouchInput { moveBy(Offset(40f, 0f)) }
        advance(100)
        assertEquals(rest, boundsOf("toggle"))
        assertEquals(neighbour, boundsOf("neighbour"))
        toggle().performTouchInput { up() }
        advance(800)
        assertEquals(rest, boundsOf("toggle"))
        assertEquals(neighbour, boundsOf("neighbour"))
    }

    @Test
    fun theHeldThumbDrawsThroughTheRealBackdropEffect() {
        show()
        toggle().performTouchInput { down(center) }
        advance(300)
        compose.onRoot().captureToImage()
        assertThumbDrewRealGlass("toggle.thumb")
        assertFalse(BackdropDebug.snapshot().isEmpty())
    }

    @Test
    fun aRowSwitchIsOneSwitchNodeAndTheThumbTogglesIt() {
        var on = false
        composeFrame {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                var state by remember { mutableStateOf(false) }
                InsetGroupedSection {
                    row {
                        ListRowSwitch("Read receipts", state, {
                            state = it
                            on = it
                        }, modifier = Modifier.testTag("row"))
                    }
                }
            }
        }
        compose.onNodeWithTag("row").assertIsOff()
        assertEquals(Role.Switch, compose.onNodeWithTag("row").fetchSemanticsNode().config[SemanticsProperties.Role])
        // The row is tall enough for a 48 dp target and the switch inside it is hidden from accessibility.
        assertTrue(compose.onNodeWithTag("row").heightDp() >= 56f)
        compose.onNodeWithTag("row").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        advance(800)
        compose.onNodeWithTag("row").assertIsOn()
        assertTrue(on)
    }
}
