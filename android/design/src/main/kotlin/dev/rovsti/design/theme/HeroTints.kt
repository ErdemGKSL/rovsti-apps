/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/HeroTints.kt (asPuckTint)
 * Changes for Rovsti: package dev.rovsti.design.theme; only the tab-bar puck wash is ported (the other hero tints are
 * artwork-driven and have no counterpart in Rovsti).
 */
package dev.rovsti.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Selection-pill wash: lighter than the ambient surface with a hint more of the hue. Alpha is picked at the call site
 * by contrast (0.70 on light ambient, 0.80 on dark).
 */
fun Color.asPuckTint(isDark: Boolean = true): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(0.15f, 0.25f) // a hint of the hue
    hsl[2] =
        if (isDark) {
            hsl[2].coerceIn(0.32f, 0.42f) // lighter than the 0.20..0.28 surface
        } else {
            hsl[2].coerceIn(0.80f, 0.88f)
        }
    return Color(ColorUtils.HSLToColor(hsl))
}
