package dev.rovsti.design.haptics

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemVibrator

/**
 * A tab switch crashed release builds with `SecurityException: vibrate: ... nor current process has
 * android.permission.VIBRATE`. Whatever the vibrator or the view throws, a haptic event must play nothing, not crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PlatformHapticsGuardTest.DeniedVibrator::class])
class PlatformHapticsGuardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val vibrator: Vibrator = context.getSystemService(Vibrator::class.java)

    /** The system vibrator of an app without the `VIBRATE` grant: every vibration throws, as on the owner's phone. */
    @Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
    class DeniedVibrator : ShadowSystemVibrator() {
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        protected fun vibrate(
            uid: Int,
            opPkg: String?,
            effect: VibrationEffect,
            reason: String?,
            attributes: VibrationAttributes?,
        ) {
            calls++
            throw failure
        }

        companion object {
            var calls = 0
            var failure: RuntimeException =
                SecurityException("vibrate: Neither user 10570 nor current process has VIBRATE.")
        }
    }

    /** A view whose haptic feedback throws [error], like an in-process vibrator on Android 15+ without the grant. */
    private class ThrowingView(
        context: Context,
        private val error: RuntimeException,
    ) : View(context) {
        var calls = 0

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            calls++
            throw error
        }
    }

    private val noPermission = SecurityException("vibrate: Neither user 10570 nor current process has VIBRATE.")

    @Test
    fun theShadowReallyThrows() {
        DeniedVibrator.failure = noPermission
        assertThrows(SecurityException::class.java) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    @Test
    fun strongModeSurvivesAMissingVibratePermission() {
        DeniedVibrator.calls = 0
        DeniedVibrator.failure = noPermission
        val haptics = PlatformHaptics(View(context), vibrator) { HapticMode.Strong }
        haptics.tick()
        haptics.selection()
        haptics.longPress()
        assertEquals(3, DeniedVibrator.calls)
    }

    @Test
    fun strongModeSurvivesAVibratorInABadState() {
        DeniedVibrator.calls = 0
        DeniedVibrator.failure = IllegalStateException("vibrator service gone")
        PlatformHaptics(View(context), vibrator) { HapticMode.Strong }.confirm()
        assertEquals(1, DeniedVibrator.calls)
    }

    @Test
    fun systemModeSurvivesAThrowingView() {
        val view = ThrowingView(context, noPermission)
        val haptics = PlatformHaptics(view, null) { HapticMode.System }
        haptics.tick()
        haptics.toggle(true)
        assertEquals(2, view.calls)
    }

    @Test
    fun theGuardReportsWhetherTheBlockRan() {
        assertTrue(guardHaptic {})
        assertFalse(guardHaptic { throw noPermission })
        assertFalse(guardHaptic { throw IllegalStateException() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherErrorsStillSurface() {
        guardHaptic { throw IllegalArgumentException("a programming error is not swallowed") }
    }
}
