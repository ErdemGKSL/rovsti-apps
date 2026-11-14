package dev.rosty.stops.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** E7.2a: the camera's own rules and its edges (poles, antimeridian, zoom limits, odd viewports, bad numbers). */
class MapCameraTest {
    private fun camera(
        lat: Double = IST_LAT,
        lon: Double = IST_LON,
        metresPerDp: Double = 4.0,
        width: Int = 400,
        height: Int = 800,
        density: Float = 1f,
    ) = MapCamera(lat, lon, metresPerDp).apply { setViewport(width, height, density) }

    // ---- projection ----

    @Test fun theCentreIsInTheMiddleAndAxesPointEastAndSouth() {
        val c = camera()
        assertEquals(200f, c.screenX(IST_LON), 1e-3f)
        assertEquals(400f, c.screenY(IST_LAT), 1e-3f)
        assertTrue(c.screenX(IST_LON + 0.01) > 200f)
        assertTrue(c.screenY(IST_LAT + 0.01) < 400f)
    }

    @Test fun roundTripWithinACentimetre() {
        for (mpp in doubleArrayOf(0.5, 3.0, 40.0, 2000.0)) {
            val c = camera(metresPerDp = mpp)
            for ((x, y) in listOf(0f to 0f, 400f to 800f, 12.5f to 99.25f, -300f to 1200f)) {
                val lat = c.latAt(y)
                val lon = c.lonAt(x)
                assertEquals("x at $mpp", x, c.screenX(lon), 0.02f)
                assertEquals("y at $mpp", y, c.screenY(lat), 0.02f)
            }
        }
    }

    @Test fun metresPerDpMatchesTheGroundDistance() {
        val c = camera(metresPerDp = 10.0)
        // 0.01 degrees of longitude at this latitude, by the haversine-free small-angle length.
        val metres = Math.toRadians(0.01) * MapMath.EARTH_RADIUS_M * Math.cos(Math.toRadians(IST_LAT))
        val px = c.screenX(IST_LON + 0.01) - c.screenX(IST_LON)
        assertEquals(metres / 10.0, px.toDouble(), metres / 10.0 * 2e-3)
        assertEquals(10.0, c.metresPerDp, 1e-9)
    }

    @Test fun densityScalesPixelsNotTheGround() {
        val one = camera(density = 1f, width = 400, height = 800)
        val three = camera(density = 3f, width = 1200, height = 2400)
        assertEquals(one.metresPerDp, three.metresPerDp, 1e-9)
        assertEquals(one.metresPerDp / 3, three.metresPerPx, 1e-9)
        assertEquals(one.zoom, three.zoom, 1e-12)
        assertEquals(one.zoom + MapMath.log2(3.0), three.pixelZoom, 1e-12)
        // The same coordinate is three times as far from the centre in px.
        val dx1 = one.screenX(IST_LON + 0.001) - 200f
        val dx3 = three.screenX(IST_LON + 0.001) - 600f
        assertEquals(dx1 * 3, dx3, 0.01f)
    }

    // ---- pan, zoom ----

    @Test fun panMovesContentWithTheFinger() {
        val c = camera(metresPerDp = 5.0)
        val lat = c.latAt(440f)
        val lon = c.lonAt(230f)
        assertTrue(c.panBy(10f, -5f))
        assertEquals(240f, c.screenX(lon), 0.02f)
        assertEquals(435f, c.screenY(lat), 0.02f)
    }

    @Test fun zoomKeepsTheFocalPoint() {
        val c = camera(metresPerDp = 20.0)
        val lat = c.latAt(320f)
        val lon = c.lonAt(320f)
        for (f in doubleArrayOf(2.0, 0.5, 1.7)) {
            assertTrue(c.zoomAbout(f, 320f, 320f))
            assertEquals("lon after $f", 320f, c.screenX(lon), 0.02f)
            assertEquals("lat after $f", 320f, c.screenY(lat), 0.02f)
        }
        assertEquals(20.0 / 1.7, c.metresPerDp, 0.05)
    }

    @Test fun theScaleIsClampedLikeTheGpuiMap() {
        val c = camera(metresPerDp = 1.0)
        c.zoomAbout(1000.0, 200f, 400f)
        assertEquals(MapMath.MIN_METRES_PER_DP, c.metresPerDp, 1e-9)
        assertEquals(MapMath.maxZoom(c.centreLat), c.zoom, 1e-9)
        c.zoomAbout(1e-9, 200f, 400f)
        assertEquals(MapMath.MAX_METRES_PER_DP, c.metresPerDp, 1e-6)
        assertEquals(MapMath.minZoom(c.centreLat), c.zoom, 1e-9)
        // Asking for more than the limit in the constructor is clamped too.
        assertEquals(MapMath.MAX_METRES_PER_DP, MapCamera(0.0, 0.0, 99_999.0).metresPerDp, 1e-6)
        assertEquals(MapMath.MIN_METRES_PER_DP, MapCamera(0.0, 0.0, 0.001).metresPerDp, 1e-9)
    }

