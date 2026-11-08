package dev.rovsti.design.chrome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabBarMinimizerTest {
    private val collapse = 40f
    private val expand = 8f
    private val src = NestedScrollSource.UserInput

    private fun TabBarMinimizer.scroll(dy: Float): Offset = onPostScroll(Offset(0f, dy), Offset.Zero, src)

    @Test
    fun minimisesOnlyPastFortyOfDownwardScroll() {
        val m = TabBarMinimizer(collapse, expand)
        m.scroll(-39f)
        assertFalse(m.minimized)
        m.scroll(-2f)
        assertTrue(m.minimized)
    }

    @Test
    fun expandsAfterEightOfUpwardScroll() {
        val m = TabBarMinimizer(collapse, expand)
        m.scroll(-50f)
        assertTrue(m.minimized)
        m.scroll(7f)
        assertTrue(m.minimized)
        m.scroll(2f)
        assertFalse(m.minimized)
    }

    @Test
    fun directionFlipRestartsTheTotal() {
        val m = TabBarMinimizer(collapse, expand)
        m.scroll(-30f)
        m.scroll(3f)
        m.scroll(-30f)
        assertFalse("30 + 30 with a flip between must not add up", m.minimized)
        m.scroll(-11f)
        assertTrue(m.minimized)
    }

    @Test
    fun pullingPastTheTopExpands() {
        val m = TabBarMinimizer(collapse, expand)
        m.scroll(-60f)
        assertTrue(m.minimized)
        m.onPostScroll(Offset.Zero, Offset(0f, 12f), src)
        assertFalse(m.minimized)
    }

    @Test
    fun neverConsumesAnyScroll() {
        val m = TabBarMinimizer(collapse, expand)
        assertEquals(Offset.Zero, m.scroll(-100f))
        assertEquals(Offset.Zero, m.scroll(100f))
    }

    @Test
    fun explicitExpandResetsTheTotal() {
        val m = TabBarMinimizer(collapse, expand)
        m.scroll(-60f)
        m.expand()
        assertFalse(m.minimized)
        m.scroll(-30f)
        assertFalse(m.minimized)
    }
}
