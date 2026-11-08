// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Rovsti extension: `upward` (the glass grows from the trigger's bottom edge when the menu opens above it).
package dev.rovsti.design.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Everything the button menu draws at one moment of the morph, derived from the master progress `t` (0 = the glass is
 * the trigger, 1 = the full card). All lengths are pixels in the card's own coordinate space (origin at its top-left
 * corner, final size from the first frame).
 *
 * The surface grows from the trigger's rect; the trigger fades into it; the trigger's glyph drifts to the centre of
 * the growing surface, swells and blurs away; the items scale up from the trigger centre, unblur and fade in later
 * than the glass. [clipLeft]..[clipBottom] is the growing surface mapped back through the items' own scale, so the
 * items can be clipped to it while they are scaled.
 */
@Immutable
data class MenuMorphFrame(
    val surfaceLeft: Float,
    val surfaceTop: Float,
    val surfaceWidth: Float,
    val surfaceHeight: Float,
    val surfaceAlpha: Float,
    val triggerAlpha: Float,
    val glyphAlpha: Float,
    val glyphDx: Float,
    val glyphDy: Float,
    val glyphScale: Float,
    val glyphBlurPx: Float,
    val contentAlpha: Float,
    val contentScale: Float,
    val contentBlurPx: Float,
    val originX: Float,
    val originY: Float,
    val clipLeft: Float,
    val clipTop: Float,
    val clipRight: Float,
    val clipBottom: Float,
    val clipRadius: Float,
    val settled: Boolean,
)

private fun unit(v: Float) = v.coerceIn(0f, 1f)

/**
 * The morph frame for progress [value]. [from] is the trigger's size, [to] the card's. The glass is anchored at the
 * card's top-left corner, or at its right edge when [trailing], and at the bottom edge when [upward], so that at
 * `value == 0` it coincides with a trigger sitting in that corner of the card. [leaving] makes the items fade out
 * earlier than they fade in. [cornerPx] is the card's corner radius.
 */
fun menuMorphFrame(
    value: Float,
    from: Size,
    to: Size,
    cornerPx: Float,
    trailing: Boolean,
    upward: Boolean,
    leaving: Boolean,
    pxPerDp: Float,
): MenuMorphFrame {
    val t = unit(value)
    val width = from.width + (to.width - from.width) * t
    val height = from.height + (to.height - from.height) * t
    val scale = 0.5f + 0.5f * t
    val originX = if (trailing) to.width - from.width / 2f else from.width / 2f
    val originY = if (upward) to.height - from.height / 2f else from.height / 2f
    val surfaceLeft = if (trailing) to.width - width else 0f
    val surfaceTop = if (upward) to.height - height else 0f

    fun localX(edge: Float) = originX + (edge - originX) / scale

    fun localY(edge: Float) = originY + (edge - originY) / scale
    val drift = 0.3f * t
    return MenuMorphFrame(
        surfaceLeft = surfaceLeft,
        surfaceTop = surfaceTop,
        surfaceWidth = width,
        surfaceHeight = height,
        surfaceAlpha = unit(t / 0.2f),
        triggerAlpha = if (t == 0f) 1f else 1f - unit(t / 0.2f),
        glyphAlpha = 1f - unit(t / 0.45f),
        glyphDx = (surfaceLeft + width / 2f - originX) * drift,
        glyphDy = (surfaceTop + height / 2f - originY) * drift,
        glyphScale = 1f + 0.6f * t,
        glyphBlurPx = 8f * t * pxPerDp,
        contentAlpha = if (leaving) unit((t - 0.55f) / 0.45f) else unit((t - 0.15f) / 0.6f),
        contentScale = scale,
        contentBlurPx = 6f * (1f - t) * pxPerDp,
        originX = originX,
        originY = originY,
        clipLeft = localX(surfaceLeft),
        clipTop = localY(surfaceTop),
        clipRight = localX(surfaceLeft + width),
        clipBottom = localY(surfaceTop + height),
        clipRadius = cornerPx / scale,
        settled = t == 1f,
    )
}

/**
 * How far the card follows a finger that moved [dPx] from where it pressed: it resists, and the pull saturates at
 * 7 dp whatever the distance (glisse `menu.tsx:32`). Odd in [dPx].
 */
fun menuResistance(
    dPx: Float,
    pxPerDp: Float,
): Float {
    val d = dPx / pxPerDp
    return sign(d) * 7f * (1f - exp(-abs(d) * 0.12f / 7f)) * pxPerDp
}

/** A pressed card grows by 5 dp on its longer side, never by more than 2.5 % (glisse `menu.tsx:187-195`). */
fun menuPressScale(
    w: Float,
    h: Float,
    pxPerDp: Float,
): Float = 1f + 5f * pxPerDp / maxOf(w, h, 200f * pxPerDp)