    @Test fun atTheZoomLimitTheFocalPointStillHolds() {
        val c = camera(metresPerDp = 0.6)
        val lon = c.lonAt(90f)
        val lat = c.latAt(700f)
        c.zoomAbout(8.0, 90f, 700f)
        assertEquals(MapMath.MIN_METRES_PER_DP, c.metresPerDp, 1e-9)
        assertEquals(90f, c.screenX(lon), 0.05f)
        assertEquals(700f, c.screenY(lat), 0.05f)
    }

    @Test fun theZoomRangeAtTheEquatorAndHighUp() {
        // 2000 m/dp at the equator is display zoom 5.29 (`view.project.clamped_scale`), 0.5 m/dp is twelve zooms on
        // (2000 / 0.5 = 2^11.97). The camera's own zoom is on the sphere of rosty_geo, 0.0016 zooms lower.
        assertEquals(5.290415500623083, MapMath.displayZoom(0.0, MapMath.MAX_METRES_PER_DP), 1e-12)
        assertEquals(5.290415500623083, MapMath.minZoom(0.0), 2e-3)
        assertEquals(MapMath.minZoom(0.0) + MapMath.log2(4000.0), MapMath.maxZoom(0.0), 1e-9)
        assertEquals(
            MapMath.displayZoom(41.0, 7.0) - MapMath.zoomForScale(41.0, 7.0),
            MapMath.log2(MapMath.EQUATOR_M / MapMath.SPHERE_M),
            1e-12,
        )
        // Higher up the same ground scale is a lower zoom.
        assertTrue(MapMath.minZoom(70.0) < MapMath.minZoom(0.0))
        assertEquals(0, MapMath.fetchZoom(-3.0))
        assertEquals(0, MapMath.fetchZoom(Double.NaN))
        assertEquals(14, MapMath.fetchZoom(17.2))
        assertEquals(14, MapMath.fetchZoom(13.5))
        assertEquals(13, MapMath.fetchZoom(13.49))
    }

    // ---- poles ----

    @Test fun theCentreLatitudeIsClampedTo85() {
        val c = camera(lat = 89.0, lon = 10.0, metresPerDp = 100.0)
        assertEquals(85.0, c.centreLat, 1e-9)
        val s = camera(lat = -90.0, lon = 10.0, metresPerDp = 100.0)
        assertEquals(-85.0, s.centreLat, 1e-9)
        // Panning into the pole stops at the limit and keeps everything finite.
        repeat(50) { c.panBy(0f, 5_000f) }
        assertEquals(85.0, c.centreLat, 1e-9)
        assertTrue(c.zoom.isFinite() && c.metresPerDp.isFinite())
        assertTrue(c.metresPerDp in MapMath.MIN_METRES_PER_DP..MapMath.MAX_METRES_PER_DP + 1e-6)
    }

    @Test fun latitudesBeyondMercatorProjectToItsEdge() {
        val c = camera(lat = 80.0, lon = 0.0, metresPerDp = 2000.0)
        assertEquals(c.screenY(MapMath.MAX_MERCATOR_LAT), c.screenY(90.0), 0f)
        assertEquals(c.screenY(-MapMath.MAX_MERCATOR_LAT), c.screenY(-90.0), 0f)
        // A row above the top of the world unprojects to the edge, never beyond and never NaN.
        assertEquals(MapMath.MAX_MERCATOR_LAT, c.latAt(-1e7f), 1e-6)
        assertEquals(-MapMath.MAX_MERCATOR_LAT, c.latAt(1e9f), 1e-6)
        assertEquals(0.0, MapMath.worldY(MapMath.MAX_MERCATOR_LAT), 1e-9)
        assertEquals(1.0, MapMath.worldY(-90.0), 1e-9)
    }

    @Test fun scaleStaysInRangeWhenPanningTowardsThePoleAtTheCloseLimit() {
        val c = camera(lat = 0.0, lon = 0.0, metresPerDp = 0.5)
        val zoomAtEquator = c.zoom
        // Constant zoom would mean less than 0.5 m/dp further north; the zoom gives way instead.
        c.jumpTo(60.0, 0.0)
        assertEquals(MapMath.MIN_METRES_PER_DP, c.metresPerDp, 1e-9)
        assertTrue(c.zoom < zoomAtEquator)
    }

