package dev.rovsti.design.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ConcentricTest {
    @Test
    fun innerRadiusIsOuterMinusInset() {
        assertEquals(12.dp, concentric(outer = 20.dp, inset = 8.dp))
        assertEquals(28.dp, concentric(outer = 32.dp, inset = 4.dp))
        assertEquals(20.dp, concentric(outer = 20.dp, inset = 0.dp))
    }

    @Test
    fun neverBelowTheFourDpFloor() {
        assertEquals(4.dp, concentric(outer = 20.dp, inset = 16.dp))
        assertEquals(4.dp, concentric(outer = 20.dp, inset = 20.dp))
        assertEquals(4.dp, concentric(outer = 10.dp, inset = 40.dp))
        assertEquals(4.dp, concentric(outer = 0.dp, inset = 0.dp))
    }

    @Test
    fun shrinksMonotonicallyWithInset() {
        var previous = concentric(20.dp, 0.dp)
        for (inset in 1..30) {
            val next = concentric(20.dp, inset.dp)
            assert(next <= previous)
            previous = next
        }
    }
}
