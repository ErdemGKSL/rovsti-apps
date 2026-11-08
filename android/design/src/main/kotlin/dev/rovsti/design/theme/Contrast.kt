package dev.rovsti.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow

/** WCAG 2.x relative luminance of the sRGB colour; alpha is ignored (composite first). */
fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= LINEAR_CUTOFF) c / LINEAR_SLOPE else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/**
 * WCAG contrast ratio (1.0 to 21.0) of [foreground] drawn on [background]. A translucent foreground is
 * composited over the background first; a translucent background is composited over [backdrop]
 * (opaque white when none is given), so "label on glass over a backdrop" is one call.
 */
fun contrastRatio(
    foreground: Color,
    background: Color,
    backdrop: Color = Color.White,
): Double {
    val bg = background.compositeOver(backdrop)
    val fg = foreground.compositeOver(bg)
    val a = relativeLuminance(fg)
    val b = relativeLuminance(bg)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/**
 * What a glass surface looks like over [backdrop]: the legibility scrim first, then the surface fill.
 * Use as the `background` of [contrastRatio] (it is opaque when [backdrop] is).
 */
fun glassOver(
    fill: Color,
    scrim: Color,
    backdrop: Color,
): Color = fill.compositeOver(scrim.compositeOver(backdrop))

private const val LINEAR_CUTOFF = 0.04045
private const val LINEAR_SLOPE = 12.92
