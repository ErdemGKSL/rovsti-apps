package dev.rovsti.design.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val backdrops =
        mapOf(
            "white" to Color.White,
            "black" to Color.Black,
            "grey808080" to Color(0xFF808080),
        )

    private class Theme(
        val name: String,
        val colors: RovstiColors,
        val glass: GlassTokens,
    )

    private val themes =
        listOf(
            Theme("light", LightColors, GlassTokens.Light),
            Theme("dark", DarkColors, GlassTokens.Dark),
        )

    /** Flat backgrounds plus the glass fills over each backdrop, named for failure messages. */
    private fun backgrounds(t: Theme): Map<String, Color> {
        val flat =
            mapOf(
                "page" to t.colors.page,
                "groupedBackground" to t.colors.groupedBackground,
                "groupedSurface" to t.colors.groupedSurface,
            )
        val glass =
            buildMap {
                for ((backdropName, backdrop) in backdrops) {
                    put("glass over $backdropName", glassOver(t.glass.glass, t.colors.glassScrim, backdrop))
                    put("surface over $backdropName", glassOver(t.glass.surface, t.colors.glassScrim, backdrop))
                }
            }
        return flat + glass
    }

    private fun glassBackgrounds(t: Theme) = backgrounds(t).filterKeys { " over " in it }

    private fun check(
        label: String,
        fg: Color,
        backgrounds: Map<String, Color>,
        min: Double,
    ) {
        for ((name, bg) in backgrounds) {
            val ratio = contrastRatio(fg, bg)
            assertTrue("$label on $name is %.2f, needs %.1f".format(ratio, min), ratio >= min)
        }
    }

    @Test
    fun primaryAndSecondaryLabelsMeet4_5EverywhereAndTertiaryMeets3() {
        for (t in themes) {
            val bgs = backgrounds(t)
            check("${t.name} labelPrimary", t.colors.labelPrimary, bgs, 4.5)
            check("${t.name} labelSecondary", t.colors.labelSecondary, bgs, 4.5)
            check("${t.name} labelTertiary", t.colors.labelTertiary, bgs, 3.0)
        }
    }

    @Test
    fun accentDestructiveAndSuccessAreReadableTextOnFlatSurfaces() {
        for (t in themes) {
            val flat = backgrounds(t) - glassBackgrounds(t).keys
            check("${t.name} accent", t.colors.accent, flat, 4.5)
            check("${t.name} destructive", t.colors.destructive, flat, 4.5)
            check("${t.name} success", t.colors.success, flat, 4.5)
        }
    }

    @Test
    fun accentDestructiveAndSuccessKeep3OnGlassAsGlyphsOrBoldText() {
        for (t in themes) {
            val glass = glassBackgrounds(t)
            check("${t.name} accent", t.colors.accent, glass, 3.0)
            check("${t.name} destructive", t.colors.destructive, glass, 3.0)
            check("${t.name} success", t.colors.success, glass, 3.0)
        }
    }

    @Test
    fun selectedStateIsFilledAccentWithReadableOnAccentText() {
        for (t in themes) {
            val ratio = contrastRatio(t.colors.onAccent, t.colors.accent)
            assertTrue("${t.name} onAccent on accent is %.2f".format(ratio), ratio >= 4.5)
            val pageRatio = contrastRatio(t.colors.accent, t.colors.page)
            assertTrue("${t.name} the filled pill reads against the page: %.2f".format(pageRatio), pageRatio >= 3.0)
        }
    }

    @Test
    fun glisseForegroundOnItsOwnGlassIsReadable() {
        for (t in themes) {
            check("${t.name} glass foreground", t.glass.foreground, glassBackgrounds(t), 4.5)
        }
    }

    @Test
    fun glassScrimExistsBecauseDarkGlassOverWhiteFailsWithout() {
        val bare = glassOver(GlassTokens.Dark.glass, Color.Transparent, Color.White)
        assertTrue(contrastRatio(Color.White, bare) < 4.5)
    }

    // The metric itself.
    @Test
    fun blackOnWhiteIs21AndEqualColoursAre1() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 1e-6)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 1e-9)
        assertEquals(contrastRatio(Color.Black, Color.White), contrastRatio(Color.White, Color.Black), 1e-9)
    }

    @Test
    fun luminanceOfPrimariesAndGrey() {
        assertEquals(0.0, relativeLuminance(Color.Black), 1e-9)
        assertEquals(1.0, relativeLuminance(Color.White), 1e-6)
        assertEquals(0.2126, relativeLuminance(Color.Red), 1e-4)
        assertEquals(0.2159, relativeLuminance(Color(0xFF808080)), 1e-3)
    }

    @Test
    fun translucentForegroundIsCompositedOverTheBackground() {
        // 50 % black over white is mid grey 0x80 (rounding aside), not black.
        val half = Color.Black.copy(alpha = 0.5f)
        assertEquals(contrastRatio(Color(0xFF808080), Color.White), contrastRatio(half, Color.White), 0.15)
    }

    @Test
    fun translucentBackgroundIsCompositedOverTheBackdrop() {
        val white60 = Color.White.copy(alpha = 0.6f)
        val overBlack = contrastRatio(Color.Black, white60, backdrop = Color.Black)
        val overWhite = contrastRatio(Color.Black, white60, backdrop = Color.White)
        assertTrue(overBlack < overWhite)
        assertEquals(21.0, overWhite, 1e-6)
    }
}
