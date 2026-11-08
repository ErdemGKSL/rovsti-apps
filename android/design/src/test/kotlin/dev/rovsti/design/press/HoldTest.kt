package dev.rovsti.design.press

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTest {
    @Test
    fun holdIsOneHundredFiftyMilliseconds() {
        assertEquals(150L, HOLD_MS)
    }

    @Test
    fun justUnderTheHoldIsNotHeld() {
        assertFalse(held(downAtMs = 1_000, nowMs = 1_149, moved = false))
    }

    @Test
    fun exactlyTheHoldIsHeld() {
        assertTrue(held(downAtMs = 1_000, nowMs = 1_150, moved = false))
    }

    @Test
    fun aDragUnderWayIsHeldImmediately() {
        assertTrue(held(downAtMs = 1_000, nowMs = 1_001, moved = true))
    }
}
