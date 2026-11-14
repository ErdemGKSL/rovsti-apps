package dev.rosty.stops.home

import androidx.compose.ui.unit.dp
import dev.rosty.stops.sideRoomDp
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetRideTest {
    private val inset = 24f
    private val medium = 445f
    private val fade = 96f

    @Test fun belowTheInsetNothingMoves() {
        assertEquals(0f, sheetRideTranslation(0f, inset, medium), 0f)
        assertEquals(0f, sheetRideTranslation(inset, inset, medium), 0f)
        assertEquals(1f, sheetRideAlpha(0f, medium, fade), 0f)
    }

    @Test fun theChromeKeepsItsGapAboveThePeekSheet() {
        // A 120 dp peek sheet that reaches the window edge lifts the chrome by what it covers above the inset.
        assertEquals(-96f, sheetRideTranslation(120f, inset, medium), 0f)
        assertEquals(1f, sheetRideAlpha(120f, medium, fade), 0f)
    }

    @Test fun theRideStopsAtMediumAndThenFades() {
        assertEquals(-(medium - inset), sheetRideTranslation(medium, inset, medium), 0f)
        assertEquals(-(medium - inset), sheetRideTranslation(800f, inset, medium), 0f)
        assertEquals(1f, sheetRideAlpha(medium, medium, fade), 0f)
        assertEquals(0.5f, sheetRideAlpha(medium + fade / 2, medium, fade), 1e-4f)
        assertEquals(0f, sheetRideAlpha(medium + fade, medium, fade), 0f)
        assertEquals(0f, sheetRideAlpha(900f, medium, fade), 0f)
    }

    @Test fun aZeroFadeIsAStep() {
        assertEquals(1f, sheetRideAlpha(medium, medium, 0f), 0f)
        assertEquals(0f, sheetRideAlpha(medium + 1f, medium, 0f), 0f)
    }

    @Test fun theLimitKeepsTheBlockUnderTheTopChrome() {
        // Portrait: plenty of room, the medium detent is the limit.
        assertEquals(445f, rideMaxVisiblePx(445f, 891f, 84f, 160f, inset, 8f), 0f)
        // Landscape: 411 tall, the block may rise until it is 8 above the pill's bottom edge.
        val max = rideMaxVisiblePx(205f, 411f, 84f, 260f, inset, 8f)
        assertEquals(411f - 84f - 8f - 260f + inset, max, 0f)
        // With the lift at that limit, the block's top is exactly the gap under the pill.
        val lift = -sheetRideTranslation(max, inset, max)
        assertEquals(411f - 84f - 8f, 260f + lift, 1e-3f)
        // No room at all: no ride.
        assertEquals(inset, rideMaxVisiblePx(205f, 300f, 84f, 260f, inset, 8f), 0f)
    }

    @Test fun visibleHeightIsClamped() {
        assertEquals(0f, sheetVisiblePx(Float.MAX_VALUE, 891f), 0f)
        assertEquals(120f, sheetVisiblePx(771f, 891f), 0f)
        assertEquals(891f, sheetVisiblePx(-10f, 891f), 0f)
    }

    @Test fun sideRoomBesideTheCentredSheet() {
        assertEquals(165.5f, sideRoomDp(891.dp, 560.dp).value, 0.01f)
        assertEquals(0f, sideRoomDp(411.dp, 560.dp).value, 0f)
    }
}
