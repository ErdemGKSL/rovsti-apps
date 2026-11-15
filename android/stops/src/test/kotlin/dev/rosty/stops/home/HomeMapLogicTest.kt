package dev.rosty.stops.home

import dev.rosty.platform.location.Fix
import dev.rosty.stops.SampleStops
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMapLogicTest {
    private val fix = Fix(41.1, 29.2, 20.0, 0L)

    @Test fun aTripInProgressWinsTheFirstView() {
        val state = HomeState(trip = TripSummary("A – B", SampleStops.pierW, SampleStops.pierE))
        // The gate, so the view fits its whole circle and not only the two ends.
        assertEquals(FirstView.Gate(SampleStops.pierW.coord, SampleStops.pierE.coord), firstView(state, fix))
    }

    @Test fun thenMyPositionThenTheSavedStopsThenTheDefault() {
        assertEquals(FirstView.Centre(41.1, 29.2), firstView(HomeState(), fix))
        val saved = HomeState(saved = SampleStops.saved.stops.toImmutableList())
        val fit = firstView(saved, null) as FirstView.Fit
        assertEquals(8, fit.latLon.size)
        assertEquals(FirstView.Default, firstView(HomeState(), null))
        assertEquals(FirstView.Default, firstView(HomeState(), Fix(Double.NaN, 1.0, 1.0, 0)))
    }

    @Test fun toastsClearTheHigherOfTheBlockAndTheSheet() {
        // Block 300 px tall from the window bottom, sheet 360 px: the toast clears the sheet, less inset and margin.
        assertEquals(360 - 72 - 24, toastBarPx(300f, 360f, 72f, 24f))
        assertEquals(500 - 72 - 24, toastBarPx(500f, 360f, 72f, 24f))
        assertTrue(toastBarPx(0f, 0f, 72f, 24f) == 0)
        // A sheet at large (above the middle) is not followed: the toast keeps its own place, over the sheet.
        assertEquals(0, toastBarPx(1900f, 1800f, 72f, 24f, windowPx = 2400f))
        assertEquals(1000 - 72 - 24, toastBarPx(300f, 1000f, 72f, 24f, windowPx = 2400f))
    }
}
