package dev.rovsti.design.overlay

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MenuMorphTest {
    private val from = Size(44f * 3, 44f * 3)
    private val to = Size(220f * 3, 192f * 3)
    private val px = 3f
    private val combos = listOf(false to false, true to false, false to true, true to true)

    private fun frame(
        t: Float,
        trailing: Boolean,
        upward: Boolean,
        leaving: Boolean = false,
    ) = menuMorphFrame(t, from, to, 60f, trailing, upward, leaving, px)

    @Test
    fun atZeroTheSurfaceIsTheTriggerRectAndContentIsInvisible() {
        for ((trailing, upward) in combos) {
            val f = frame(0f, trailing, upward)
            assertEquals(from.width, f.surfaceWidth, 0.001f)
            assertEquals(from.height, f.surfaceHeight, 0.001f)
            // The trigger sits in the card's corner: right edge when trailing, bottom edge when upward.
            assertEquals(if (trailing) to.width - from.width else 0f, f.surfaceLeft, 0.001f)
            assertEquals(if (upward) to.height - from.height else 0f, f.surfaceTop, 0.001f)
            assertEquals(0f, f.contentAlpha, 0f)
            assertEquals(1f, f.triggerAlpha, 0f)
            assertFalse(f.settled)
        }
    }

    @Test
    fun atOneTheFrameIsSettledAndTheSurfaceIsTheCard() {
        for ((trailing, upward) in combos) {
            val f = frame(1f, trailing, upward)
            assertEquals(0f, f.surfaceLeft, 0.001f)
            assertEquals(0f, f.surfaceTop, 0.001f)
            assertEquals(to.width, f.surfaceWidth, 0.001f)
            assertEquals(to.height, f.surfaceHeight, 0.001f)
            assertEquals(1f, f.contentScale, 0f)
            assertEquals(1f, f.contentAlpha, 0f)
            assertEquals(0f, f.contentBlurPx, 0f)
            assertTrue(f.settled)
        }
    }

    @Test
    fun theClipStaysInsideTheCardWhenMappedBackThroughTheScale() {
        for ((trailing, upward) in combos) {
            for (step in 0..20) {
                val f = frame(step / 20f, trailing, upward)

                fun mapX(x: Float) = f.originX + (x - f.originX) * f.contentScale

                fun mapY(y: Float) = f.originY + (y - f.originY) * f.contentScale
                val eps = 0.01f
                assertTrue("left $step", mapX(f.clipLeft) >= -eps)
                assertTrue("right $step", mapX(f.clipRight) <= to.width + eps)
                assertTrue("top $step", mapY(f.clipTop) >= -eps)
                assertTrue("bottom $step", mapY(f.clipBottom) <= to.height + eps)
                // And it is the surface rect.
                assertEquals(f.surfaceLeft, mapX(f.clipLeft), 0.01f)
                assertEquals(f.surfaceTop + f.surfaceHeight, mapY(f.clipBottom), 0.01f)
            }
        }
    }

    @Test
    fun leavingFadesTheContentEarlierThanEntering() {
        for (t in listOf(0.2f, 0.4f, 0.5f, 0.55f)) {
            val entering = frame(t, true, false, leaving = false).contentAlpha
            val leaving = frame(t, true, false, leaving = true).contentAlpha
            assertTrue("t=$t entering=$entering leaving=$leaving", leaving < entering)
        }
        assertEquals(0f, frame(0.55f, true, false, leaving = true).contentAlpha, 0f)
    }

    @Test
    fun theSurfaceGrowsMonotonically() {
        var last = 0f
        for (step in 0..20) {
            val f = frame(step / 20f, true, true)
            assertTrue(f.surfaceWidth >= last)
            last = f.surfaceWidth
        }
    }

    @Test
    fun resistanceSaturatesAtSevenDpAndIsOdd() {
        for (d in listOf(-4000f, -300f, -12f, 5f, 40f, 600f, 100_000f)) {
            val r = menuResistance(d, px)
            assertTrue("d=$d r=$r", abs(r) <= 7f * px + 0.001f)
            assertEquals(-r, menuResistance(-d, px), 0.0001f)
        }
        assertEquals(7f * px, menuResistance(1_000_000f, px), 0.01f)
        assertEquals(0f, menuResistance(0f, px), 0f)
        // Small pulls follow the finger at about 0.12 of its travel.
        assertEquals(0.12f * 2f, menuResistance(2f * px, px) / px, 0.01f)
    }

    @Test
    fun pressScaleNeverExceedsTwoAndAHalfPercent() {
        for ((w, h) in listOf(100f to 40f, 220f * px to 192f * px, 150f * px to 400f * px, 1f to 1f)) {
            val s = menuPressScale(w, h, px)
            assertTrue("s=$s", s > 1f && s <= 1.025f + 1e-6f)
        }
        // A 220 dp card grows by 5 dp on its long side.
        assertEquals(1f + 5f / 220f, menuPressScale(220f * px, 192f * px, px), 0.0001f)
    }
}
