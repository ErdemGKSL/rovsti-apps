package dev.rovsti.design.ios.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A row swipe starts only when the finger moves within 25° of horizontal; steeper drags scroll the list. */
class SwipeAngleTest {
    @Test
    fun aFlatDragSwipesTheRow() {
        assertTrue(isSidewaysDrag(20f, 0f))
        assertTrue(isSidewaysDrag(-20f, 5f))
        assertTrue(isSidewaysDrag(20f, -9f))
    }

    @Test
    fun aSlantedOrVerticalDragIsLeftToTheList() {
        assertFalse(isSidewaysDrag(20f, 10f))
        assertFalse(isSidewaysDrag(-12f, 12f))
        assertFalse(isSidewaysDrag(3f, 20f))
        assertFalse(isSidewaysDrag(0f, 0f))
    }

    @Test
    fun aRowOpenOnOneSideOnlyClosesWhenDraggedTheOtherWay() {
        assertEquals(0f to 160f, swipeBounds(1f, 400f, 160f))
        assertEquals(-400f to 0f, swipeBounds(-1f, 400f, 160f))
        assertEquals(-400f to 160f, swipeBounds(0f, 400f, 160f))
    }
}
