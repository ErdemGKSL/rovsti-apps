package dev.rosty.design.text

import dev.rosty.design.theme.DarkColors
import dev.rosty.design.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionTest {
    @Test
    fun handlesAreTheAccentAndTheHighlightIsAThirdOfIt() {
        for (colors in listOf(LightColors, DarkColors)) {
            val selection = rostySelectionColors(colors)
            assertEquals(colors.accent, selection.handleColor)
            assertEquals(colors.accent.copy(alpha = 0.3f), selection.backgroundColor)
        }
    }
}
