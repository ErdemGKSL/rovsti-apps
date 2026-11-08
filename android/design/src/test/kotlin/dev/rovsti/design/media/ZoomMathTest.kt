package dev.rovsti.design.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    private val viewport = Size(1080f, 2340f)
    private val wide = Size(4000f, 3000f)
    private val fitted = ZoomMath.fit(wide, viewport)

    @Test
    fun fitLetterboxesWideTallAndSquare() {
        val w = ZoomMath.fit(Size(4000f, 3000f), viewport)
        assertEquals(1080f, w.width, 0.01f)
        assertEquals(810f, w.height, 0.01f)
        val t = ZoomMath.fit(Size(1000f, 4000f), viewport)
        assertEquals(585f, t.width, 0.01f)
        assertEquals(2340f, t.height, 0.01f)
        val s = ZoomMath.fit(Size(500f, 500f), viewport)
        assertEquals(1080f, s.width, 0.01f)
        assertEquals(1080f, s.height, 0.01f)
    }

    @Test
    fun fitOfUnknownContentIsTheViewport() {
        assertEquals(viewport, ZoomMath.fit(Size.Zero, viewport))
    }

    @Test
    fun pinchKeepsTheFocusPointUnderTheFingers() {
        val focus = Offset(200f, -300f)
        val start = ZoomState(2f, Offset(100f, 50f))
        // The content point under the focus before and after.
        val before = (focus - start.offset) / start.scale
        val after = ZoomMath.pinch(start, 1.5f, focus, Offset.Zero, fitted, viewport)
        assertEquals(3f, after.scale, 0.001f)
        val pointAfter = (focus - after.offset) / after.scale
        val limit = ZoomMath.maxOffset(fitted, viewport, after.scale)
        // Inside bounds the point must not move; if the clamp bit, the offset sits exactly on a bound.
        val clamped = kotlin.math.abs(after.offset.x) == limit.x || kotlin.math.abs(after.offset.y) == limit.y
        assertTrue(clamped || (pointAfter - before).getDistance() < 0.01f)
    }

    @Test
    fun pinchFocusIsExactWhenThereIsRoom() {
        val tall = ZoomMath.fit(Size(1000f, 4000f), viewport)
        val focus = Offset(40f, 60f)
        val start = ZoomState(3f, Offset(10f, 10f))
        val after = ZoomMath.pinch(start, 1.2f, focus, Offset.Zero, tall, viewport)
        val before = (focus - start.offset) / start.scale
        val point = (focus - after.offset) / after.scale
        assertEquals(before.x, point.x, 0.01f)
        assertEquals(before.y, point.y, 0.01f)
    }

    @Test
    fun pinchRubberBandsPastTheScaleRange() {
        var s = ZoomState.Fit
        repeat(30) { s = ZoomMath.pinch(s, 0.9f, Offset.Zero, Offset.Zero, fitted, viewport) }
        assertTrue("below 1 but never under 0.8: ${s.scale}", s.scale < 1f && s.scale > ZoomMath.RubberMinScale)
        s = ZoomState(5f, Offset.Zero)
        repeat(30) { s = ZoomMath.pinch(s, 1.2f, Offset.Zero, Offset.Zero, fitted, viewport) }
        assertTrue("above 5 but never over 6: ${s.scale}", s.scale > 5f && s.scale < ZoomMath.RubberMaxScale)
    }

    @Test
    fun rubberScaleIsIdentityInsideTheRange() {
        for (v in listOf(1f, 2f, 4.9f, 5f)) assertEquals(v, ZoomMath.rubberScale(v), 0f)
    }

    @Test
    fun panClampsAtMaxOffsetAndReportsTheOverflow() {
        val start = ZoomState(2f, Offset.Zero)
        val limit = ZoomMath.maxOffset(fitted, viewport, 2f)
        assertEquals(540f, limit.x, 0.01f)
        assertEquals(0f, limit.y, 0.01f) // 810 * 2 = 1620 < 2340: no vertical room
        val result = ZoomMath.pan(start, Offset(700f, 40f), fitted, viewport)
        assertEquals(540f, result.state.offset.x, 0.01f)
        assertEquals(0f, result.state.offset.y, 0.01f)
        assertEquals(160f, result.overflow.x, 0.01f)
        assertEquals(40f, result.overflow.y, 0.01f)
    }

    @Test
    fun panInsideBoundsMovesFreely() {
        val r = ZoomMath.pan(ZoomState(3f, Offset.Zero), Offset(100f, 0f), fitted, viewport)
        assertEquals(100f, r.state.offset.x, 0.01f)
        assertEquals(Offset.Zero, r.overflow)
    }

    @Test
    fun doubleTapTogglesBetweenFitAndTwoAndAHalf() {
        val zoomedIn = ZoomMath.doubleTap(ZoomState.Fit, Offset(100f, 0f), fitted, viewport)
        assertEquals(ZoomMath.DoubleTapScale, zoomedIn.scale, 0f)
        // The tapped point stays under the finger: offset = focus * (1 - 2.5), clamped.
        assertEquals(-150f, zoomedIn.offset.x, 0.01f)
        assertEquals(ZoomState.Fit, ZoomMath.doubleTap(zoomedIn, Offset.Zero, fitted, viewport))
    }

    @Test
    fun doubleTapNearTheEdgeClampsTheOffset() {
        val r = ZoomMath.doubleTap(ZoomState.Fit, Offset(700f, 0f), fitted, viewport)
        val limit = ZoomMath.maxOffset(fitted, viewport, ZoomMath.DoubleTapScale)
        assertEquals(-limit.x, r.offset.x, 0.01f)
    }

    @Test
    fun settledReturnsIntoBounds() {
        val s = ZoomMath.settled(ZoomState(0.85f, Offset(30f, 30f)), fitted, viewport)
        assertEquals(ZoomState.Fit, s)
        val big = ZoomMath.settled(ZoomState(6f, Offset(9000f, 0f)), fitted, viewport)
        assertEquals(5f, big.scale, 0f)
        assertEquals(ZoomMath.maxOffset(fitted, viewport, 5f).x, big.offset.x, 0.01f)
    }

    @Test
    fun dismissProgressIsAboutHalfAtOneHundredTwentyDp() {
        val density = 2.625f
        val p = ZoomMath.dismissProgress(120f * density, 891f * density)
        assertEquals(0.54f, p, 0.02f)
        assertEquals(1f, ZoomMath.dismissProgress(9000f, 2340f), 0f)
        assertEquals(0f, ZoomMath.dismissProgress(0f, 2340f), 0f)
        assertEquals(p, ZoomMath.dismissProgress(-120f * density, 891f * density), 0f)
    }

    @Test
    fun dismissThresholds() {
        val h = 2000f
        assertFalse(ZoomMath.shouldDismiss(100f, 0f, h))
        assertTrue(ZoomMath.shouldDismiss(520f, 0f, h)) // past 25 %
        assertTrue(ZoomMath.shouldDismiss(120f, 2500f, h)) // a flick projected past it
        assertFalse(ZoomMath.shouldDismiss(120f, -2500f, h)) // flicked back
        assertFalse(ZoomMath.shouldDismiss(120f, 300f, h)) // too slow to project
        assertTrue(ZoomMath.shouldDismiss(-520f, 0f, h)) // upwards works too
    }

    @Test
    fun pageDragIsRubberAtTheEnds() {
        val w = 1100f
        assertEquals(200f, ZoomMath.pageDrag(200f, w, index = 3, count = 7), 0f)
        assertEquals(-200f, ZoomMath.pageDrag(-200f, w, index = 3, count = 7), 0f)
        val first = ZoomMath.pageDrag(400f, w, index = 0, count = 7)
        assertTrue(first in 1f..399f)
        val last = ZoomMath.pageDrag(-400f, w, index = 6, count = 7)
        assertTrue(last in -399f..-1f)
        assertEquals(-200f, ZoomMath.pageDrag(-200f, w, index = 0, count = 7), 0f)
    }

    @Test
    fun settlePageTurnsOnHalfAPageOrAFlickAndNeverLeavesTheRange() {
        val w = 1100f
        assertEquals(3, ZoomMath.settlePage(-200f, 0f, w, index = 3, count = 7))
        assertEquals(4, ZoomMath.settlePage(-700f, 0f, w, index = 3, count = 7))
        assertEquals(4, ZoomMath.settlePage(-150f, -3000f, w, index = 3, count = 7))
        assertEquals(2, ZoomMath.settlePage(300f, 6000f, w, index = 3, count = 7))
        assertEquals(0, ZoomMath.settlePage(900f, 6000f, w, index = 0, count = 7))
        assertEquals(6, ZoomMath.settlePage(-900f, -6000f, w, index = 6, count = 7))
    }
}
