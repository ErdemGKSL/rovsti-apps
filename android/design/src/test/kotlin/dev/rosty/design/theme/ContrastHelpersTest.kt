package dev.rosty.design.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastHelpersTest {
    @Test
    fun whiteIsKeptOnFillsThatPass() {
        for (fill in listOf(Color(0xFF0051D5), Color(0xFFC8202A), Color(0xFF007AFF))) {
            val ratio = contrastRatio(Color.White, fill)
            if (ratio >= 4.5) assertEquals(Color.White, readableOn(fill))
        }
        assertEquals(Color.White, readableOn(Color(0xFF0051D5)))
        assertEquals(Color.White, readableOn(Color(0xFFC8202A)))
    }

    @Test
    fun accentFillKeepsThemeOnAccentAndLightFillsGetInk() {
        assertEquals(LightColors.onAccent, LightColors.onColorFor(LightColors.accent))
        assertEquals(DarkColors.onAccent, DarkColors.onColorFor(DarkColors.accent))
        assertEquals(Color(0xFF001A3A), DarkColors.onAccent)
        assertEquals(Color(0xFF5AB0FF), DarkColors.accent)
        assertEquals(InkOnFill, LightColors.onColorFor(Color(0xFF34C759)))
        assertEquals(InkOnFill, LightColors.onColorFor(Color(0xFFFF9500)))
    }

    @Test
    fun readableOnAlwaysReachesTheBestOfWhiteAndInk() {
        for (fill in listOf(Color(0xFF5AB0FF), Color(0xFF34C759), Color(0xFFFF9500), Color(0xFF808080))) {
            val chosen = contrastRatio(readableOn(fill), fill)
            assertTrue(chosen >= contrastRatio(Color.White, fill) - 1e-9)
            assertTrue(chosen >= contrastRatio(InkOnFill, fill) - 1e-9 || chosen >= 4.5)
        }
    }

    @Test
    fun scrimOnWhiteBandIsStrongEnoughForWhiteText() {
        val alpha = scrimAlphaFor(1.0, Color.White, 4.5, floor = 0.1f)
        assertTrue("alpha=$alpha", alpha >= 0.53f)
        val l = 1.0 * (1 - alpha)
        assertTrue((1.05) / (l + 0.05) >= 4.5 - 1e-6 || alpha == 0.8f)
    }

    @Test
    fun scrimOnDarkBandIsTheFloor() {
        assertEquals(0.12f, scrimAlphaFor(0.01, Color.White, 4.5, floor = 0.12f), 0f)
    }

    @Test
    fun scrimAlphaIsMonotonicInLuminanceAndClamped() {
        var previous = 0f
        for (i in 0..20) {
            val a = scrimAlphaFor(i / 20.0, Color.White, 4.5, floor = 0.1f)
            assertTrue(a >= previous)
            assertTrue(a in 0.1f..0.8f)
            previous = a
        }
    }
}
