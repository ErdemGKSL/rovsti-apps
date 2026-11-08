package dev.rovsti.design.overlay

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetMathTest {
    private val density = 2.625f
    private val height = 891f * density
    private val status = 28f * density
    private val all = listOf(Detent.Large, Detent.Peek(), Detent.Medium)

    private fun offsets() = detentOffsets(all, height, status, density)

    @Test
    fun offsetsAreAscendingAndTheTallestIsLargeUnderTheStatusBar() {
        val o = offsets()
        assertEquals(3, o.size)
        assertTrue(o[0] < o[1] && o[1] < o[2])
        assertEquals(status + 24f * density, o[0], 0.01f)
        assertEquals(height * 0.5f, o[1], 0.01f)
        assertEquals(height - 120f * density, o[2], 0.01f)
    }

    @Test
    fun detentsAboveLargeAreClampedAndDuplicatesMerged() {
        val o = detentOffsets(listOf(Detent.Fixed(5000.dp), Detent.Large, Detent.Fraction(1f)), height, status, density)
        assertEquals(1, o.size)
        assertEquals(status + 24f * density, o[0], 0.01f)
    }

    @Test
    fun aWindowUnder480DpTallGetsLargeOnly() {
        val h = 411f * density
        val o = detentOffsets(all, h, 24f * density, density)
        assertEquals(1, o.size)
        assertEquals(48f * density, o[0], 0.01f)
        assertEquals(1, detentOffsets(listOf(Detent.Peek()), h, 0f, density).size)
        // Exactly 480 dp is not compact.
        assertEquals(3, detentOffsets(all, 480f * density, status, density).size)
    }

    @Test
    fun aSlowReleaseSettlesOnTheNearestDetent() {
        val o = offsets()
        val nearMedium = o[1] + 40f
        assertEquals(SheetTarget.Snap(o[1]), sheetReleaseTarget(nearMedium, 0f, o, height, true))
        val nearLarge = o[0] + 30f
        assertEquals(SheetTarget.Snap(o[0]), sheetReleaseTarget(nearLarge, 0f, o, height, true))
    }

    @Test
    fun aFlingProjectsWithPointNineNineEight() {
        val o = offsets()
        // 3000 px/s projects 3000/1000 * .998/.002 = 1497 px.
        val up = sheetReleaseTarget(o[1], -3000f, o, height, true)
        assertEquals(SheetTarget.Snap(o[0]), up)
        val down = sheetReleaseTarget(o[0], 1000f, o, height, true)
        // 1000 px/s projects 499 px: not enough to leave Large.
        assertEquals(SheetTarget.Snap(o[0]), down)
        val mediumDown = sheetReleaseTarget(o[1], 2000f, o, height, true)
        assertEquals(SheetTarget.Snap(o[2]), mediumDown)
    }

    @Test
    fun dismissesWhenProjectedPastTheLowestDetentPlusTwelvePercent() {
        val o = offsets()
        val limit = o.last() + 0.12f * height
        // Rest at limit - 1 px: stays.
        val vSlow = (limit - 1f - o.last()) * 1000f * (1f - 0.998f) / 0.998f
        assertTrue(sheetReleaseTarget(o.last(), vSlow, o, height, true) is SheetTarget.Snap)
        // 1 px more: leaves.
        val vFast = (limit + 1f - o.last()) * 1000f * (1f - 0.998f) / 0.998f
        assertEquals(SheetTarget.Dismiss, sheetReleaseTarget(o.last(), vFast, o, height, true))
        // A sheet that cannot be dismissed never leaves.
        assertTrue(sheetReleaseTarget(o.last(), vFast * 3, o, height, false) is SheetTarget.Snap)
    }

    @Test
    fun fractionIsOneAtTheLowestDetentAndZeroWhenGone() {
        val o = offsets()
        assertEquals(1f, sheetFraction(o.last(), o.last(), height), 1e-4f)
        assertEquals(1f, sheetFraction(o[0], o.last(), height), 1e-4f)
        assertEquals(0f, sheetFraction(height, o.last(), height), 1e-4f)
        assertEquals(0.5f, sheetFraction((o.last() + height) / 2f, o.last(), height), 1e-3f)
    }

    @Test
    fun draggingAboveTheTopResistsAndBelowTheBottomOnlyWhenPinned() {
        val top = 100f
        val bottom = 700f
        assertEquals(400f, sheetDragOffset(400f, top, bottom, 1000f, true), 0f)
        val stretched = sheetDragOffset(top - 200f, top, bottom, 1000f, true)
        assertTrue(stretched < top && stretched > top - 200f)
        assertEquals(900f, sheetDragOffset(900f, top, bottom, 1000f, true), 0f)
        assertEquals(1000f, sheetDragOffset(1200f, top, bottom, 1000f, true), 0f)
        val pinned = sheetDragOffset(900f, top, bottom, 1000f, false)
        assertTrue(pinned > bottom && pinned < 900f)
    }

    @Test
    fun grabberStretchesWithTanh() {
        val (sx0, sy0) = grabberStretch(0f, 100f)
        assertEquals(1f, sx0, 0f)
        assertEquals(1f, sy0, 0f)
        val (sx, sy) = grabberStretch(1000f, 100f)
        assertEquals(1.7f, sx, 1e-3f)
        assertEquals(0.7f, sy, 1e-3f)
        assertEquals(grabberStretch(50f, 100f), grabberStretch(-50f, 100f))
    }

    @Test
    fun backPreviewTravelsAtMostThirtyFivePercentEasedOut() {
        assertEquals(100f, backPreviewOffset(100f, 900f, 0f), 0f)
        assertEquals(100f + 800f * 0.35f, backPreviewOffset(100f, 900f, 1f), 1e-3f)
        assertEquals(100f + 800f * 0.35f * 0.75f, backPreviewOffset(100f, 900f, 0.5f), 1e-3f)
    }

    @Test
    fun aSteppedBackSheetKeepsItsPivotAndMovesUp() {
        assertEquals(190f, stackedTopPx(200f, 10f), 0.001f)
    }

    @Test
    fun theUpperSheetStaysTenDpUnderTheSteppedBackTop() {
        val step = 10f * density
        val gap = 10f * density
        val lower = 300f
        // Already low enough: untouched.
        assertEquals(500f, upperDetentOffset(500f, lower, step, gap), 0.001f)
        // At the lower sheet's own top: stepped-back top + gap equals the lower top.
        assertEquals(lower, upperDetentOffset(lower - 40f, lower, step, gap), 0.001f)
        // No sheet below: untouched.
        assertEquals(12f, upperDetentOffset(12f, Float.NaN, step, gap), 0.001f)
    }
}
