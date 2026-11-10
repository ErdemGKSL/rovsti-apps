package dev.rovsti.design.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticEventTest {
    @Test
    fun namedFunctionsMapToTheirEvents() {
        val played = mutableListOf<HapticEvent>()
        val haptics =
            object : RovstiHaptics {
                override fun play(event: HapticEvent) {
                    played += event
                }
            }
        haptics.tick()
        haptics.selection()
        haptics.toggle(true)
        haptics.toggle(false)
        haptics.textHandle()
        haptics.longPress()
        haptics.confirm()
        haptics.reject()
        haptics.keyboardTap()
        assertEquals(
            listOf(
                HapticEvent.Tick,
                HapticEvent.Selection,
                HapticEvent.ToggleOn,
                HapticEvent.ToggleOff,
                HapticEvent.TextHandle,
                HapticEvent.LongPress,
                HapticEvent.Confirm,
                HapticEvent.Reject,
                HapticEvent.KeyboardTap,
            ),
            played,
        )
    }
}
