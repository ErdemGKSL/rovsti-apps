package dev.rovsti.design.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** The keyboard-gap bug: a window the system already shrank must not be padded by a second keyboard height. */
class ImeOverlapTest {
    private val screen = 2400
    private val keyboard = 900
    private val nav = 60

    @Test
    fun edgeToEdgeWindowIsPaddedByTheKeyboardLessTheNavigationBar() {
        // A sheet reaching the screen bottom that keeps the navigation bar clear itself.
        assertEquals(keyboard - nav, imeOverlap(screen, screen, keyboard, nav))
    }

    @Test
    fun windowTheSystemResizedIsNotPaddedAgain() {
        // HyperOS resized the window: the element already ends at the keyboard's top edge.
        assertEquals(0, imeOverlap(screen - keyboard, screen, keyboard, nav))
    }

    @Test
    fun partlyCoveredElementIsPaddedByTheCoveredPartOnly() {
        assertEquals(200, imeOverlap(screen - keyboard + 200, screen, keyboard, 0))
    }

    @Test
    fun hiddenOrFloatingKeyboardPadsNothing() {
        assertEquals(0, imeOverlap(screen, screen, 0, nav))
    }

    @Test
    fun dockAboveTheNavigationBarIsPaddedByTheKeyboardLessTheBar() {
        // ComposerField: navigationBarsPadding() first, so the measured content ends above the bar; nothing excluded.
        assertEquals(keyboard - nav, imeOverlap(screen - nav, screen, keyboard, 0))
    }
}
