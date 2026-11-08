package dev.rovsti.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScaleTest {
    @Test
    fun clearGlassCapturesAtFullResolution() {
        assertEquals(1f, captureScale(0f), 1e-6f)
    }

    @Test
    fun blurOfTwoAndAHalfDpOrMoreCapturesAtThirtyPercent() {
        assertEquals(0.3f, captureScale(2.5f), 1e-6f)
        assertEquals(0.3f, captureScale(12f), 1e-6f)
    }

    @Test
    fun scaleFallsLinearlyAndNeverLeavesTheRange() {
        assertEquals(0.65f, captureScale(1.25f), 1e-6f)
        assertEquals(1f, captureScale(-3f), 1e-6f)
        var last = 2f
        for (i in 0..40) {
            val s = captureScale(i * 0.1f)
            assertTrue(s <= last)
            assertTrue(s in 0.3f..1f)
            last = s
        }
    }

    @Test
    fun specUsesItsOwnBlur() {
        assertEquals(0.3f, specFor(blur = 3f).captureScale, 1e-6f)
    }

    @Test
    fun replayIsDrawnUnderTheInverseOfThePressScale() {
        assertEquals(0.3f, replayScale(0.3f, 1f), 1e-6f)
        assertEquals(0.3f / 1.1f, replayScale(0.3f, 1.1f), 1e-6f)
        assertEquals(0.3f, replayScale(0.3f, 0f), 1e-6f)
    }

    private fun specFor(blur: Float) =
        GlassSpec(
            tier = GlassTier.Liquid,
            blurDp = blur,
            saturation = 1.5f,
            lens = null,
            lensAmount = 0f,
            fill = androidx.compose.ui.graphics.Color.White,
            flatBase = androidx.compose.ui.graphics.Color.White,
            scrim = androidx.compose.ui.graphics.Color.Transparent,
            rimShine = 0f,
            edge = androidx.compose.ui.graphics.Color.Black,
            flatEdge = androidx.compose.ui.graphics.Color.Black,
            cornerDp = 8f,
            shadow = GlassShadow.None,
        )
}
