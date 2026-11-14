package dev.rosty.stops.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S28 and S43: the scale bar's lengths, against `apps/stops/src/map.rs::scale_bar` for metric. */
class ScaleBarsTest {
    /** The Rust function, transcribed, as the reference for the metric steps. */
    private fun rust(metresPerPx: Double): Pair<Float, String> {
        val steps = intArrayOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)
        val metres = steps.reversed().firstOrNull { it / metresPerPx <= 110.0 } ?: steps[0]
        val label = if (metres >= 1_000) "${metres / 1_000} km" else "$metres m"
        return (metres / metresPerPx).toFloat() to label
    }

    private fun label(
        index: Int,
        units: MapUnits,
    ): String {
        val unit =
            when {
                units == MapUnits.Metric && ScaleBars.isLargeUnit(index, units) -> "km"
                units == MapUnits.Metric -> "m"
                ScaleBars.isLargeUnit(index, units) -> "mi"
                else -> "ft"
            }
        return "${ScaleBars.labelValue(index, units)} $unit"
    }

    @Test fun metricMatchesTheGpuiScaleBarAcrossTheZoomRange() {
        var mpp = 0.05
        while (mpp < 5000.0) {
            val (px, text) = rust(mpp)
            val i = ScaleBars.stepIndex(mpp, MapUnits.Metric)
            assertEquals("length at $mpp", px, ScaleBars.lengthDp(i, mpp, MapUnits.Metric), px * 1e-6f)
            assertEquals("label at $mpp", text, label(i, MapUnits.Metric))
            mpp *= 1.07
        }
    }

    @Test fun spotValues() {
        // 4 m per dp: 200 m is 50 dp, 500 m would be 125 dp.
        val i = ScaleBars.stepIndex(4.0, MapUnits.Metric)
        assertEquals("200 m", label(i, MapUnits.Metric))
        assertEquals(50f, ScaleBars.lengthDp(i, 4.0, MapUnits.Metric), 1e-4f)
        assertEquals("1 km", label(ScaleBars.stepIndex(10.0, MapUnits.Metric), MapUnits.Metric))
        assertEquals("100 km", label(ScaleBars.stepIndex(2000.0, MapUnits.Metric), MapUnits.Metric))
        assertEquals("50 m", label(ScaleBars.stepIndex(0.5, MapUnits.Metric), MapUnits.Metric))
    }

    @Test fun theBarIsNeverWiderThanTheLimitWithinTheCameraRange() {
        for (units in MapUnits.entries) {
            var mpp = MapMath.MIN_METRES_PER_DP
            while (mpp <= MapMath.MAX_METRES_PER_DP) {
                val i = ScaleBars.stepIndex(mpp, units)
                val dp = ScaleBars.lengthDp(i, mpp, units)
                assertTrue("$units at $mpp: $dp", dp <= ScaleBars.MAX_DP + 1e-3 && dp >= 20f)
                mpp *= 1.05
            }
        }
    }

    @Test fun imperialUsesFeetThenMiles() {
        // 0.5 m per dp: 110 dp are 55 m = 180 ft, so 100 ft.
        val close = ScaleBars.stepIndex(0.5, MapUnits.Imperial)
        assertEquals("100 ft", label(close, MapUnits.Imperial))
        assertEquals(100 * 0.3048 / 0.5, ScaleBars.lengthDp(close, 0.5, MapUnits.Imperial).toDouble(), 1e-3)
        // 20 m per dp: 110 dp are 2200 m = 1.37 mi, so 1 mi = 80.5 dp.
        val city = ScaleBars.stepIndex(20.0, MapUnits.Imperial)
        assertEquals("1 mi", label(city, MapUnits.Imperial))
        assertEquals(1609.344 / 20.0, ScaleBars.lengthDp(city, 20.0, MapUnits.Imperial).toDouble(), 1e-3)
        assertEquals("2000 ft", label(ScaleBars.stepIndex(6.0, MapUnits.Imperial), MapUnits.Imperial))
        assertEquals("100 mi", label(ScaleBars.stepIndex(2000.0, MapUnits.Imperial), MapUnits.Imperial))
    }

    @Test fun aLongerStepIsChosenAsTheViewWidens() {
        for (units in MapUnits.entries) {
            var last = 0
            var mpp = 0.5
            while (mpp <= 2000.0) {
                val i = ScaleBars.stepIndex(mpp, units)
                assertTrue(i >= last)
                last = i
                mpp *= 1.1
            }
            assertEquals(ScaleBars.stepCount(units) - 1, last)
        }
    }

    @Test fun rubbishScalesShowTheShortestStep() {
        for (bad in doubleArrayOf(Double.NaN, 0.0, -3.0, Double.POSITIVE_INFINITY)) {
            assertEquals(0, ScaleBars.stepIndex(bad, MapUnits.Metric))
            assertEquals(0f, ScaleBars.lengthDp(0, bad, MapUnits.Metric), 0f)
        }
        // Closer than any step fits: the shortest, at its true (too long) length, like Rust.
        assertEquals(0, ScaleBars.stepIndex(0.01, MapUnits.Metric))
        assertFalse(ScaleBars.isLargeUnit(0, MapUnits.Imperial))
    }

    @Test fun metresPerDpAtALatitudeIsTheMercatorScale() {
        // The same zoom shows half the ground per dp at 60 degrees as at the equator.
        assertEquals(MapMath.metresPerDp(0.0, 10.0) / 2, MapMath.metresPerDp(60.0, 10.0), 1e-6)
        // Zoom 0: the whole equator of the sphere in 512 dp.
        assertEquals(2 * Math.PI * MapMath.EARTH_RADIUS_M / 512, MapMath.metresPerDp(0.0, 0.0), 1e-6)
        assertEquals(10.0, MapMath.zoomForScale(0.0, MapMath.metresPerDp(0.0, 10.0)), 1e-12)
    }
}
