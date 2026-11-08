package dev.rovsti.design.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlingTest {
    @Test
    fun projectedDistanceOfOneThousandPxPerSecondIs499() {
        assertEquals(499f, Fling.projectedDistance(1000f), 0.5f)
        assertEquals(-499f, Fling.projectedDistance(-1000f), 0.5f)
        assertEquals(0f, Fling.projectedDistance(0f))
    }

    @Test
    fun fastRateTravelsFurther() {
        assertEquals(99f, Fling.projectedDistance(1000f, Fling.FastRate), 0.5f)
        // Higher rate (0.998) travels further than 0.99 for the same velocity.
        assertTrue(Fling.projectedDistance(1000f) > Fling.projectedDistance(1000f, Fling.FastRate))
    }

    @Test
    fun restingPointAddsTheProjection() {
        assertEquals(599f, Fling.restingPoint(100f, 1000f), 0.5f)
    }

    @Test
    fun picksTheNearestSnapPoint() {
        val points = floatArrayOf(0f, 400f, 800f)
        assertEquals(400f, Fling.nearestSnap(points, 430f, 100f))
        assertEquals(800f, Fling.nearestSnap(points, 900f, -100f))
        assertEquals(0f, Fling.nearestSnap(points, -50f, 0f))
    }

    @Test
    fun tieGoesToTheDirectionOfTravel() {
        val points = floatArrayOf(0f, 400f)
        assertEquals(400f, Fling.nearestSnap(points, 200f, 500f))
        assertEquals(0f, Fling.nearestSnap(points, 200f, -500f))
        // Order of the array does not matter.
        assertEquals(400f, Fling.nearestSnap(floatArrayOf(400f, 0f), 200f, 500f))
        assertEquals(0f, Fling.nearestSnap(floatArrayOf(400f, 0f), 200f, -500f))
    }

    @Test
    fun zeroVelocityTiePicksTheLowerValue() {
        assertEquals(0f, Fling.nearestSnap(floatArrayOf(0f, 400f), 200f, 0f))
        assertEquals(0f, Fling.nearestSnap(floatArrayOf(400f, 0f), 200f, 0f))
    }

    @Test
    fun singlePointIsAlwaysChosen() {
        assertEquals(7f, Fling.nearestSnap(floatArrayOf(7f), 1e6f, 1f))
    }
}

class RubberTest {
    private val dim = 800f

    @Test
    fun isOdd() {
        for (x in listOf(1f, 10f, 123f, 5000f)) {
            assertEquals(-Rubber.band(x, dim), Rubber.band(-x, dim), 1e-4f)
        }
        assertEquals(0f, Rubber.band(0f, dim))
    }

    @Test
    fun isStrictlyMonotonic() {
        var previous = 0f
        var x = 1f
        while (x < 100_000f) {
            val y = Rubber.band(x, dim)
            assertTrue("band($x) = $y must exceed $previous", y > previous)
            previous = y
            x *= 1.5f
        }
    }

    @Test
    fun slopeIsAboutOneAtZero() {
        val h = 0.01f
        assertEquals(1f, Rubber.band(h, dim) / h, 0.01f)
    }

    @Test
    fun staysBelowTheAsymptote() {
        val limit = dim / Rubber.Constant
        for (x in listOf(1e3f, 1e5f, 1e7f, 1e9f)) {
            assertTrue("band($x) = ${Rubber.band(x, dim)} < $limit", Rubber.band(x, dim) < limit)
        }
        assertEquals(limit, Rubber.band(1e9f, dim), 1f)
    }

    @Test
    fun invalidDimensionGivesZero() {
        assertEquals(0f, Rubber.band(50f, 0f))
        assertEquals(0f, Rubber.band(50f, -3f))
    }
}
