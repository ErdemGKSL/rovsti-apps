package dev.rovsti.design.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5.6 step 5: the slide-to-answer track. The clock is manual, so every settle is advanced by hand. */
class SlideToAnswerTest : ShotTest() {
    private var answers = 0
    private val haptics = RecordingHaptics()

    private fun show(rtl: Boolean = false) {
        composeFrame(rtl = rtl) {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    SlideToAnswer("slide to answer", { answers++ }, Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    private val track get() = compose.onNodeWithTag(SlideToAnswerTags.Track)
    private val handle get() = compose.onNodeWithTag(SlideToAnswerTags.Handle, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot

    /** The handle's progress along the track, measured from its on-screen position (RTL counts from the right). */
    private fun progress(rtl: Boolean = false): Float {
        val t = track.bounds()
        val h = handle.bounds()
        val inset = (t.height - h.width) / 2
        val travel = t.width - h.width - 2 * inset
        val moved = if (rtl) t.right - inset - h.right else h.left - t.left - inset
        return moved / travel
    }

    private fun travelPx(): Float {
        val t = track.bounds()
        val h = handle.bounds()
        return t.width - h.width - (t.height - h.width)
    }

    /** Presses the handle and moves it until it sits at [target] (a touch slop eats the first pixels). */
    private fun dragTo(
        target: Float,
        rtl: Boolean = false,
        release: Boolean = true,
        press: Boolean = true,
    ) {
        val sign = if (rtl) -1f else 1f
        if (press) handle.performTouchInput { down(center) }
        repeat(4) {
            val missing = (target - progress(rtl)) * travelPx()
            if (kotlin.math.abs(missing) > 0.5f) {
                handle.performTouchInput { moveBy(Offset(missing * sign, 0f)) }
                advance(32)
            }
        }
        if (release) {
            // Hold still so the release carries no velocity.
            handle.performTouchInput {
                advanceEventTime(400)
                up()
            }
            advance(1_500)
        }
    }

    @Test fun releasingBelowEightyPercentSpringsBackWithoutAnswering() {
        show()
        dragTo(0.79f, release = false)
        assertEquals(0.79f, progress(), 0.01f)
        handle.performTouchInput {
            advanceEventTime(400)
            up()
        }
        advance(1_500)
        assertEquals(0, answers)
        assertEquals(0f, progress(), 0.01f)
    }

    @Test fun releasingAboveEightyPercentAnswersOnceAndParksTheHandleAtTheEnd() {
        show()
        dragTo(0.81f)
        assertEquals(1, answers)
        assertEquals(1f, progress(), 0.01f)
        // Nothing more happens, whatever the finger does next.
        handle.performTouchInput {
            down(center)
            up()
        }
        advance(500)
        assertEquals(1, answers)
    }

    @Test fun theHandleFollowsTheFingerOneToOne() {
        show()
        val before = handle.bounds().left
        dragTo(0.5f, release = false)
        val moved = handle.bounds().left - before
        assertEquals(0.5f * travelPx(), moved, 1f)
        handle.performTouchInput { up() }
    }

    @Test fun aFastFlingFromSixtyPercentAnswers() {
        show()
        dragTo(0.6f, release = false)
        handle.performTouchInput {
            // A quick flick onward from where the finger is: 60 px in 30 ms is a high velocity.
            moveBy(Offset(20f, 0f), delayMillis = 10)
            moveBy(Offset(20f, 0f), delayMillis = 10)
            moveBy(Offset(20f, 0f), delayMillis = 10)
            up()
        }
        advance(1_500)
        assertTrue("progress ${progress()}", progress() > 0.99f)
        assertEquals(1, answers)
    }

    @Test fun aSlowFlingFromSixtyPercentSpringsBack() {
        show()
        dragTo(0.6f)
        assertEquals(0, answers)
        assertEquals(0f, progress(), 0.01f)
    }

    @Test fun theThresholdTickPlaysOnceAndRearmsBelowSeventyPercent() {
        show()
        dragTo(0.85f, release = false)
        assertEquals(listOf(HapticEvent.ThresholdTick), haptics.events)
        // Wobbling between 70 % and 80 % does not re-arm; dropping below 70 % does.
        dragTo(0.75f, release = false, press = false)
        dragTo(0.85f, release = false, press = false)
        assertEquals(1, haptics.events.size)
        dragTo(0.6f, release = false, press = false)
        dragTo(0.85f, release = false, press = false)
        assertEquals(2, haptics.events.size)
        handle.performTouchInput {
            advanceEventTime(400)
            up()
        }
    }

    @Test fun inRightToLeftTheHandleStartsOnTheRightAndTheDragRunsLeft() {
        show(rtl = true)
        assertTrue(handle.bounds().center.x > track.bounds().center.x)
        dragTo(0.81f, rtl = true)
        assertEquals(1, answers)
        assertTrue(handle.bounds().center.x < track.bounds().center.x)
    }

    @Test fun theAccessibilityActionAnswers() {
        show()
        val actions =
            track
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
        assertEquals(listOf("slide to answer"), actions.map { it.label })
        assertEquals(
            "slide to answer",
            track
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                ?.single(),
        )
        compose.runOnUiThread { actions.single().action() }
        advance(1_000)
        assertEquals(1, answers)
        track.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, answers)
    }
}
