package dev.rosty.shell.settings

import androidx.compose.ui.graphics.Color
import dev.rosty.design.theme.DarkColors
import dev.rosty.design.theme.LightColors
import dev.rosty.design.theme.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

/** r19-s6: the glyph on a settings icon tile keeps 3:1 on the light dark-mode accent (white fell to 2.7:1). */
class SettingsGlyphColorTest {
    @Test
    fun theGlyphReadsOnTheAccentTileInBothThemes() {
        for (palette in listOf(LightColors, DarkColors)) {
            val tile = palette.accent.copy(alpha = 0.9f)
            val glyph = settingsGlyphColor(tile, palette.groupedSurface)
            val shown = tile.let { Color(it.red, it.green, it.blue, 0.9f) }
            assertTrue(contrastRatio(glyph, shown, palette.groupedSurface) >= 3.0)
        }
    }
}
