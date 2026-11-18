// Rosty Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rosty.design.media

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.rosty.design.theme.relativeLuminance
import dev.rosty.design.theme.scrimAlphaFor

/**
 * The shade under the photo viewer's chrome. The viewer's bars are white on glass, and a near-white or beige photo takes
 * the contrast away from them; a black gradient behind each bar restores it. How strong it must be follows the photo:
 * the band of the picture behind the bar is measured on a small grid (16 x 32 cells) and [scrimAlphaFor] gives the
 * alpha at which white text reaches 4.5:1 over it, never below [FLOOR] so a dark photo still gets a light, steady shade.
 */
internal object ViewerScrim {
    const val COLS = 16
    const val ROWS = 32

    /** The lightest shade: also what a dark photo, or a photo not decoded yet, gets. */
    const val FLOOR = 0.35f

    /** The strongest shade; gamma-space blending of black reaches 4.5:1 on white well below this. */
    const val MAX = 0.6f

    /** The shade fades out over this much below the bar, in dp. */
    const val RUN_DP = 24f

    /** Mean WCAG luminance of each cell of a [COLS] x [ROWS] grid of ARGB pixels (row-major). */
    fun lumaGrid(argb: IntArray): FloatArray {
        require(argb.size == COLS * ROWS) { "expected ${COLS * ROWS} pixels, got ${argb.size}" }
        return FloatArray(argb.size) { i ->
            val p = argb[i]
            relativeLuminance(Color(red = (p shr 16) and 0xFF, green = (p shr 8) and 0xFF, blue = p and 0xFF)).toFloat()
        }
    }

    /**
     * Mean luminance of the rows of [grid] between the picture fractions [from] and [to] (0 = top, 1 = bottom), with
     * partial rows weighted by their overlap; null when the band is empty.
     */
    fun bandLuminance(
        grid: FloatArray,
        from: Float,
        to: Float,
    ): Double? {
        val a = from.coerceIn(0f, 1f) * ROWS
        val b = to.coerceIn(0f, 1f) * ROWS
        if (b - a <= 1e-4f) return null
        var sum = 0.0
        var weight = 0.0
        for (row in a.toInt() until minOf(ROWS, kotlin.math.ceil(b).toInt())) {
            val w = (minOf(b, row + 1f) - maxOf(a, row.toFloat())).toDouble()
            if (w <= 0.0) continue
            var rowSum = 0.0
            for (col in 0 until COLS) rowSum += grid[row * COLS + col]
            sum += rowSum / COLS * w
            weight += w
        }
        return if (weight <= 0.0) null else sum / weight
    }

    /**
     * The part of the picture a band of the screen covers, as fractions of the picture's height: the band runs from
     * [bandTop] to [bandBottom] px of a screen where the picture is [photoTop] px from the top and [photoHeight] tall.
     * Null when the picture does not reach the band (it sits over the black letterbox).
     */
    fun picturePart(
        bandTop: Float,
        bandBottom: Float,
        photoTop: Float,
        photoHeight: Float,
    ): Pair<Float, Float>? {
        if (photoHeight <= 0f) return null
        val from = ((bandTop - photoTop) / photoHeight).coerceIn(0f, 1f)
        val to = ((bandBottom - photoTop) / photoHeight).coerceIn(0f, 1f)
        return if (to - from <= 1e-4f) null else from to to
    }

    /** The shade alpha for a band of mean luminance [luminance] (null: unknown or no picture there). */
    fun alphaFor(luminance: Double?): Float =
        if (luminance == null) FLOOR else scrimAlphaFor(luminance, Color.White, 4.5, FLOOR, MAX)

    /**
     * The top gradient: [alpha] held through the bar (where the title sits, up to [holdFraction] of the height) and eased
     * height plus the run below, so there is no edge to see. [barFraction] is bar / (bar + run).
     */
    fun topBrush(
        alpha: Float,
        holdFraction: Float,
    ): Brush {
        val hold = holdFraction.coerceIn(0f, 0.95f)
        return Brush.verticalGradient(
            colorStops =
                Array(STOPS) { i ->
                    val t = i / (STOPS - 1f)
                    val u = ((t - hold) / (1f - hold)).coerceIn(0f, 1f)
                    t to Color.Black.copy(alpha = alpha * (1f - u * u * (3f - 2f * u)))
                },
        )
    }

    /** The bottom gradient: nothing at the top, [alpha] behind the actions. */
    fun bottomBrush(alpha: Float): Brush =
        Brush.verticalGradient(
            colorStops =
                Array(STOPS) { i ->
                    val t = i / (STOPS - 1f)
                    t to Color.Black.copy(alpha = alpha * (t * t * (3f - 2f * t)))
                },
        )

    private const val STOPS = 10
}

/** The viewer chrome's own colours: the same in the light and the dark app theme, because the photo is on black. */
internal object ViewerChromeColors {
    val Title: Color = Color.White
    val Glyph: Color = Color.White

    /** The dark capsule under the bottom actions' glass, for the same reason. */
    val ActionsDisc: Color = Color.Black.copy(alpha = 0.55f)

    /** The dark disc under the back button's glass, so the circle reads as dark glass over a light photo. */
    val BackDisc: Color = Color.Black.copy(alpha = 0.6f)
}
