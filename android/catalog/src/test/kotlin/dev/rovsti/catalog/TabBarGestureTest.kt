package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.RovstiTabBar
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabBarGestureTest : ShotTest() {
    private var selected = 0
    private var reselected = -1
    private var scrollValue = { 0 }

    private fun show() {
        composeFrame {
            val scroll = rememberScrollState()
            scrollValue = { scroll.value }
            var current by remember { mutableIntStateOf(0) }
            Column(Modifier.verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().height(200.dp))
                RovstiTabBar(
                    tabs = CatalogTabs,
                    selected = current,
                    onSelect = {
                        current = it
                        selected = it
                    },
                    onReselect = { reselected = it },
                    imeVisible = { false },
                )
                Box(Modifier.fillMaxWidth().height(2000.dp))
            }
        }
    }

    @Test
    fun tapSelectsTheTabUnderTheFinger() {
        show()
        val g = BarGeometry(this)
        compose.onNodeWithTag("tab-keypad").assertIsSelected()
        compose.onNodeWithTag("tab-surface").performTouchInput {
            down(g.slotCentre(1f) - g.surface.topLeft)
            up()
        }
        advance(800)
        assertEquals(1, selected)
        compose.onNodeWithTag("tab-messages").assertIsSelected()
    }

    @Test
    fun tappingTheSelectedTabReselects() {
        show()
        val g = BarGeometry(this)
        compose.onNodeWithTag("tab-surface").performTouchInput {
            down(g.slotCentre(0f) - g.surface.topLeft)
            up()
        }
        advance(500)
        assertEquals(0, reselected)
    }

    @Test
    fun dragOfOnePointSixSlotsSelectsSlotTwoByProjection() {
        show()
        val g = BarGeometry(this)
        val from = g.slotCentre(0f) - g.surface.topLeft
        compose.onNodeWithTag("tab-surface").performTouchInput { down(from) }
        advance(200)
        val total = 1.6f * g.slot
        val steps = 12
        repeat(steps) {
            compose.onNodeWithTag("tab-surface").performTouchInput { moveBy(Offset(total / steps, 0f)) }
            advance(16)
        }
        advance(150)
        compose.onNodeWithTag("tab-surface").performTouchInput { up() }
        advance(1500)
        assertEquals(2, selected)
        compose.onNodeWithTag("tab-contacts").assertIsSelected()
    }

    @Test
    fun aTapReleasedTwentyFourDpOrMoreOffTheBarIsIgnored() {
        show()
        val g = BarGeometry(this)
        val start = g.slotCentre(1f) - g.surface.topLeft
        compose.onNodeWithTag("tab-surface").performTouchInput { down(start) }
        advance(200)
        val off = Offset(start.x, g.surface.height + g.dp(30f))
        compose.onNodeWithTag("tab-surface").performTouchInput {
            moveTo(off)
            up()
        }
        advance(1000)
        assertEquals(0, selected)
        compose.onNodeWithTag("tab-keypad").assertIsSelected()
    }

    @Test
    fun aTapReleasedJustInsideTheSlopStillCounts() {
        show()
        val g = BarGeometry(this)
        val start = g.slotCentre(3f) - g.surface.topLeft
        compose.onNodeWithTag("tab-surface").performTouchInput { down(start) }
        advance(200)
        compose.onNodeWithTag("tab-surface").performTouchInput {
            moveTo(Offset(start.x, g.surface.height + g.dp(20f)))
            up()
        }
        advance(1000)
        assertEquals(3, selected)
    }

    @Test
    fun aVerticalMoveBeforeTheHoldTimeBelongsToThePage() {
        show()
        val g = BarGeometry(this)
        val start = g.slotCentre(1f) - g.surface.topLeft
        compose.onNodeWithTag("tab-surface").performTouchInput {
            down(start)
            moveBy(Offset(0f, -g.dp(60f)))
            up()
        }
        advance(1000)
        assertEquals("the bar must not select", 0, selected)
        assertTrue("the parent column scrolled (${scrollValue()})", scrollValue() > 0)
    }

    @Test
    fun haptics() {
        val rec = RecordingHaptics()
        composeFrame {
            androidx.compose.runtime.CompositionLocalProvider(
                dev.rovsti.design.haptics.LocalRovstiHaptics provides rec,
            ) {
                var current by remember { mutableIntStateOf(0) }
                RovstiTabBar(CatalogTabs, current, { current = it }, {}, imeVisible = { false })
            }
        }
        val g = BarGeometry(this)
        compose.onNodeWithTag("tab-surface").performTouchInput {
            down(g.slotCentre(2f) - g.surface.topLeft)
            up()
        }
        advance(800)
        compose.onNodeWithTag("tab-surface").performTouchInput {
            down(g.slotCentre(2f) - g.surface.topLeft)
            up()
        }
        advance(800)
        assertEquals(listOf(HapticEvent.Selection, HapticEvent.Tick), rec.events)
    }
}
