package dev.rovsti.design.chrome

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabBarGeometryTest {
    private val bar = 379f
    private val h = TabBarSpec.BarHeightDp
    private val pad = TabBarSpec.PaddingDp
    private val n = 4

    private fun rect(
        position: Float,
        lift: Float,
        squash: Float,
        width: Float = bar,
    ) = puckRect(
        position,
        lift,
        squash,
        width,
        h,
        n,
        pad,
        TabBarSpec.CellHeightDp,
        TabBarSpec.GrowXDp,
        TabBarSpec.GrowYDp,
        TabBarSpec.PuckInsetDp,
    )

    @Test
    fun puckStaysInsideInnerRectForEveryLiftAndSquashAtBothEnds() {
        val inner = innerRect(bar, h, TabBarSpec.PuckInsetDp)
        for (position in listOf(-0.5f, 0f, 1.5f, 3f, 3.6f)) {
            for (li in 0..10) {
                for (sq in 0..8) {
                    val r = rect(position, li / 10f, sq / 40f)
                    assertTrue("left $r at $position $li $sq", r.left >= inner.left - 1e-3f)
                    assertTrue("right $r", r.right <= inner.right + 1e-3f)
                    assertTrue("top $r", r.top >= inner.top - 1e-3f)
                    assertTrue("bottom $r", r.bottom <= inner.bottom + 1e-3f)
                }
            }
        }
    }

    @Test
    fun squashBeyondTwentyPercentIsClamped() {
        assertEquals(rect(1f, 1f, 0.2f), rect(1f, 1f, 5f))
    }

    @Test
    fun restPuckIsCentredOnItsSlot() {
        val slot = slotWidth(bar, n, pad)
        for (i in 0 until n) {
            val r = rect(i.toFloat(), 0f, 0f)
            assertEquals(pad + (i + 0.5f) * slot, r.center.x, 0.01f)
            assertEquals(h / 2f, r.center.y, 0.01f)
            assertEquals(slot, r.width, 0.01f)
        }
    }

    @Test
    fun liftedPuckGrowsByTheLensGrowUntilTheBarStopsIt() {
        val rest = rect(1f, 0f, 0f)
        val held = rect(1f, 1f, 0f)
        assertEquals(rest.width + TabBarSpec.GrowXDp, held.width, 0.01f)
        // Taller than the bar's inner area allows: clamped, so the lens is never taller than the bar.
        assertTrue(held.height <= h - 2 * TabBarSpec.PuckInsetDp + 1e-3f)
        assertTrue(held.height > rest.height)
    }

    @Test
    fun slotUnderMapsXToTheNearestSlot() {
        val slot = slotWidth(bar, n, pad)
        assertEquals(0, slotUnder(-20f, bar, n, pad))
        assertEquals(0, slotUnder(pad + slot - 1f, bar, n, pad))
        assertEquals(1, slotUnder(pad + slot + 1f, bar, n, pad))
        assertEquals(3, slotUnder(bar + 50f, bar, n, pad))
    }

    @Test
    fun slotAtRoundsAndClamps() {
        assertEquals(2, slotAt(1.6f, 4))
        assertEquals(1, slotAt(1.4f, 4))
        assertEquals(3, slotAt(9f, 4))
        assertEquals(0, slotAt(-3f, 4))
    }

    @Test
    fun tapSlopIsTwentyFourDpOffTheBar() {
        val slop = TabBarSpec.TapSlopDp
        assertTrue(isTap(h + slop - 0.1f, h, slop))
        assertTrue(isTap(-slop + 0.1f, h, slop))
        assertFalse(isTap(h + slop + 0.1f, h, slop))
        assertFalse(isTap(-slop - 0.1f, h, slop))
    }

    @Test
    fun constantsMatchGlisseIconsVariant() {
        assertEquals(72f, TabBarSpec.CellWidthDp, 0f)
        assertEquals(52f, TabBarSpec.CellHeightDp, 0f)
        assertEquals(60f, TabBarSpec.BarHeightDp, 0f)
        assertEquals(30f, TabBarSpec.CornerDp, 0f)
        assertEquals(6f, TabBarSpec.DragStartDp, 0f)
        assertEquals(60.dp, dev.rovsti.design.theme.Sizes.tabBarHeight)
    }

    @Test
    fun foldIsMonotonicAndLabelsGoInTheFirstThird() {
        var prevWidth = Float.MAX_VALUE
        var prevOthers = 2f
        for (i in 0..20) {
            val g = foldGeometry(i / 20f, 380f, 60f)
            assertTrue(g.width <= prevWidth + 1e-3f)
            assertTrue(g.otherAlpha <= prevOthers + 1e-3f)
            prevWidth = g.width
            prevOthers = g.otherAlpha
        }
        assertEquals(380f, foldGeometry(0f, 380f, 60f).width, 0f)
        assertEquals(60f, foldGeometry(1f, 380f, 60f).width, 0f)
        assertEquals(1f, foldGeometry(0f, 380f, 60f).labelAlpha, 0f)
        assertEquals(0f, foldGeometry(1f / 3f, 380f, 60f).labelAlpha, 1e-5f)
        assertEquals(-1f, foldGeometry(1f, 380f, 60f).anchorBias, 0f)
    }
}
