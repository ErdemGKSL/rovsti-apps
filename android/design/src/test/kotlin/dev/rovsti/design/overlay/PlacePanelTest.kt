package dev.rovsti.design.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pixel maths at density 1: a 400 x 800 window, 8 gap, 12 margin. */
class PlacePanelTest {
    private val window = Size(400f, 800f)
    private val safe = Rect(0f, 24f, 400f, 776f)
    private val panel = Size(200f, 160f)
    private val gap = 8f
    private val margin = 12f

    private fun place(
        anchor: Rect,
        panel: Size = this.panel,
        window: Size = this.window,
        safe: Rect = this.safe,
        rtl: Boolean = false,
    ) = placePanel(anchor, panel, window, safe, gap, margin, rtl)

    private fun assertInside(
        p: Placement,
        panel: Size = this.panel,
        safe: Rect = this.safe,
    ) {
        assertTrue("left ${p.topLeft.x}", p.topLeft.x >= safe.left + margin - 0.01f)
        assertTrue("right", p.topLeft.x + panel.width <= safe.right - margin + 0.01f)
        assertTrue("top ${p.topLeft.y}", p.topLeft.y >= safe.top + margin - 0.01f)
        assertTrue("bottom", p.topLeft.y + panel.height <= safe.bottom - margin + 0.01f || panel.height > safe.height)
        assertTrue(p.origin.x in 0f..1f && p.origin.y in 0f..1f)
    }

    @Test
    fun topLeftCornerOpensBelowAndStartsAtTheAnchor() {
        val p = place(Rect(16f, 40f, 60f, 84f))
        assertEquals(Side.Below, p.side)
        assertEquals(84f + gap, p.topLeft.y, 0f)
        assertEquals(16f, p.topLeft.x, 0f)
        assertInside(p)
    }

    @Test
    fun topRightCornerAlignsTheTrailingEdge() {
        val p = place(Rect(340f, 40f, 384f, 84f))
        assertEquals(Side.Below, p.side)
        assertEquals(384f - panel.width, p.topLeft.x, 0f)
        assertInside(p)
    }

    @Test
    fun bottomCornersFlipAbove() {
        val left = place(Rect(16f, 700f, 60f, 744f))
        assertEquals(Side.Above, left.side)
        assertEquals(700f - gap - panel.height, left.topLeft.y, 0f)
        assertInside(left)
        val right = place(Rect(340f, 700f, 384f, 744f))
        assertEquals(Side.Above, right.side)
        assertEquals(384f - panel.width, right.topLeft.x, 0f)
        assertInside(right)
    }

    @Test
    fun centreOpensBelow() {
        val p = place(Rect(170f, 380f, 230f, 424f))
        assertEquals(Side.Below, p.side)
        assertInside(p)
    }

    @Test
    fun aPanelTallerThanTheFreeSpaceClampsBelowInsideTheMargin() {
        val tall = Size(200f, 900f)
        val p = place(Rect(170f, 380f, 230f, 424f), panel = tall)
        assertEquals(Side.Below, p.side)
        assertTrue(p.topLeft.y >= safe.top + margin)
        assertEquals(safe.top + margin, p.topLeft.y, 0f)
    }

    @Test
    fun maxHeightIsTheSafeHeightMinusBothMargins() {
        assertEquals(752f - 24f, panelMaxHeight(safe, margin), 0f)
        assertEquals(0f, panelMaxHeight(Rect(0f, 0f, 10f, 10f), 20f), 0f)
    }

    @Test
    fun rtlMirrorsTheHorizontalAlignment() {
        val anchor = Rect(340f, 40f, 384f, 84f)
        val ltr = place(anchor)
        val rtl = place(anchor, rtl = true)
        assertEquals(384f - panel.width, ltr.topLeft.x, 0f)
        // RTL wants the leading (left) edge at 340, but 340 + 200 overflows the right margin, so it is clamped.
        assertEquals(safe.right - margin - panel.width, rtl.topLeft.x, 0f)
        val left = Rect(16f, 40f, 60f, 84f)
        assertEquals(margin, place(left, rtl = true).topLeft.x, 0f)
    }

    @Test
    fun landscapeStaysInsideTheShortWindow() {
        val land = Size(891f, 411f)
        val landSafe = Rect(0f, 24f, 891f, 411f)
        val p = place(Rect(800f, 300f, 844f, 344f), window = land, safe = landSafe)
        assertEquals(Side.Above, p.side)
        assertInside(p, safe = landSafe)
        val big = place(Rect(400f, 150f, 444f, 194f), panel = Size(200f, 300f), window = land, safe = landSafe)
        assertEquals(Side.Below, big.side)
        // Neither side fits: clamped so the panel's bottom meets the margin, top still inside it.
        assertEquals(landSafe.bottom - margin - 300f, big.topLeft.y, 0f)
        assertInside(big, panel = Size(200f, 300f), safe = landSafe)
    }

    @Test
    fun aCutoutOnTheLeftPushesThePanelIn() {
        val cut = Rect(32f, 24f, 400f, 776f)
        val p = place(Rect(0f, 40f, 44f, 84f), safe = cut)
        assertEquals(32f + margin, p.topLeft.x, 0f)
        assertInside(p, safe = cut)
    }

    @Test
    fun anOpenKeyboardFlipsThePanelAbove() {
        val anchor = Rect(170f, 400f, 230f, 444f)
        assertEquals(Side.Below, place(anchor).side)
        val ime = Rect(safe.left, safe.top, safe.right, safe.bottom - 300f)
        val p = place(anchor, safe = ime)
        assertEquals(Side.Above, p.side)
        assertInside(p, safe = ime)
    }

    @Test
    fun originPointsAtTheAnchorCentre() {
        val anchor = Rect(340f, 40f, 384f, 84f)
        val p = place(anchor)
        assertEquals((anchor.center.x - p.topLeft.x) / panel.width, p.origin.x, 1e-5f)
        // The anchor lies above a panel opened below it, so the vertical origin pins to the top edge.
        assertEquals(0f, p.origin.y, 0f)
        val above = place(Rect(170f, 700f, 230f, 744f))
        assertEquals(1f, above.origin.y, 0.35f)
        assertTrue(above.origin.y > 0.9f)
    }

    @Test
    fun aZeroSizedPanelHasACentredOrigin() {
        val p = place(Rect(100f, 100f, 140f, 140f), panel = Size(0f, 0f))
        assertEquals(0.5f, p.origin.x, 0f)
    }
}
