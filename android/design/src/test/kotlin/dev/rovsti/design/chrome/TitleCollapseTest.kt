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
}

class TitleAlphaTest {
    private val fractions = (0..1000).map { it / 1000f }

    @Test
    fun bothStayInUnitRange() {
        for (f in fractions) {
            assertTrue(inlineTitleAlpha(f) in 0f..1f)
            assertTrue(largeTitleAlpha(f) in 0f..1f)
        }
    }

    @Test
    fun endpoints() {
        assertEquals(1f, largeTitleAlpha(0f), 0f)
        assertEquals(0f, inlineTitleAlpha(0f), 0f)
        assertEquals(0f, largeTitleAlpha(1f), 0f)
        assertEquals(1f, inlineTitleAlpha(1f), 0f)
    }

    @Test
    fun monotone() {
        fractions.zipWithNext().forEach { (a, b) ->
            assertTrue(inlineTitleAlpha(b) >= inlineTitleAlpha(a))
            assertTrue(largeTitleAlpha(b) <= largeTitleAlpha(a))
        }
    }

    @Test
    fun neverBothVisible() {
        for (f in fractions) {
            assertEquals("f=$f", 0f, inlineTitleAlpha(f) * largeTitleAlpha(f), 0f)
        }
    }

    @Test
    fun inlineIsInvisibleBeforeSixtyFivePercentAndHalfWayAtEightyTwoAndAHalf() {
        assertEquals(0f, inlineTitleAlpha(0.65f), 0f)
        assertEquals(0.5f, inlineTitleAlpha(0.825f), 1e-5f)
    }
}
