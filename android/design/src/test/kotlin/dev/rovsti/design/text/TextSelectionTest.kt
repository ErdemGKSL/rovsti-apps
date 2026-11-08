package dev.rovsti.design.text

import dev.rovsti.design.theme.DarkColors
import dev.rovsti.design.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionTest {
    @Test
    fun handlesAreTheAccentAndTheHighlightIsAThirdOfIt() {
        for (colors in listOf(LightColors, DarkColors)) {
            val selection = rovstiSelectionColors(colors)
            assertEquals(colors.accent, selection.handleColor)
            assertEquals(colors.accent.copy(alpha = 0.3f), selection.backgroundColor)
        }
    }
}
