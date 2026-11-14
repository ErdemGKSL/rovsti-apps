package dev.rosty.stops.map

import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.StopColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E7.3 and E7.4: what is under the finger, as pure functions of the camera and the radar state. */
class RadarHitTestTest {
    private fun camera(density: Float = 1f) =
        MapCamera(41.0, 29.0, 4.0).apply { setViewport((400 * density).toInt(), (800 * density).toInt(), density) }

    /** The coordinate under a pixel, as a pin. */
    private fun MapCamera.pinAt(
        x: Float,
        y: Float,
    ) = RadarPin(latAt(y), lonAt(x))

    private fun MapCamera.stops(vararg at: Pair<Float, Float>) =
        RadarStops(
            DoubleArray(at.size * 2) { if (it % 2 == 0) latAt(at[it / 2].second) else lonAt(at[it / 2].first) },
            at.indices.map { "S$it" },
            at.indices.map { "Stop $it" },
        )

    // ---- pins ----

    @Test fun aPinIsGrabbedWithin32DpAndNotBeyond() {
        val c = camera()
        val state = RadarState(pinA = c.pinAt(100f, 100f))
        assertEquals(Slot.A, RadarHitTest.pinAt(c, state, 100f, 100f))
        assertEquals(Slot.A, RadarHitTest.pinAt(c, state, 131f, 100f))
        assertNull(RadarHitTest.pinAt(c, state, 133f, 100f))
        assertNull(RadarHitTest.pinAt(c, RadarState.Empty, 100f, 100f))
        assertEquals(32f, RadarHitTest.PIN_GRAB_DP, 0f)
    }

    @Test fun theGrabRadiusIsInDp() {
        val c = camera(density = 3f)
        val state = RadarState(pinB = c.pinAt(300f, 300f))
        assertEquals(Slot.B, RadarHitTest.pinAt(c, state, 300f + 95f, 300f))
        assertNull(RadarHitTest.pinAt(c, state, 300f + 97f, 300f))
    }

    @Test fun theNearerOfTwoPinsWins() {
        val c = camera()
        val state = RadarState(pinA = c.pinAt(100f, 100f), pinB = c.pinAt(140f, 100f))
        assertEquals(Slot.A, RadarHitTest.pinAt(c, state, 115f, 100f))
        assertEquals(Slot.B, RadarHitTest.pinAt(c, state, 125f, 100f))
        // Exactly between: A.
        assertEquals(Slot.A, RadarHitTest.pinAt(c, state, 120f, 100f))
        // On top of each other: still one answer.
        val stacked = RadarState(pinA = c.pinAt(100f, 100f), pinB = c.pinAt(100f, 100f))
        assertEquals(Slot.A, RadarHitTest.pinAt(c, stacked, 100f, 100f))
    }

    @Test fun pinsAcrossTheAntimeridianAreStillUnderTheFinger() {
        val c = MapCamera(0.0, 179.999, 4.0).apply { setViewport(400, 800, 1f) }
        val x = c.screenX(-179.9995)
        assertTrue(x > 200f && x < 400f)
        val state = RadarState(pinA = RadarPin(0.0, -179.9995))
        assertEquals(Slot.A, RadarHitTest.pinAt(c, state, x + 5f, 400f))
    }

    // ---- stops ----

    @Test fun theNearestStopWithinTheRadius() {
        val c = camera()
        val stops = c.stops(100f to 100f, 130f to 100f, 300f to 700f)
        assertEquals(0, RadarHitTest.stopAt(c, stops, 110f, 100f, 40f))
        assertEquals(1, RadarHitTest.stopAt(c, stops, 121f, 100f, 40f))
        assertEquals(-1, RadarHitTest.stopAt(c, stops, 200f, 400f, 40f))
        assertEquals(2, RadarHitTest.stopAt(c, stops, 300f, 660.5f, 40f))
        assertEquals(-1, RadarHitTest.stopAt(c, stops, 300f, 659f, 40f))
        assertEquals(10f, RadarHitTest.stopDistancePx(c, stops, 0, 110f, 100f), 0.05f)
        assertEquals(-1, RadarHitTest.stopAt(c, RadarStops.None, 110f, 100f, 40f))
    }

