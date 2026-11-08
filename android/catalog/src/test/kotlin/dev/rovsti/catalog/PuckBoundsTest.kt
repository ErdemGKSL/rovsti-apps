package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC4: pressed and stretched, the puck never leaves the bar (owner complaint X4). */
class PuckBoundsTest : ShotTest() {
    private fun assertInside(label: String) {
        val bar = compose.onNodeWithTag("tab-surface").bounds
        val puck = compose.onNodeWithTag("tab-puck").bounds
        val eps = 0.6f
        assertTrue("$label: puck $puck vs bar $bar", puck.left >= bar.left - eps && puck.right <= bar.right + eps)
        assertTrue("$label: puck $puck vs bar $bar", puck.top >= bar.top - eps && puck.bottom <= bar.bottom + eps)
    }

    @Test
    fun puckStaysInsideTheBarWhilePressedAndFlicked() {
        composeFrame { TabBarPage(imeVisible = { false }) }
        val g = BarGeometry(this)
        assertInside("rest")
        // Rest: centred on its tab.
        assertEquals(
            g.slotCentre(0f).x,
            compose
                .onNodeWithTag("tab-puck")
                .bounds.center.x,
            1f,
        )
        assertEquals(
            g.surface.center.y,
            compose
                .onNodeWithTag("tab-puck")
                .bounds.center.y,
            1f,
        )

        val start = g.slotCentre(0f) - g.surface.topLeft
        compose.onNodeWithTag("tab-surface").performTouchInput { down(start) }
        advance(200)
        assertInside("held at the first slot")
        // A hard flick right to past the last slot, then back left past the first.
        for (target in listOf(4.5f, -1f, 4.5f, -1f)) {
            val to = (g.slotCentre(target) - g.surface.topLeft)
            compose.onNodeWithTag("tab-surface").performTouchInput { moveTo(Offset(to.x, start.y)) }
            repeat(4) {
                advance(16)
                assertInside("flick towards $target")
            }
        }
        compose.onNodeWithTag("tab-surface").performTouchInput { up() }
        repeat(30) {
            advance(16)
            assertInside("settling")
        }
        advance(800)
        assertInside("after")
    }
}
