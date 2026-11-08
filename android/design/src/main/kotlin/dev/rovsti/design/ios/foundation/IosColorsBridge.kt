/*
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/foundation/IosColors.kt
 * Changes for Rovsti: new bridge (not in Convx) that fills Convx's IosColors from the Rovsti palette.
 */
package dev.rovsti.design.ios.foundation

import dev.rovsti.design.theme.RovstiColors

/**
 * Maps the Rovsti palette onto Convx's [IosColors]: label, secondary and tertiary label, separators, fills, page,
 * grouped backgrounds, accent, destructive (systemRed) and success (systemGreen) come from [colors]; everything else
 * (the other system hues, grays, quaternary roles) keeps Convx's iOS defaults for the same appearance.
 */
fun iosColorsFrom(colors: RovstiColors): IosColors {
    val base = if (colors.isDark) darkIosColors(colors.accent) else lightIosColors(colors.accent)
    return base.copy(
        systemRed = colors.destructive,
        systemGreen = colors.success,
        label = colors.labelPrimary,
        secondaryLabel = colors.labelSecondary,
        tertiaryLabel = colors.labelTertiary,
        placeholderText = colors.labelTertiary,
        systemFill = colors.fillPrimary,
        secondarySystemFill = colors.fillSecondary,
        tertiarySystemFill = colors.fillTertiary,
        separator = colors.separator,
        opaqueSeparator = colors.opaqueSeparator,
        systemBackground = colors.page,
        secondarySystemBackground = colors.groupedSurface,
        tertiarySystemBackground = colors.groupedSurface,
        systemGroupedBackground = colors.groupedBackground,
        secondarySystemGroupedBackground = colors.groupedSurface,
        accent = colors.accent,
    )
}
