package dev.rosty.stops.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** The map sheet's measured peek height ([mapSheetPeekPx]). */
class SheetPeekTest {
    private fun peek(
        content: Float,
        window: Float = 2000f,
    ) = mapSheetPeekPx(
        content,
        grabberPx = 13f,
        gapPx = 12f,
        bottomInsetPx = 24f,
        windowPx = window,
        minPx = 120f,
        maxGapPx = 48f,
    )

    @Test fun reachesTheLastPeekElementPlusTheGapAndTheSafeArea() {
        assertEquals(13f + 300f + 12f + 24f, peek(300f), 0f)
    }

    @Test fun isTheMinimumUntilMeasuredAndEndsUnderShortContent() {
        assertEquals(120f, peek(0f), 0f)
        // The hint alone: peek ends right under it, lower than the unmeasured height.
        assertEquals(13f + 50f + 12f + 24f, peek(50f), 0f)
    }

    @Test fun staysUnderTheMediumDetent() {
        assertEquals(1000f - 48f, peek(1500f), 0f)
        // A window too short for the cap keeps the unmeasured height at most.
        assertEquals(120f, peek(500f, window = 300f), 0f)
        assertEquals(13f + 50f + 12f + 24f, peek(50f, window = 300f), 0f)
    }
}