    @Test fun stopsMustMatchTheirNames() {
        try {
            RadarStops(doubleArrayOf(41.0, 29.0), emptyList(), emptyList())
            throw AssertionError("accepted a stop without an id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("match"))
        }
    }

    // ---- saved stops ----

    private fun saved(vararg stops: SavedStop) = SavedStops(2, 99, stops.toList())

    private fun stop(
        id: Long,
        a: Coord,
        b: Coord,
        colour: StopColour = StopColour.Orange,
    ) = SavedStop(id, "Stop $id", Endpoint("", a), Endpoint("", b), 0, colour = colour)

    @Test fun savedMarkersSitInTheMiddleOfTheirGate() {
        val markers = RadarSaved.of(saved(stop(7, Coord(41.0, 28.994), Coord(41.0, 29.006), StopColour.Green)))
        assertEquals(1, markers.size)
        assertEquals(41.0, markers.latLon[0], 1e-12)
        assertEquals(29.0, markers.latLon[1], 1e-12)
        assertEquals(listOf(StopColour.Green), markers.colours)
        assertEquals(7L, markers.ids[0])
        assertEquals(0, RadarSaved.of(saved()).size)
    }

    @Test fun theMiddleOfAGateAcrossTheAntimeridianIsOnIt() {
        assertEquals(-179.95, RadarSaved.midLon(179.9, -179.8), 1e-9)
        assertEquals(179.95, RadarSaved.midLon(179.8, -179.9), 1e-9)
        assertEquals(-180.0, RadarSaved.midLon(179.9, -179.9), 1e-9)
        assertEquals(29.0, RadarSaved.midLon(28.0, 30.0), 1e-12)
        assertEquals(0.0, RadarSaved.midLon(-10.0, 10.0), 1e-12)
    }

    @Test fun aTapOnASavedMarkerAnswersItsStop() {
        val c = camera()
        val a = stop(1, Coord(c.latAt(100f), c.lonAt(90f)), Coord(c.latAt(100f), c.lonAt(110f)))
        val b = stop(2, Coord(c.latAt(100f), c.lonAt(120f)), Coord(c.latAt(100f), c.lonAt(130f)))
        val state = RadarState(saved = RadarSaved.of(saved(a, b)))
        assertEquals(1L, RadarHitTest.savedAt(c, state, 100f, 100f))
        assertEquals(1L, RadarHitTest.savedAt(c, state, 100f, 117f))
        assertEquals(-1L, RadarHitTest.savedAt(c, state, 100f, 119f))
        // Between the two markers the nearer one answers.
        assertEquals(2L, RadarHitTest.savedAt(c, state, 116f, 100f))
        // Hidden markers (the S40 toggle) are not targets.
        assertEquals(-1L, RadarHitTest.savedAt(c, state.copy(showSaved = false), 100f, 100f))
        assertEquals(-1L, RadarHitTest.savedAt(c, RadarState.Empty, 100f, 100f))
    }

    // ---- position ----

    @Test fun thePositionDotIsHitWithin24Dp() {
        val c = camera()
        val position = RadarPosition(c.latAt(400f), c.lonAt(200f), 12.0)
        assertTrue(RadarHitTest.hitsPosition(c, position, 200f, 400f))
        assertTrue(RadarHitTest.hitsPosition(c, position, 223f, 400f))
        assertFalse(RadarHitTest.hitsPosition(c, position, 225f, 400f))
        assertFalse(RadarHitTest.hitsPosition(c, null, 200f, 400f))
    }

    // ---- metres to pixels ----

    @Test fun metresBecomePixelsAtTheCameraScale() {
        val c = camera()
        // 4 m per dp at the centre latitude.
        assertEquals(25f, RadarHitTest.metresToPx(c, 100.0, 41.0), 0.01f)
        assertEquals(75f, RadarHitTest.metresToPx(camera(density = 3f), 100.0, 41.0), 0.03f)
        // Mercator stretches towards the poles: the same metres are more pixels further north.
        assertTrue(RadarHitTest.metresToPx(c, 100.0, 60.0) > 25f * 1.4f)
    }

    @Test fun oddLengthsAndLatitudesNeverGiveNaN() {
        val c = camera()
        assertEquals(0f, RadarHitTest.metresToPx(c, 0.0, 41.0), 0f)
        assertEquals(0f, RadarHitTest.metresToPx(c, -5.0, 41.0), 0f)
        assertEquals(0f, RadarHitTest.metresToPx(c, Double.NaN, 41.0), 0f)
        assertEquals(0f, RadarHitTest.metresToPx(c, Double.POSITIVE_INFINITY, 41.0), 0f)
        // A centimetre and half the planet.
        assertEquals(0.0025f, RadarHitTest.metresToPx(c, 0.01, 41.0), 1e-5f)
        val huge = RadarHitTest.metresToPx(c, 20_000_000.0, 41.0)
        assertTrue(huge.isFinite() && huge > 1_000_000f)
        // At and beyond the pole the latitude is clamped to the edge of the projection: finite, large.
        val polar = RadarHitTest.metresToPx(c, 100.0, 90.0)
        assertTrue(polar.isFinite() && polar > 100f)
        assertEquals(polar, RadarHitTest.metresToPx(c, 100.0, 89.0), 0f)
        assertEquals(polar, RadarHitTest.metresToPx(c, 100.0, -90.0), 0f)
    }

    // ---- the holder ----

    @Test fun theHolderKeepsStateAndTheDrag() {
        val holder = RadarHolder()
        assertNull(holder.dragSlot)
        holder.update { it.copy(showSaved = false, trail = doubleArrayOf(41.0, 29.0)) }
        assertFalse(holder.state.showSaved)
        assertEquals(2, holder.state.trail.size)
        holder.dragTo(Slot.B, 41.5, 29.5)
        assertEquals(Slot.B, holder.dragSlot)
        assertEquals(41.5, holder.dragLat, 0.0)
        holder.endDrag()
        assertNull(holder.dragSlot)
        // copy keeps what it is not told to change.
        val next = holder.state.copy(pinA = RadarPin(1.0, 2.0, snapped = true, caption = "Pier"))
        assertFalse(next.showSaved)
        assertEquals("Pier", next.pin(Slot.A)!!.caption)
        assertNull(next.pin(Slot.B))
    }

    @Test fun flattenIsLatLonPairs() {
        assertEquals(listOf(41.0, 29.0, 42.0, 30.0), flatten(listOf(Coord(41.0, 29.0), Coord(42.0, 30.0))).toList())
        assertEquals(0, flatten(emptyList()).size)
    }
}
