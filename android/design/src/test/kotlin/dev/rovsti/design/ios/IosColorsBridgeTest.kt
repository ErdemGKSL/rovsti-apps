package dev.rovsti.design.ios

import dev.rovsti.design.ios.foundation.IosColors
import dev.rovsti.design.ios.foundation.iosColorsFrom
import dev.rovsti.design.ios.foundation.meetsContrast
import dev.rovsti.design.theme.DarkColors
import dev.rovsti.design.theme.LightColors
import dev.rovsti.design.theme.RovstiColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge that feeds every ported iOS component from the Rovsti palette. */
class IosColorsBridgeTest {
    private fun check(
        rovsti: RovstiColors,
        ios: IosColors,
    ) {
        assertEquals(rovsti.isDark, ios.isDark)
        assertEquals(rovsti.labelPrimary, ios.label)
        assertEquals(rovsti.labelSecondary, ios.secondaryLabel)
        assertEquals(rovsti.labelTertiary, ios.tertiaryLabel)
        assertEquals(rovsti.separator, ios.separator)
        assertEquals(rovsti.opaqueSeparator, ios.opaqueSeparator)
        assertEquals(rovsti.fillPrimary, ios.systemFill)
        assertEquals(rovsti.fillSecondary, ios.secondarySystemFill)
        assertEquals(rovsti.fillTertiary, ios.tertiarySystemFill)
        assertEquals(rovsti.page, ios.systemBackground)
        assertEquals(rovsti.groupedBackground, ios.systemGroupedBackground)
        assertEquals(rovsti.groupedSurface, ios.secondarySystemGroupedBackground)
        assertEquals(rovsti.accent, ios.accent)
        assertEquals(rovsti.destructive, ios.systemRed)
        assertEquals(rovsti.success, ios.systemGreen)
    }

    @Test
    fun lightPaletteMapsToTheSameTokens() = check(LightColors, iosColorsFrom(LightColors))

    @Test
    fun darkPaletteMapsToTheSameTokens() = check(DarkColors, iosColorsFrom(DarkColors))

    @Test
    fun theLabelPassesContrastOnThePageInBothThemes() {
        for (palette in listOf(LightColors, DarkColors)) {
            val ios = iosColorsFrom(palette)
            assertTrue(meetsContrast(ios.label, ios.systemBackground, 4.5f))
            assertTrue(meetsContrast(ios.label, ios.secondarySystemGroupedBackground, 4.5f))
        }
    }

    @Test
    fun unmappedRolesKeepConvxDefaults() {
        assertEquals(
            dev.rovsti.design.ios.foundation
                .lightIosColors()
                .systemBlue,
            iosColorsFrom(LightColors).systemBlue,
        )
        assertEquals(
            dev.rovsti.design.ios.foundation
                .darkIosColors()
                .systemGray5,
            iosColorsFrom(DarkColors).systemGray5,
        )
    }
}
