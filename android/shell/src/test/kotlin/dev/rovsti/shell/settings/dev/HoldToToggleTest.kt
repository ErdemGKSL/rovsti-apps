package dev.rovsti.shell.settings.dev

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.core.log.DevMode
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.shell.settings.AboutPage
import dev.rovsti.shell.settings.HostedPage
import dev.rovsti.shell.settings.SampleAbout
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Holding About's Version row for 10 s switches developer mode; anything shorter, or a moving finger, does not. */
@Config(sdk = [35])
class HoldToToggleTest : ShotTest() {
    private val prefs =
        RuntimeEnvironment.getApplication().getSharedPreferences(DevMode.PREFS, Context.MODE_PRIVATE)

    @Before fun startOff() {
        prefs.edit().clear().commit()
    }

    @Test fun theTimingRuleIsInclusiveAtTheHoldTime() {
        assertFalse(holdReached(1_000, 10_999))
        assertTrue(holdReached(1_000, 11_000))
        assertTrue(holdReached(0, 20_000))
        assertFalse(holdReached(0, 99, holdMs = 100))
    }

    private fun page(
        mode: DevMode,
        haptics: RecordingHaptics,
    ) = composeFrame {
        CompositionLocalProvider(LocalDevMode provides mode, LocalRovstiHaptics provides haptics) {
            HostedPage { AboutPage(SampleAbout, onOpenLicences = {}) }
        }
    }

    @Test fun tenSecondsOnTheVersionRowTurnsItOnWithAHapticAndBackOff() {
        val mode = DevMode(prefs)
        val haptics = RecordingHaptics()
        page(mode, haptics)
        advance(300)
        val row = compose.onNodeWithTag(DevTags.Version, useUnmergedTree = true)
        row.performTouchInput { down(center) }
        advance(9_900)
        assertFalse("9.9 s is not enough", mode.enabled.value)
        advance(200)
        row.performTouchInput { up() }
        advance(300)
        assertTrue(mode.enabled.value)
        assertEquals(listOf(HapticEvent.Confirm), haptics.events)

        row.performTouchInput { down(center) }
        advance(10_100)
        row.performTouchInput { up() }
        advance(300)
        assertFalse(mode.enabled.value)
        assertEquals(listOf(HapticEvent.Confirm, HapticEvent.ToggleOff), haptics.events)
    }

    @Test fun liftingEarlyOrMovingAwayCancels() {
        val mode = DevMode(prefs)
        page(mode, RecordingHaptics())
        advance(300)
        val row = compose.onNodeWithTag(DevTags.Version, useUnmergedTree = true)
        row.performTouchInput { down(center) }
        advance(5_000)
        row.performTouchInput { up() }
        advance(6_000)
        assertFalse(mode.enabled.value)

        row.performTouchInput { down(center) }
        advance(1_000)
        row.performTouchInput {
            moveBy(
                androidx.compose.ui.geometry
                    .Offset(0f, 200f),
            )
        }
        advance(10_000)
        row.performTouchInput { up() }
        advance(300)
        assertFalse("a finger that moved off is a scroll, not a hold", mode.enabled.value)
    }
}
