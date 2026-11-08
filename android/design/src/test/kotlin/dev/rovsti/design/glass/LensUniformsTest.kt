// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.glass

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensUniformsTest {
    @Test
    fun namesAndOrderMatchTheShader() {
        val u = lensUniforms(Size(200f, 100f), 50f, LensOptions.Glass, 1f)
        assertEquals(LensUniformNames, u.map { it.first })
        for (name in LensUniformNames) {
            assertTrue("shader declares $name", LENS_AGSL.contains(" $name;"))
        }
        assertTrue(LENS_AGSL.contains("uniform shader content;"))
    }

    @Test
    fun relativeGlassUsesShareOfShorterSide() {
        val u = lensUniforms(Size(200f, 100f), 50f, LensOptions.Glass, 1f).toMap()
        // depth = 0.25 * 100 = 25 px; inner half = (100 - 25, 50 - 25).
        assertArrayEquals(floatArrayOf(75f, 25f), u.getValue("innerHalf"), 1e-4f)
        // reach = 0.45 * 100 * 1.
        assertArrayEquals(floatArrayOf(45f), u.getValue("reach"), 1e-4f)
        assertArrayEquals(floatArrayOf(25f), u.getValue("splayReach"), 1e-4f)
        assertArrayEquals(floatArrayOf(1f / (25f * 1.4142135f)), u.getValue("ramp"), 1e-6f)
        assertArrayEquals(floatArrayOf(1.08f, 1.04f, 1f), u.getValue("spectrum"), 1e-6f)
        assertArrayEquals(floatArrayOf(200f, 100f), u.getValue("size"), 0f)
    }

    @Test
    fun amountScalesOnlyTheReach() {
        val full = lensUniforms(Size(200f, 100f), 50f, LensOptions.Glass, 1f).toMap()
        val third = lensUniforms(Size(200f, 100f), 50f, LensOptions.Glass, 0.3f).toMap()
        assertEquals(full.getValue("reach")[0] * 0.3f, third.getValue("reach")[0], 1e-4f)
        assertArrayEquals(full.getValue("innerHalf"), third.getValue("innerHalf"), 0f)
        assertArrayEquals(
            lensUniforms(Size(200f, 100f), 50f, LensOptions.Glass, 0f).toMap().getValue("reach"),
            floatArrayOf(0f),
            0f,
        )
    }

    @Test
    fun absoluteLensUsesUnitAndNoDomeMeansNoArc() {
        val u = lensUniforms(Size(120f, 60f), 30f, LensOptions.TabLens, 1f, unit = 3f).toMap()
        // depth 4 dp at 3 px/dp = 12 px.
        assertArrayEquals(floatArrayOf(48f, 18f), u.getValue("innerHalf"), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f), u.getValue("arcR"), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f), u.getValue("arcS"), 0f)
        assertArrayEquals(floatArrayOf(0.2f * 60f), u.getValue("reach"), 1e-4f)
    }

    @Test
    fun domeLensHasArcsAndCornerIsClamped() {
        val u = lensUniforms(Size(120f, 60f), 500f, LensOptions.SwitchThumb, 1f, unit = 2f).toMap()
        assertArrayEquals(floatArrayOf(30f), u.getValue("radius"), 0f)
        val arcR = u.getValue("arcR")
        assertTrue(arcR[0] > 0f && arcR[1] > 0f)
        assertTrue(u.getValue("arcS")[0] > 0f)
    }
}
