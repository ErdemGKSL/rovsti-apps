package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.design.ios.component.IosSlider
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E1.17: the Convx-based slider. 20 dp thumb inset on each end, 3 px per dp. */
class SliderTest : ShotTest() {
    private var value = 0.4f
    private var values = mutableListOf<Float>()
    private var editing = mutableListOf<Boolean>()
    private var scroll = { 0 }

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
        range: ClosedFloatingPointRange<Float> = 0f..1f,
        initial: Float = 0.4f,
        step: Float? = null,
        enabled: Boolean = true,
        scrolling: Boolean = false,
    ) {
        value = initial
        values = mutableListOf()
        editing = mutableListOf()
        composeFrame {
            val state = rememberScrollState()
            scroll = { state.value }
            var v by remember { mutableFloatStateOf(initial) }
            Column(
                Modifier.padding(horizontal = 16.dp).then(if (scrolling) Modifier.verticalScroll(state) else Modifier),
            ) {
                Box(Modifier.fillMaxWidth().height(if (scrolling) 300.dp else 20.dp))
                IosSlider(
                    value = v,
                    onValueChange = {
                        v = it
                        value = it
                        values += it
                    },
                    valueRange = range,
                    step = step,
                    enabled = enabled,
                    onEditingChanged = { editing += it },
                    modifier = Modifier.testTag("slider"),
                )
                RovstiText("neighbour", modifier = Modifier.testTag("neighbour"))
                if (scrolling) Box(Modifier.fillMaxWidth().height(2000.dp))
            }
        }
    }

    private fun slider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    /** x in px of a value in the range 0..1: the thumb centre travels between the two 20 dp insets. */
    private fun xOf(fraction: Float): Float {
        val width =
            slider()
                .fetchSemanticsNode()
                .size.width
                .toFloat()
        val inset = 20f * 3f
        return inset + (width - 2 * inset) * fraction
    }

    @Test
    fun draggingTheThumbSetsTheValue() {
        show()
        val start = xOf(0.4f)
        slider().performTouchInput { down(Offset(start, center.y)) }
        advance(200)
        slider().performTouchInput { moveTo(Offset(xOf(0.7f), center.y)) }
        advance(50)
        slider().performTouchInput { up() }
        advance(800)
        assertEquals(0.7f, value, 0.02f)
        assertEquals(listOf(true, false), editing)
    }

    @Test
    fun aTapOnTheTrackJumpsThere() {
        show()
        slider().performTouchInput {
            down(Offset(xOf(0.9f), center.y))
            up()
        }
        advance(800)
        assertEquals(0.9f, value, 0.01f)
        assertEquals(listOf(true, false), editing)
    }

    @Test
    fun grabbingTheTrackMovesTheThumbUnderTheFinger() {
        show()
        slider().performTouchInput { down(Offset(xOf(0.1f), center.y)) }
        advance(200)
        assertEquals(0.1f, value, 0.01f)
        slider().performTouchInput { moveTo(Offset(xOf(0.2f), center.y)) }
        advance(50)
        slider().performTouchInput { up() }
        advance(800)
        assertEquals(0.2f, value, 0.02f)
    }

    @Test
    fun theValueStaysInsideTheRange() {
        show(range = 10f..20f, initial = 15f)
        slider().performTouchInput { down(Offset(xOf(0.5f), center.y)) }
        advance(200)
        slider().performTouchInput { moveTo(Offset(width + 400f, center.y)) }
        advance(50)
        slider().performTouchInput { up() }
        advance(800)
        assertEquals(20f, value, 1e-4f)
        assertTrue(values.all { it in 10f..20f })
    }

    @Test
    fun stepSnapsEveryReportedValue() {
        show(step = 0.25f, initial = 0.5f)
        slider().performTouchInput { down(Offset(xOf(0.5f), center.y)) }
        advance(200)
        slider().performTouchInput { moveTo(Offset(xOf(0.62f), center.y)) }
        advance(50)
        slider().performTouchInput { moveTo(Offset(xOf(0.8f), center.y)) }
        advance(50)
        slider().performTouchInput { up() }
        advance(800)
        assertTrue(
            "values $values",
            values.all { v ->
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).any {
                    kotlin.math.abs(it - v) <
                        1e-4f
                }
            },
        )
        assertEquals(0.75f, value, 1e-4f)
    }

    @Test
    fun semanticsExposeTheRangeAndSetProgress() {
        show(range = 0f..100f, initial = 40f, step = 10f)
        val info = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(40f, info.current, 1e-4f)
        assertEquals(0f..100f, info.range)
        assertEquals(9, info.steps)
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(63f) }
        advance(800)
        assertEquals(60f, value, 1e-4f)
        val after = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(60f, after.current, 1e-4f)
    }

    @Test
    fun aVerticalSwipeInAScrollingPageScrollsAndLeavesTheValue() {
        show(scrolling = true)
        slider().performTouchInput {
            down(Offset(xOf(0.4f), center.y))
            moveBy(Offset(0f, -60f), delayMillis = 16)
            moveBy(Offset(0f, -60f), delayMillis = 16)
            moveBy(Offset(0f, -60f), delayMillis = 16)
            up()
        }
        advance(800)
        assertTrue("the page should have scrolled, was ${scroll()}", scroll() > 0)
        assertTrue(values.isEmpty())
        assertTrue(editing.isEmpty())
    }

    @Test
    fun aHeldDragKeepsThePageStill() {
        show(scrolling = true)
        slider().performTouchInput { down(Offset(xOf(0.4f), center.y)) }
        advance(200)
        slider().performTouchInput { moveBy(Offset(60f, 50f)) }
        advance(50)
        slider().performTouchInput { up() }
        advance(800)
        assertEquals(0, scroll())
        assertTrue(value > 0.4f)
    }

    @Test
    fun aDisabledSliderIgnoresTouch() {
        show(enabled = false)
        slider().assertIsNotEnabled()
        slider().performTouchInput {
            down(Offset(xOf(0.9f), center.y))
            up()
        }
        advance(800)
        assertTrue(values.isEmpty())
    }

    @Test
    fun theTouchTargetIsAtLeast48dpTall() {
        show()
        assertTrue(slider().heightDp() >= 48f)
    }

    @Test
    fun pressingMovesNothingAround() {
        show()
        val rest = boundsOf("slider")
        val neighbour = boundsOf("neighbour")
        slider().performTouchInput { down(Offset(xOf(0.4f), center.y)) }
        advance(300)
        assertEquals(rest, boundsOf("slider"))
        assertEquals(neighbour, boundsOf("neighbour"))
        slider().performTouchInput { up() }
        advance(800)
        assertEquals(rest, boundsOf("slider"))
        assertEquals(neighbour, boundsOf("neighbour"))
    }

    @Test
    fun theHeldThumbDrawsThroughTheRealBackdropEffect() {
        show()
        slider().performTouchInput { down(Offset(xOf(0.4f), center.y)) }
        advance(300)
        compose.onRoot().captureToImage()
        assertThumbDrewRealGlass("slider.thumb")
        compose.onNodeWithTag("slider")
    }
}
