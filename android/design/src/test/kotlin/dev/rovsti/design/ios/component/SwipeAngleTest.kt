package dev.rovsti.design.ios.component

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
}
