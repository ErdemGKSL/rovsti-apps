package dev.rosty.design.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure band maths of the viewer's shade: the grid, the band of the picture a bar covers, and the alpha. */
class ViewerScrimTest {
    private fun solid(argb: Int) = ViewerScrim.lumaGrid(IntArray(ViewerScrim.COLS * ViewerScrim.ROWS) { argb })

    @Test
    fun whiteGridIsLuminanceOne() {
        val grid = solid(0xFFFFFFFF.toInt())
        assertEquals(1.0, ViewerScrim.bandLuminance(grid, 0f, 0.2f)!!, 1e-3)
    }

    @Test
    fun bandAveragesOnlyItsRows() {
        // Top half white, bottom half black.
        val pixels =
            IntArray(ViewerScrim.COLS * ViewerScrim.ROWS) {
                if (it / ViewerScrim.COLS <
                    16
                ) {
                    -1
                } else {
                    0xFF000000.toInt()
                }
            }
        val grid = ViewerScrim.lumaGrid(pixels)
        assertEquals(1.0, ViewerScrim.bandLuminance(grid, 0f, 0.5f)!!, 1e-3)
        assertEquals(0.0, ViewerScrim.bandLuminance(grid, 0.5f, 1f)!!, 1e-3)
        assertEquals(0.5, ViewerScrim.bandLuminance(grid, 0.25f, 0.75f)!!, 1e-3)
    }

    @Test
    fun emptyBandIsNull() {
        assertNull(ViewerScrim.bandLuminance(solid(-1), 0.4f, 0.4f))
    }

    @Test
    fun letterboxedPictureDoesNotReachTheBand() {
        // A wide photo centred on a tall screen: 300 px of black above it, the band is 200 px tall.
        assertNull(ViewerScrim.picturePart(0f, 200f, 300f, 600f))
        val part = ViewerScrim.picturePart(0f, 400f, 300f, 600f)
        assertNotNull(part)
        assertEquals(0f, part!!.first, 1e-6f)
        assertEquals(100f / 600f, part.second, 1e-6f)
    }

    @Test
    fun fullHeightPictureMapsBandStraightThrough() {
        val part = ViewerScrim.picturePart(0f, 240f, 0f, 2400f)!!
        assertEquals(0.1f, part.second, 1e-6f)
    }

    @Test
    fun darkOrUnknownBandGetsTheFloor() {
        assertEquals(ViewerScrim.FLOOR, ViewerScrim.alphaFor(null), 0f)
        assertEquals(ViewerScrim.FLOOR, ViewerScrim.alphaFor(0.02), 0f)
    }

    @Test
    fun lightBandGetsMoreShadeButNeverMoreThanMax() {
        val beige = ViewerScrim.alphaFor(0.6)
        val white = ViewerScrim.alphaFor(1.0)
        assertTrue(beige > ViewerScrim.FLOOR)
        assertTrue(white >= beige)
        assertEquals(ViewerScrim.MAX, white, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gridNeedsExactly512Pixels() {
        ViewerScrim.lumaGrid(IntArray(10))
    }
}