    // ---- antimeridian ----

    @Test fun longitudeWrapsWhenPannedEastAcrossTheAntimeridian() {
        val c = camera(lat = 0.0, lon = 179.9, metresPerDp = 2000.0)
        val dpPerDegree = Math.toRadians(1.0) * MapMath.EARTH_RADIUS_M / 2000.0
        // Drag the content 0.3 degrees to the left: the centre moves east to -179.8.
        assertTrue(c.panBy((-0.3 * dpPerDegree).toFloat(), 0f))
        assertEquals(-179.8, c.centreLon, 0.01)
        assertTrue(c.centreX in 0.0..1.0)
        assertEquals(0.0, c.centreLat, 1e-9)
    }

    @Test fun pointsAcrossTheAntimeridianProjectNextToEachOther() {
        val c = camera(lat = 10.0, lon = 179.9, metresPerDp = 500.0)
        val west = c.screenX(179.8)
        val east = c.screenX(-179.9)
        assertTrue("west of the centre", west < 200f)
        assertTrue("east of the centre, not a world away", east > 200f && east < 400f)
        // And back: the column right of the centre is a negative longitude.
        assertTrue(c.lonAt(east) < -179.0)
        assertEquals(-179.9, c.lonAt(east), 1e-3)
    }

    @Test fun longitudesOutsideTheRangeAreTheSamePlace() {
        val a = camera(lon = 28.9784)
        val b = camera(lon = 28.9784 + 360.0)
        val w = camera(lon = 28.9784 - 720.0)
        assertEquals(a.centreLon, b.centreLon, 1e-9)
        assertEquals(a.centreLon, w.centreLon, 1e-9)
        assertEquals(a.screenX(30.0), a.screenX(390.0), 1e-3f)
        assertEquals(-180.0, MapMath.lonOf(MapMath.worldX(180.0)), 1e-12)
    }

    @Test fun zoomingAboutAPointAcrossTheAntimeridianKeepsIt() {
        val c = camera(lat = 0.0, lon = 179.99, metresPerDp = 100.0)
        val lon = c.lonAt(390f)
        assertTrue(lon < 0.0)
        c.zoomAbout(4.0, 390f, 400f)
        assertEquals(390f, c.screenX(lon), 0.05f)
        assertTrue(c.centreX in 0.0..1.0)
    }

    // ---- viewports ----

    @Test fun aZeroSizeViewportIsHarmless() {
        val c = MapCamera(IST_LAT, IST_LON, 4.0)
        assertEquals(0, c.widthPx)
        assertTrue(c.panBy(10f, 10f))
        assertTrue(c.zoomAbout(2.0, 0f, 0f))
        assertTrue(c.fit(40.9, 28.9, 41.1, 29.1, marginPx = 50f))
        assertTrue(c.zoom.isFinite() && c.centreLat.isFinite() && c.centreLon.isFinite())
        assertTrue(c.setViewport(0, 0, 2f))
        assertEquals(0f, c.focusX, 0f)
    }

    @Test fun tinyAndHugeViewports() {
        val tiny = camera(width = 1, height = 1)
        assertEquals(0.5f, tiny.screenX(IST_LON), 1e-3f)
        assertTrue(tiny.fit(40.9, 28.9, 41.1, 29.1, marginPx = 500f))
        assertTrue(tiny.metresPerDp.isFinite())
        val huge = camera(width = 100_000, height = 100_000, metresPerDp = 2000.0)
        assertEquals(50_000f, huge.screenX(IST_LON), 0.5f)
        // Wider than the world at this zoom: a point still has one position, the nearest copy.
        assertTrue(huge.worldPx < 100_000)
        assertTrue(abs(huge.screenX(IST_LON + 179.0) - 50_000f) <= huge.worldPx / 2 + 1)
    }

    @Test fun badViewportsAreRefused() {
        val c = camera()
        assertFalse(c.setViewport(-1, 10, 1f))
        assertFalse(c.setViewport(10, 10, 0f))
        assertFalse(c.setViewport(10, 10, Float.NaN))
        assertFalse(c.setViewport(10, 10, Float.POSITIVE_INFINITY))
        assertEquals(400, c.widthPx)
        assertFalse(c.setPadding(Float.NaN, 0f, 0f, 0f))
    }

    // ---- NaN and infinity ----

