package dev.rovsti.design.press

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PressTargetsTest {
    private val w = 120f
    private val h = 40f

    @Test
    fun restGrowsBothAxesEquallyByTheLongerSide() {
        val t = pressTargets(w, h, 0f, 0f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        assertEquals(1f + 15f / 120f, t[0], 1e-6f)
        assertEquals(t[0], t[1], 0f)
        assertEquals(0f, t[2], 0f)
        assertEquals(0f, t[3], 0f)
    }

    @Test
    fun horizontalDragStretchesXAndShrinksY() {
        val t = pressTargets(w, h, 60f, 0f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        val base = 1f + 15f / 120f
        assertTrue(t[0] > base)
        assertTrue(t[1] < base)
        assertTrue(t[2] > 0f)
        assertEquals(0f, t[3], 1e-6f)
    }

    @Test
    fun verticalDragStretchesYAndShrinksX() {
        val t = pressTargets(w, h, 0f, 30f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        val base = 1f + 15f / 120f
        assertTrue(t[1] > base)
        assertTrue(t[0] < base)
        assertTrue(t[3] > 0f)
    }

    @Test
    fun shiftSaturatesBelowTheMaximum() {
        val far = pressTargets(w, h, 10_000_000f, 0f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        assertTrue(far[2] < 8f)
        assertTrue(far[2] > 7.9f)
        val farLeft = pressTargets(w, h, -10_000_000f, 0f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        assertEquals(-far[2], farLeft[2], 1e-4f)
    }

    @Test
    fun aSmallDragShiftsLessThanALargeOne() {
        val small = pressTargets(w, h, 5f, 0f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        val large = pressTargets(w, h, 80f, 0f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        assertTrue(abs(small[2]) < abs(large[2]))
    }

    @Test
    fun aDiagonalDragStretchesNothing() {
        // |nx| == |ny| on a square button: the stretch term is zero, only the shift remains.
        val t = pressTargets(40f, 40f, 20f, 20f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        assertEquals(t[0], t[1], 1e-5f)
        assertTrue(t[2] > 0f && t[3] > 0f)
    }

    @Test
    fun anEmptyElementStaysAtRest() {
        val t = pressTargets(0f, 0f, 10f, 10f, grow = 15f, stretchPx = 6f, maxShift = 8f)
        assertEquals(listOf(1f, 1f, 0f, 0f), t.toList())
    }
}
