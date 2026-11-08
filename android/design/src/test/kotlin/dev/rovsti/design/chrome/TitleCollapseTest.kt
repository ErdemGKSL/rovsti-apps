package dev.rovsti.design.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCollapseTest {
    @Test
    fun zeroAtTheTop() {
        assertEquals(0f, titleCollapseFraction(0, 0, 80f), 0f)
    }

    @Test
    fun oneOncePastTheTitleItem() {
        assertEquals(1f, titleCollapseFraction(1, 0, 80f), 0f)
        assertEquals(1f, titleCollapseFraction(7, 123, 80f), 0f)
    }

    @Test
    fun linearInBetween() {
        assertEquals(0.25f, titleCollapseFraction(0, 20, 80f), 1e-6f)
        assertEquals(0.5f, titleCollapseFraction(0, 40, 80f), 1e-6f)
        assertEquals(0.75f, titleCollapseFraction(0, 60, 80f), 1e-6f)
    }

    @Test
    fun clampedWhenTheOffsetOvershootsTheHeight() {
        assertEquals(1f, titleCollapseFraction(0, 200, 80f), 0f)
        assertEquals(0f, titleCollapseFraction(0, -5, 80f), 0f)
    }

    @Test
    fun zeroOrNegativeHeightIsSafe() {
        assertEquals(0f, titleCollapseFraction(0, 0, 0f), 0f)
        assertEquals(1f, titleCollapseFraction(0, 10, 0f), 0f)
        assertEquals(1f, titleCollapseFraction(0, 10, -40f), 0f)
        assertTrue(titleCollapseFraction(0, 0, Float.NaN).let { it in 0f..1f })
    }

    @Test
    fun topScrimPeaksAtPointFortyTwoAndFallsToZero() {
        val stops = topScrimStops(barFraction = 0.55f)
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(ScaffoldSpec.ScrimPeak, stops.first().second, 1e-6f)
        assertEquals(0.42f, stops.maxOf { it.second }, 1e-6f)
        assertEquals(1f, stops.last().first, 1e-6f)
        assertEquals(0f, stops.last().second, 0f)
        // Positions never go back and the share never rises after the bar.
        assertTrue(stops.zipWithNext().all { (a, b) -> b.first >= a.first && b.second <= a.second + 1e-6f })
    }
}