    @Test fun nonFiniteInputIsRefusedAndChangesNothing() {
        val c = camera()
        val lat = c.centreLat
        val lon = c.centreLon
        val zoom = c.zoom
        val generation = c.generation
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val f = bad.toFloat()
            assertFalse(c.jumpTo(bad, 0.0))
            assertFalse(c.jumpTo(0.0, bad))
            assertFalse(c.jumpTo(0.0, 0.0, bad))
            assertFalse(c.setView(bad, 0.0, 4.0))
            assertFalse(c.setView(0.0, 0.0, bad))
            assertFalse(c.centreOn(bad, 0.0))
            assertFalse(c.panBy(f, 0f))
            assertFalse(c.panBy(0f, f))
            assertFalse(c.zoomAbout(bad, 0f, 0f))
            assertFalse(c.zoomAbout(2.0, f, 0f))
            assertFalse(c.fit(bad, 0.0, 1.0, 1.0))
            assertFalse(c.fit(0.0, 0.0, 1.0, 1.0, marginPx = f))
            assertFalse(c.fitPoints(doubleArrayOf(1.0, bad)))
            assertFalse(c.fling(f, 0f))
            assertFalse(c.animateZoomBy(bad))
            assertFalse(c.travelTo(bad, 0.0))
        }
        assertFalse(c.zoomAbout(0.0, 0f, 0f))
        assertFalse(c.zoomAbout(-1.0, 0f, 0f))
        assertEquals(lat, c.centreLat, 0.0)
        assertEquals(lon, c.centreLon, 0.0)
        assertEquals(zoom, c.zoom, 0.0)
        assertEquals(generation, c.generation)
        assertFalse(c.animating)
        // A camera built from rubbish starts at the default view.
        val d = MapCamera(Double.NaN, Double.NaN, Double.NaN)
        assertEquals(MapCamera.DEFAULT_LAT, d.centreLat, 1e-9)
    }

    // ---- fit and padding ----

    @Test fun fitContainsTheBoxAndTheWideAxisBinds() {
        val c = camera(width = 400, height = 800)
        assertTrue(c.fit(41.00, 28.90, 41.05, 29.10, marginPx = 24f))
        val left = c.screenX(28.90)
        val right = c.screenX(29.10)
        assertEquals(24f, left, 0.5f)
        assertEquals(376f, right, 0.5f)
        assertTrue(c.screenY(41.05) >= 24f && c.screenY(41.00) <= 776f)
    }

    @Test fun fitAimsAtTheAreaThePaddingLeavesFree() {
        val c = camera(width = 400, height = 800)
        // A sheet at its medium detent covers the lower half.
        assertTrue(c.setPadding(0f, 100f, 0f, 400f))
        assertTrue(c.fit(41.00, 28.90, 41.05, 29.10, marginPx = 20f))
        assertEquals(250f, c.focusY, 0f)
        val midY = (c.screenY(41.00) + c.screenY(41.05)) / 2
        assertEquals(250f, midY, 0.5f)
        assertTrue(c.screenY(41.05) >= 120f - 0.5f && c.screenY(41.00) <= 380f + 0.5f)
        // centreOn uses the same point.
        assertTrue(c.centreOn(41.02, 29.0))
        assertEquals(250f, c.screenY(41.02), 0.5f)
        assertEquals(200f, c.screenX(29.0), 0.5f)
    }

    @Test fun fitOfOnePointUsesTheClosestAllowedScale() {
        val c = camera()
        assertTrue(c.fitPoints(doubleArrayOf(IST_LAT, IST_LON)))
        assertEquals(MapMath.MIN_METRES_PER_DP, c.metresPerDp, 1e-9)
        // The GPUI app never fits closer than 4 m per px (`MIN_FIT_MPP`).
        assertTrue(c.fitPoints(doubleArrayOf(IST_LAT, IST_LON), minMetresPerDp = 4.0))
        assertEquals(4.0, c.metresPerDp, 1e-6)
        assertTrue(c.fitPoints(doubleArrayOf()))
        assertEquals(MapMath.MAX_METRES_PER_DP, c.metresPerDp, 1e-6)
    }

    // ---- animation ----

    @Test fun flingCoastsAndStops() {
        val c = camera(metresPerDp = 4.0)
        val startLon = c.centreLon
        assertTrue(c.fling(1500f, 0f))
        assertTrue(c.animating && c.moving.value)
        var frames = 0
        var lastStep = Double.MAX_VALUE
        var previous = c.centreX
        while (c.advance(1.0 / 120) && frames < 5000) {
            val step = abs(c.centreX - previous)
            // Decays: no frame travels further than the one before.
            assertTrue(step <= lastStep + 1e-15)
            lastStep = step
            previous = c.centreX
            frames++
        }
        assertFalse(c.animating || c.moving.value)
        assertTrue("stops within a few seconds, took $frames frames", frames in 60..600)
        // Content went right with the finger, so the centre went west; total travel is v / (1000 * -ln(rate)).
        assertTrue(c.centreLon < startLon)
        val travelledPx = (MapMath.wrapDelta(MapMath.worldX(startLon), c.centreX)) * c.worldPx
        assertEquals(1500 / (1000 * -Math.log(MapCamera.FLING_RATE)), travelledPx, 12.0)
    }

    @Test fun aSlowReleaseDoesNotFling() {
        val c = camera()
        assertTrue(c.fling(10f, 10f))
        assertFalse(c.animating)
        assertFalse(c.moving.value)
    }

    @Test fun flingIntoTheLatitudeLimitEnds() {
        val c = camera(lat = 84.99, lon = 0.0, metresPerDp = 100.0)
        c.fling(0f, 6000f)
        var frames = 0
        while (c.advance(1.0 / 60) && frames < 2000) frames++
        assertFalse(c.animating)
        assertEquals(85.0, c.centreLat, 1e-6)
        assertTrue(frames < 30)
    }

    @Test fun doubleTapZoomEndsTwiceAsCloseAboutTheTap() {
        val c = camera(metresPerDp = 8.0)
        val lat = c.latAt(600f)
        val lon = c.lonAt(100f)
        assertTrue(c.animateZoomBy(MapCamera.DOUBLE_TAP_FACTOR, 100f, 600f))
        assertTrue(c.zooming)
        var zoom = c.zoom
        var frames = 0
        while (c.advance(1.0 / 60)) {
            assertTrue("zooms in monotonically", c.zoom >= zoom)
            zoom = c.zoom
            assertEquals(100f, c.screenX(lon), 0.05f)
            assertEquals(600f, c.screenY(lat), 0.05f)
            frames++
        }
        assertTrue("a quarter of a second, took $frames frames", frames in 13..16)
        assertEquals(4.0, c.metresPerDp, 0.01)
        assertFalse(c.zooming || c.moving.value)
    }

    @Test fun aSecondDoubleTapDuringTheFirstAddsUp() {
        val c = camera(metresPerDp = 8.0)
        val z = c.zoom
        c.animateZoomBy(2.0, 200f, 400f)
        c.advance(0.1)
        c.animateZoomBy(2.0, 200f, 400f)
        while (c.advance(1.0 / 60)) Unit
        assertEquals(z + 2.0, c.zoom, 1e-6)
    }

    @Test fun travelGlidesToTheTargetTheShortWayRound() {
        val c = camera(lat = 0.0, lon = 179.0, metresPerDp = 2000.0)
        assertTrue(c.travelTo(0.0, -179.0))
        var maxStep = 0.0
        var previous = c.centreX
        while (c.advance(1.0 / 60)) {
            maxStep = maxOf(maxStep, abs(MapMath.wrapDelta(c.centreX, previous)))
            previous = c.centreX
        }
        assertEquals(-179.0, c.centreLon, 1e-6)
        // Two degrees in all, never the 358 degree way.
        assertTrue(maxStep < 2.0 / 360)
    }

    @Test fun aTouchStopsWhateverRuns() {
        val c = camera()
        c.fling(3000f, 3000f)
        c.advance(0.016)
        c.setGesture(active = true)
        assertFalse(c.animating)
        assertTrue(c.moving.value)
        c.setGesture(active = false)
        assertFalse(c.moving.value)
    }

    // ---- observers ----

    @Test fun movingChangesOnEdgesOnlyAndListenersHearEveryChange() {
        val c = camera()
        var calls = 0
        val listener = {
            calls++
            Unit
        }
        c.addListener(listener)
        val generation = c.generation
        c.setGesture(active = true)
        assertTrue(c.moving.value)
        repeat(10) { c.panBy(3f, 1f) }
        assertTrue(c.moving.value)
        assertEquals(generation + 10, c.generation)
        assertTrue(calls >= 11)
        c.setGesture(active = false)
        assertFalse(c.moving.value)
        c.removeListener(listener)
        val before = calls
        c.panBy(1f, 1f)
        assertEquals(before, calls)
        assertNotEquals(generation, c.generation)
    }

    @Test fun pinchIsZoomingAndAPanIsNot() {
        val c = camera()
        c.setGesture(active = true, pinch = false)
        assertFalse(c.zooming)
        c.setGesture(active = true, pinch = true)
        assertTrue(c.zooming)
        c.setGesture(active = false)
        assertFalse(c.zooming)
    }

    private companion object {
        const val IST_LAT = 41.0082
        const val IST_LON = 28.9784
    }
}
