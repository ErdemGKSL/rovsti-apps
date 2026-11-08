package dev.rovsti.design.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCardLayoutTest {
    private val d = 2f

    private fun dp(v: Float) = v * d

    private val window = Size(dp(411f), dp(891f))
    private val safe = Rect(0f, dp(28f), dp(411f), dp(891f - 24f))
    private val panel = Size(dp(220f), dp(212f))

    private fun layout(
        item: Rect,
        window: Size = this.window,
        safe: Rect = this.safe,
        cardHeight: Float = dp(216f),
        rtl: Boolean = false,
    ) = previewCardLayout(item, window, safe, panel, cardHeight, d, rtl)

    private fun tile(
        left: Float,
        top: Float,
        width: Float = 180f,
        height: Float = 120f,
    ) = Rect(dp(left), dp(top), dp(left + width), dp(top + height))

    @Test
    fun leftHalfItemLeftAlignsCardAndPanel() {
        val l = layout(tile(16f, 200f))
        assertFalse(l.trailing)
        assertEquals(dp(16f), l.card.left, 0.5f)
        assertEquals(l.card.left, l.panel.left, 0.5f)
    }

    @Test
    fun rightHalfItemRightAlignsCardAndPanel() {
        val l = layout(tile(215f, 200f))
        assertTrue(l.trailing)
        assertEquals(dp(411f - 16f), l.card.right, 0.5f)
        assertEquals(l.card.right, l.panel.right, 0.5f)
    }

    @Test
    fun cardIsAtMostSeventyEightPercentOrThreeHundredFortyDp() {
        assertEquals(dp(0.78f * 411f), layout(tile(16f, 200f)).card.width, 1f)
        val wide = Size(dp(900f), dp(891f))
        val l = layout(tile(16f, 200f), window = wide, safe = Rect(0f, dp(28f), wide.width, dp(867f)))
        assertEquals(dp(340f), l.card.width, 0.5f)
    }

    @Test
    fun groupStartsAtTheItemAndGapIsEightDp() {
        val l = layout(tile(16f, 200f))
        assertEquals(dp(200f), l.card.top, 0.5f)
        assertEquals(l.card.bottom + dp(8f), l.panel.top, 0.5f)
    }

    @Test
    fun nearTheBottomTheGroupSlidesUpToTheBottomLimit() {
        val l = layout(tile(16f, 700f))
        assertEquals(safe.bottom - dp(16f), l.panel.bottom, 0.5f)
        assertEquals(dp(216f), l.card.height, 0.5f)
    }

    @Test
    fun nearTheTopTheGroupStaysBelowTheStatusBarClearance() {
        val l = layout(tile(16f, 0f))
        assertEquals(safe.top + dp(8f), l.card.top, 0.5f)
    }

    @Test
    fun smallWindowShrinksTheCardButNeverBelowOneFortyDp() {
        val small = Size(dp(411f), dp(480f))
        val smallSafe = Rect(0f, dp(28f), dp(411f), dp(456f))
        val l = layout(tile(16f, 100f), window = small, safe = smallSafe)
        assertTrue(l.card.height < dp(216f))
        assertTrue(l.card.height >= dp(140f) - 0.5f)
        assertTrue(l.panel.bottom <= smallSafe.bottom - dp(16f) + 0.5f)
    }

    @Test
    fun cardNeverDropsBelowTheMinimumEvenWhenNothingFits() {
        val tiny = Size(dp(300f), dp(300f))
        val tinySafe = Rect(0f, dp(20f), dp(300f), dp(280f))
        val l = layout(tile(16f, 40f, 120f, 80f), window = tiny, safe = tinySafe, cardHeight = dp(300f))
        assertEquals(dp(140f), l.card.height, 0.5f)
        assertTrue(l.card.width >= dp(140f))
        assertEquals(tinySafe.top + dp(8f), l.card.top, 0.5f)
    }

    @Test
    fun aCentredFullWidthRowFollowsTheLayoutDirection() {
        val row = Rect(0f, dp(300f), dp(411f), dp(364f))
        assertFalse(layout(row).trailing)
        assertTrue(layout(row, rtl = true).trailing)
    }

    @Test
    fun cardStaysInsideTheSideMargins() {
        for (x in listOf(0f, 100f, 231f)) {
            val l = layout(tile(x, 200f))
            assertTrue(l.card.left >= dp(16f) - 0.5f)
            assertTrue(l.card.right <= dp(411f - 16f) + 0.5f)
        }
    }

    @Test
    fun dismissalRule() {
        val home = Offset(0f, -dp(300f))
        assertTrue(shouldDismissPreview(Offset(0f, dp(80f)), home, dp(64f)))
        assertFalse(shouldDismissPreview(Offset(0f, dp(40f)), home, dp(64f)))
        assertTrue(shouldDismissPreview(Offset(0f, -dp(130f)), home, dp(64f)))
        assertFalse(shouldDismissPreview(Offset(0f, -dp(100f)), home, dp(64f)))
        assertEquals(0f, progressTowardHome(Offset(5f, 5f), Offset.Zero), 0f)
    }
}
