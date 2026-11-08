package dev.rovsti.catalog

import android.content.Context
import android.os.Vibrator
import android.view.View
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.HapticMode
import dev.rovsti.design.haptics.NoHaptics
import dev.rovsti.design.haptics.PlatformHaptics
import dev.rovsti.testing.RecordingHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HapticsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class RecordingView(
        context: Context,
    ) : View(context) {
        val performed = mutableListOf<Int>()

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            performed += feedbackConstant
            return true
        }
    }

    private fun playAll(haptics: dev.rovsti.design.haptics.RovstiHaptics) {
        haptics.tick()
        haptics.selection()
        haptics.toggle(true)
        haptics.toggle(false)
        haptics.textHandle()
        haptics.longPress()
        haptics.confirm()
        haptics.reject()
    }

    @Test
    fun offPlaysNothing() {
        val view = RecordingView(context)
        val vibrator = context.getSystemService(Vibrator::class.java)
        playAll(PlatformHaptics(view, vibrator) { HapticMode.Off })
        assertTrue(view.performed.isEmpty())
        assertTrue(!shadowOf(vibrator).isVibrating)
    }

    @Test
    fun systemUsesThePlatformConstants() {
        val view = RecordingView(context)
        playAll(PlatformHaptics(view, null) { HapticMode.System })
        assertEquals(
            listOf(
                android.view.HapticFeedbackConstants.CLOCK_TICK,
                android.view.HapticFeedbackConstants.SEGMENT_TICK,
                android.view.HapticFeedbackConstants.TOGGLE_ON,
                android.view.HapticFeedbackConstants.TOGGLE_OFF,
                android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.CONFIRM,
                android.view.HapticFeedbackConstants.REJECT,
            ),
            view.performed,
        )
    }

    @Test
    fun strongVibratesThePredefinedEffectAndSkipsTheView() {
        val view = RecordingView(context)
        val vibrator = context.getSystemService(Vibrator::class.java)
        PlatformHaptics(view, vibrator) { HapticMode.Strong }.longPress()
        assertTrue(view.performed.isEmpty())
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun theModeIsReadOnEveryEvent() {
        val view = RecordingView(context)
        var mode = HapticMode.Off
        val haptics = PlatformHaptics(view, null) { mode }
        haptics.tick()
        mode = HapticMode.System
        haptics.tick()
        assertEquals(1, view.performed.size)
    }

    @Test
    fun theRecordingFakeKeepsOrder() {
        val fake = RecordingHaptics()
        fake.confirm()
        fake.reject()
        assertEquals(listOf(HapticEvent.Confirm, HapticEvent.Reject), fake.events)
        NoHaptics.tick()
    }
}
