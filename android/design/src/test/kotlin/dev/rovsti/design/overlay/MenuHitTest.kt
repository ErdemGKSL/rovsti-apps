package dev.rovsti.design.overlay

import dev.rovsti.design.haptics.NoHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure part of press-slide-release: which row is under a y, and where the arrow keys go. */
class MenuHitTest {
    private val items: List<MenuItem> =
        listOf(
            MenuItem.Action("A", onClick = {}),
            MenuItem.Action("B", onClick = {}),
            MenuItem.Separator,
            MenuItem.Action("Off", enabled = false, onClick = {}),
            MenuItem.Action("D", destructive = true, onClick = {}),
        )

    private fun panel(): MenuPanelState {
        val panel =
            MenuPanelState(
                items = items,
                scope = CoroutineScope(Dispatchers.Unconfined),
                geometry = CardGeometry(),
                haptics = { NoHaptics },
                reduceMotion = { false },
                pxPerDp = 1f,
                onCommit = {},
            )
        // 48 px rows, a 15 px separator.
        var y = 0
        items.forEachIndexed { i, item ->
            val h = if (item is MenuItem.Separator) 15 else 48
            panel.place(i, y, y + h)
            y += h
        }
        return panel
    }

    @Test
    fun aRowOwnsItsTopEdgeButNotItsBottomEdge() {
        val rects = floatArrayOf(0f, 48f, 48f, 96f)
        assertEquals(0, hoveredRow(rects, 0f))
        assertEquals(0, hoveredRow(rects, 47.9f))
        assertEquals(1, hoveredRow(rects, 48f))
        assertEquals(-1, hoveredRow(rects, 96f))
        assertEquals(-1, hoveredRow(rects, -1f))
    }

    @Test
    fun aGapBetweenRowsHitsNothing() {
        val rects = floatArrayOf(0f, 40f, 50f, 90f)
        assertEquals(-1, hoveredRow(rects, 45f))
    }

    @Test
    fun separatorsAndDisabledRowsAreNeverHit() {
        val rects = panel().rects
        assertEquals(0, hoveredRow(rects, 10f))
        assertEquals(1, hoveredRow(rects, 60f))
        for (y in 96..110) assertEquals("separator at $y", -1, hoveredRow(rects, y.toFloat()))
        for (y in 111..158) assertEquals("disabled row at $y", -1, hoveredRow(rects, y.toFloat()))
        assertEquals(4, hoveredRow(rects, 170f))
        assertEquals(true, rects[4].isNaN() && rects[5].isNaN())
        assertEquals(true, rects[6].isNaN() && rects[7].isNaN())
    }

    @Test
    fun anEmptyMenuHitsNothing() {
        assertEquals(-1, hoveredRow(FloatArray(0), 3f))
    }

    @Test
    fun theFirstEnabledRowTakesFocus() {
        assertEquals(0, firstEnabledRow(items))
        assertEquals(1, firstEnabledRow(listOf(MenuItem.Separator, MenuItem.Action("x", onClick = {}))))
        assertEquals(-1, firstEnabledRow(listOf(MenuItem.Separator)))
        assertEquals(0, panel().focusIndex)
    }

    @Test
    fun arrowsWrapAndSkipSeparatorsAndDisabledRows() {
        assertEquals(1, stepRow(items, 0, +1))
        assertEquals(4, stepRow(items, 1, +1))
        assertEquals(0, stepRow(items, 4, +1))
        assertEquals(4, stepRow(items, 0, -1))
        assertEquals(1, stepRow(items, 4, -1))
        assertEquals(-1, stepRow(listOf(MenuItem.Separator), 0, +1))
    }

    @Test
    fun aKeyboardStepFromNoRowStartsAtTheEnds() {
        assertEquals(0, stepRow(items, -1, +1))
        assertEquals(4, stepRow(items, -1, -1))
    }
}
