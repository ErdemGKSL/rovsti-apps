// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Cases from glisse test/glass.test.tsx:209-240.
package dev.rovsti.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class RimTwinTest {
    private val w = 160f
    private val h = 56f
    private val r = 28f
    private val density = 3f

    /** Alpha of the pixel that contains (x, y) px from the top left, sampled at 3 px per px like glisse's test. */
    private fun alpha(
        x: Float,
        y: Float,
        shine: Float = 0.65f,
        edge: Float = 0f,
    ): Float {
        val px = (floor(y * density) + 0.5f) / density
        val qx = (floor(x * density) + 0.5f) / density
        return rimAlpha(qx, px, w, h, r, shine, edge, density)
    }

    @Test
    fun brightestAtTheEdgeAndGoneJustInside() {
        for ((x, y) in listOf(80f to 0f, 80f to 55.9f, 0f to 28f, 159.9f to 28f)) {
            assertTrue("edge at $x,$y", alpha(x, y) > 0.55f)
        }
        assertTrue(alpha(80f, 0.5f) < alpha(80f, 0f))
        assertTrue(alpha(80f, 0.5f) > 0.3f)
        assertTrue(alpha(80f, 1.1f) < 0.1f)
        assertEquals(0f, alpha(80f, 1.4f), 0f)
        assertEquals(0f, alpha(80f, 28f), 0f)
    }

    @Test
    fun litOnTopLeftAndBottomRightCorners() {
        fun corner(
            x: Float,
            y: Float,
            degrees: Double,
        ): Float {
            val a = degrees * PI / 180.0
            return alpha(x + (27.7 * cos(a)).toFloat(), y + (27.7 * sin(a)).toFloat())
        }
        assertTrue(corner(28f, 28f, -135.0) > 0.45f)
        assertTrue(corner(132f, 28f, 45.0) > 0.45f)
        assertTrue(corner(132f, 28f, -45.0) < 0.03f)
        assertTrue(corner(28f, 28f, 135.0) < 0.03f)
    }

    @Test
    fun shineScalesBrightness() {
        assertEquals(alpha(80f, 0f) * 0.3f / 0.65f, alpha(80f, 0f, shine = 0.3f), 0.01f)
    }

    private fun sample(
        x: Float,
        y: Float,
        shine: Float,
        edge: Float,
    ) = rimSample(
        (floor(x * density) + 0.5f) / density,
        (floor(y * density) + 0.5f) / density,
        w,
        h,
        r,
        shine,
        edge,
        density,
    )

    @Test
    fun hairlineLiesUnderTheLight() {
        assertEquals(0.08f, sample(80f, 0f, 0f, 0.08f).alpha, 1e-6f)
        assertEquals(0.04f, sample(80f, 0.4f, 0f, 0.08f).alpha, 1e-6f)
        assertEquals(0f, sample(80f, 0.7f, 0f, 0.08f).alpha, 1e-6f)
        val lit = sample(80f, 0f, 0.65f, 0.08f)
        assertEquals(alpha(80f, 0f) + 0.08f * (1f - alpha(80f, 0f)), lit.alpha, 1e-5f)
    }

    @Test
    fun shaderSourceDeclaresTheSameConstants() {
        assertTrue(RIM_AGSL.contains("RIM = 1.22"))
        assertTrue(RIM_AGSL.contains("EDGE = 0.5"))
        assertEquals(1.22f, RIM_WIDTH, 0f)
    }
}
