// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Cases from glisse test/lens.test.tsx:10-49 and test/glass.test.tsx:123-124.
package dev.rovsti.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class LensPullTest {
    private val options = LensOptions(strength = 0.44f, dispersion = 1f, depth = 2f, dome = 6f, splay = 0.4f)

    private fun pull(
        x: Float,
        y: Float,
    ) = lensPull(x, y, 60f, 30f, 15f, options)

    @Test
    fun centreIsNotBent() {
        val (dx, dy) = pull(0f, 0f)!!
        assertEquals(0f, dx, 0f)
        assertEquals(0f, dy, 0f)
        val (nx, ny) = pull(0.5f, 0.5f)!!
        assertEquals(0f, nx, 1e-3f)
        assertEquals(0f, ny, 1e-3f)
    }

    @Test
    fun outsideTheShapeIsNull() {
        assertNull(lensPull(-29.5f, -14.5f, 60f, 30f, 15f, options))
        assertNull(lensPull(31f, 0f, 60f, 30f, 15f, options))
    }

    @Test
    fun rimsSampleFromFurtherIn() {
        // Pixel centres 2.5 px in from each end, as glisse reads columns - 3 and 2.
        val right = pull(27.5f, 0.5f)!!
        val left = pull(-27.5f, 0.5f)!!
        val top = pull(0.5f, -12.5f)!!
        val bottom = pull(0.5f, 12.5f)!!
        assertTrue("right dx > 0", right.first > 0f)
        assertTrue("left dx < 0", left.first < 0f)
        assertTrue("top dy < 0", top.second < 0f)
        assertTrue("bottom dy > 0", bottom.second > 0f)
    }

    @Test
    fun pullIsAntisymmetric() {
        for (xi in -29..29) {
            for (yi in -14..14) {
                val a = pull(xi + 0.5f, yi + 0.5f) ?: continue
                val b = pull(xi + 0.5f, -(yi + 0.5f)) ?: continue
                assertEquals(a.second, -b.second, 1e-4f)
                assertEquals(a.first, b.first, 1e-4f)
                val c = pull(-(xi + 0.5f), yi + 0.5f) ?: continue
                assertEquals(a.first, -c.first, 1e-4f)
            }
        }
    }

    @Test
    fun relativeBandIsTheSameShareAtAnySize() {
        val glass = LensOptions.Glass

        // How far in from the left edge the pull has faded to within one grey step, as a share of the size.
        fun reachShare(size: Int): Float {
            val row = size / 2
            for (column in 0 until size) {
                val x = column + 0.5f - size / 2f
                val y = row + 0.5f - size / 2f
                val p = lensPull(x, y, size.toFloat(), size.toFloat(), size / 4f, glass)
                val grey = if (p == null) 128 else ((0.5f - 0.5f * p.first) * 255f).roundToInt()
                if (abs(grey - 128) <= 1) return column.toFloat() / size
            }
            return 1f
        }
        assertEquals(reachShare(40), reachShare(160), 0.05f)
    }

    @Test
    fun nonRelativeBandFollowsDp() {
        val thin = lensPull(27.5f, 0.5f, 60f, 30f, 15f, options.copy(depth = 1f))!!
        val deep = lensPull(27.5f, 0.5f, 60f, 30f, 15f, options.copy(depth = 8f))!!
        assertNotNull(thin)
        assertTrue(deep.first >= thin.first)
    }

    @Test
    fun spectrumBendsRedFurthest() {
        assertEquals(1.08f, spectrum(0.4f)[0], 0f)
        assertEquals(1.04f, spectrum(0.4f)[1], 1e-6f)
        assertEquals(1f, spectrum(0.4f)[2], 0f)
        assertEquals(1f, spectrum(0f)[0], 0f)
    }

    @Test
    fun domeArcHasMeanSlopeOfAHalf() {
        val a = arc(6f, 30f)
        var sum = 0f
        val n = 200
        for (i in 0..n) {
            val s = slope(i.toFloat() / n * 30f, a)
            sum += if (i == 0 || i == n) s / 2f else s
        }
        assertEquals(0.5f, sum / n, 0.01f)
    }
}
