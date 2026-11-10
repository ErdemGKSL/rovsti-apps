package dev.rovsti.phone.keypad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Keypad budget: sizes on three screens, and that the blocks never overlap on any screen. */
class KeypadLayoutTest {
    // The room the screen hands over: the window less the status bar and the tab bar slot (about 28 + 110 dp).
    private fun room(window: Float) = window - 28f - 110f

    private fun total(sizes: KeypadSizes) =
        sizes.fieldHeight + sizes.keysHeight + sizes.suggestionRows * 56f * 1f +
            if (sizes.suggestionRows > 0) 36f else 0f

    @Test fun aNormalPhoneGetsFullKeysAndThreeSuggestions() {
        val sizes = KeypadLayout.compute(412f, room(915f), 1f)
        assertEquals(78f, sizes.key, 0.01f)
        assertEquals(3, sizes.suggestionRows)
        assertTrue(sizes.letters)
        assertEquals("24 dp between the columns", 24f, sizes.columnGap, 0.01f)
    }

    @Test fun theReferencePhoneGetsFullKeys() {
        val sizes = KeypadLayout.compute(411f, room(891f), 1f)
        assertEquals(78f, sizes.key, 0.01f)
        assertTrue(total(sizes) + 8f <= room(891f))
    }

    @Test fun aSmallScreenShrinksTheKeysButKeepsTheFloor() {
        val sizes = KeypadLayout.compute(360f, room(640f), 1f)
        assertTrue("key ${sizes.key} between 64 and 78", sizes.key in 64f..78f)
        assertTrue(total(sizes) + 8f <= room(640f))
        // Down here the suggestions give way to the "N matches" line before the keys do.
        assertTrue(KeypadLayout.compute(360f, room(740f), 1f).suggestionRows >= 1)
    }

    @Test fun aLargeSystemFontShrinksTheSuggestionsBeforeTheKeys() {
        val normal = KeypadLayout.compute(360f, room(640f), 1f)
        val big = KeypadLayout.compute(360f, room(640f), 2f)
        assertTrue(big.key >= 64f)
        assertTrue(big.fieldHeight > normal.fieldHeight)
        assertTrue(big.suggestionRows <= normal.suggestionRows)
        assertTrue("no letters under keys at twice the font", !big.letters)
    }

    @Test fun theBlocksNeverOverlapOnAnyScreenThatCanHoldTheKeys() {
        var checked = 0
        for (window in 600..1100 step 20) {
            for (width in listOf(320f, 360f, 411f, 480f, 600f)) {
                for (scale in listOf(0.85f, 1f, 1.3f, 2f)) {
                    val sizes = KeypadLayout.compute(width, room(window.toFloat()), scale)
                    val minimum = sizes.fieldHeight + sizes.keysHeight
                    if (minimum + 8f > room(window.toFloat())) continue
                    assertTrue(
                        "$width x $window fs$scale: ${total(sizes)}",
                        total(sizes) + 8f <= room(window.toFloat()),
                    )
                    assertTrue(sizes.key <= 78f && sizes.key >= 64f)
                    assertTrue("three columns fit the width", KeypadLayout.gridWidth(sizes) + 32f <= width + 0.01f)
                    checked++
                }
            }
        }
        assertTrue("the sweep covered screens", checked > 50)
    }
}
