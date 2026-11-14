package dev.rosty.design.ios

import dev.rosty.design.ios.foundation.IosColors
import dev.rosty.design.ios.foundation.iosColorsFrom
import dev.rosty.design.ios.foundation.meetsContrast
import dev.rosty.design.theme.DarkColors
import dev.rosty.design.theme.LightColors
import dev.rosty.design.theme.RostyColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge that feeds every ported iOS component from the Rosty palette. */
class IosColorsBridgeTest {
    private fun check(
        rosty: RostyColors,
        ios: IosColors,
    ) {
        assertEquals(rosty.isDark, ios.isDark)
        assertEquals(rosty.labelPrimary, ios.label)
        assertEquals(rosty.labelSecondary, ios.secondaryLabel)
        assertEquals(rosty.labelTertiary, ios.tertiaryLabel)
        assertEquals(rosty.separator, ios.separator)
        assertEquals(rosty.opaqueSeparator, ios.opaqueSeparator)
        assertEquals(rosty.fillPrimary, ios.systemFill)
        assertEquals(rosty.fillSecondary, ios.secondarySystemFill)
        assertEquals(rosty.fillTertiary, ios.tertiarySystemFill)
        assertEquals(rosty.page, ios.systemBackground)
        assertEquals(rosty.groupedBackground, ios.systemGroupedBackground)
        assertEquals(rosty.groupedSurface, ios.secondarySystemGroupedBackground)
        assertEquals(rosty.accent, ios.accent)
        assertEquals(rosty.destructive, ios.systemRed)
        assertEquals(rosty.success, ios.systemGreen)
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
            dev.rosty.design.ios.foundation
                .lightIosColors()
                .systemBlue,
            iosColorsFrom(LightColors).systemBlue,
        )
        assertEquals(
            dev.rosty.design.ios.foundation
                .darkIosColors()
                .systemGray5,
            iosColorsFrom(DarkColors).systemGray5,
        )
    }
}
