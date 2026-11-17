package dev.rosty.design.theme

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

/** Near-black ink for text and icons on light or saturated fills where white falls under 4.5:1. */
val InkOnFill: Color = Color(0xFF0B0B0F)

/**
 * The content colour for a filled surface: [preferred] (white) when it reaches [minRatio] on [fill], otherwise
 * whichever of [preferred] and [ink] has the higher ratio. [fill] is expected to be opaque.
 */
fun readableOn(
    fill: Color,
    preferred: Color = Color.White,
    ink: Color = InkOnFill,
    minRatio: Double = 4.5,
): Color {
    val preferredRatio = contrastRatio(preferred, fill)
    if (preferredRatio >= minRatio) return preferred
    return if (contrastRatio(ink, fill) > preferredRatio) ink else preferred
}

/** The text colour for [fill]: the theme's [RostyColors.onAccent] on the accent itself, else [readableOn]. */
fun RostyColors.onColorFor(fill: Color): Color = if (fill == accent) onAccent else readableOn(fill)

/**
 * The smallest alpha of a black scrim over a band of mean linear luminance [bandLuminance] at which
 * [foreground] reaches the contrast [target]. The scrim scales the band to L' = L * (1 - a), so for a lighter
 * foreground the closed form is a = 1 - ((Lf + 0.05) / target - 0.05) / L. The result is clamped to
 * [[floor], [max]]; a foreground darker than the band cannot be helped by a black scrim and gets [floor].
 */
fun scrimAlphaFor(
    bandLuminance: Double,
    foreground: Color,
    target: Double,
    floor: Float,
    max: Float = 0.8f,
): Float {
    val fg = relativeLuminance(foreground)
    val band = bandLuminance.coerceIn(0.0, 1.0)
    val needed =
        if (fg < band || band <= 0.0) {
            0.0
        } else {
            1.0 - ((fg + 0.05) / target - 0.05) / band
        }
    return needed.toFloat().coerceIn(floor, max)
}

private const val LINEAR_CUTOFF = 0.04045
private const val LINEAR_SLOPE = 12.92
