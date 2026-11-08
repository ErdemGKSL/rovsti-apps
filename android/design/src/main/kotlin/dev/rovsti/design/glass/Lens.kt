// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Lens shape after glisse's implementation of Aave's web liquid glass (dome slope, erf falloff) and kube.io's
// liquid glass article (refraction profile); see glisse src/internal/lens.ts:96-252,525.
package dev.rovsti.design.glass

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * How a lens bends what is under it (glisse `LensOptions`).
 *
 * [strength] is how far the rim reaches in, as a share of the shorter side. [dispersion] is how much further red
 * bends than blue. [depth] is the width of the bending band and [dome] the sag of the dome whose slope sets the bend
 * along the rim: both are dp, or shares of the shorter side when [relative] is set. [splay] (0..1) is how far the bend
 * near each side keeps to that side's own direction.
 */
@Immutable
data class LensOptions(
    val strength: Float,
    val dispersion: Float,
    val depth: Float,
    val dome: Float,
    val splay: Float,
    val relative: Boolean = false,
) {
    companion object {
        /** Buttons, bars and cards (glisse `refraction.ts:14-24`). */
        val Glass = LensOptions(0.45f, 0.4f, 0.25f, 0.5f, 1f, relative = true)

        /** The lifted tab puck (glisse `segments.tsx:73-95`, without its ink mode and soften). */
        val TabLens = LensOptions(0.2f, 2f, 4f, 0f, 1f)

        val SegmentLens = LensOptions(0.3f, 0.1f, 2.5f, 0f, 1f)

        val SwitchThumb = LensOptions(0.45f, 1f, 2f, 6f, 0.4f)

        val SliderThumb = LensOptions(0.75f, 0.65f, 2f, 5f, 0.5f)
    }
}

/** A circular arc sagging by a given amount across a half-span, with the scale that makes its mean slope 0.5. */
data class Arc(
    val radius: Float,
    val scale: Float,
)

/** The arc that sags by [sag] across [half], scaled so its slope averages a half over that span. */
fun arc(
    sag: Float,
    half: Float,
): Arc {
    val radius = (half * half + sag * sag) / (2f * sag)
    var sum = 0f
    for (i in 0..ARC_STEPS) {
        val x = i.toFloat() / ARC_STEPS * half
        val slope = x / sqrt(radius * radius - x * x)
        sum += if (i == 0 || i == ARC_STEPS) slope / 2f else slope
    }
    val mean = sum / ARC_STEPS
    return Arc(radius, if (mean > 0f) 0.5f / mean else 1f)
}

private const val ARC_STEPS = 200

fun slope(
    x: Float,
    a: Arc,
): Float {
    val inside = min(x, 0.999f * a.radius)
    return inside / sqrt(a.radius * a.radius - inside * inside) * a.scale
}

/** Signed distance to a rounded rectangle centred on the origin; negative inside. */
fun roundRectSdf(
    x: Float,
    y: Float,
    halfWidth: Float,
    halfHeight: Float,
    radius: Float,
): Float {
    val qx = abs(x) - halfWidth + radius
    val qy = abs(y) - halfHeight + radius
    return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - radius
}

/** erf(x) is close to tanh(sqrt(pi) x). */
private fun erf(x: Float): Float = tanh(1.7724538509f * x)

/**
 * The CPU reference of the lens shader, for JVM property tests. [x] and [y] are relative to the lens centre, in the
 * same unit as [w], [h] and [radius0] (px). [unit] converts non-relative `depth` and `dome` from dp to that unit.
 *
 * Returns the pull (dx, dy) already multiplied by the falloff: positive dx means the pixel shows content from further
 * towards +x, i.e. from the inside on the right rim. Null outside the shape.
 */
fun lensPull(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radius0: Float,
    o: LensOptions,
    unit: Float = 1f,
): Pair<Float, Float>? {
    val hw = w / 2f
    val hh = h / 2f
    val radius = radius0.coerceIn(0f, min(hw, hh))
    if (roundRectSdf(x, y, hw, hh, radius) >= 0f) return null
    val g = LensGeometry.of(w, h, radius, o, unit)
    var dx = if (g.across != null) sign(x) * slope(abs(x), g.across) else (x / hw).coerceIn(-1f, 1f)
    var dy = if (g.down != null) sign(y) * slope(abs(y), g.down) else (y / hh).coerceIn(-1f, 1f)
    val reach = 0.5f * min(hw, hh)
    if (o.splay < 1f && reach > 0f) {
        val keepX = max(0f, 1f - (hh - abs(y)) / reach) * (1f - o.splay)
        val keepY = max(0f, 1f - (hw - abs(x)) / reach) * (1f - o.splay)
        if (keepX > 0.001f || keepY > 0.001f) {
            val before = hypot(dx, dy)
            dx *= 1f - keepX
            dy *= 1f - keepY
            val after = hypot(dx, dy)
            if (after > 0.001f) {
                dx *= before / after
                dy *= before / after
            }
        }
    }
    val falloff = 0.5f * (1f + erf(roundRectSdf(x, y, g.innerW, g.innerH, g.innerR) * g.ramp))
    return Pair(dx * falloff, dy * falloff)
}

/** The size-dependent constants shared by the CPU twin and the uniforms; computed once per size. */
internal class LensGeometry(
    val radius: Float,
    val depth: Float,
    val innerW: Float,
    val innerH: Float,
    val innerR: Float,
    val ramp: Float,
    val across: Arc?,
    val down: Arc?,
) {
    companion object {
        fun of(
            w: Float,
            h: Float,
            radius: Float,
            o: LensOptions,
            unit: Float,
        ): LensGeometry {
            val hw = w / 2f
            val hh = h / 2f
            val share = if (o.relative) min(w, h) else unit
            val depth = o.depth * share
            val dome = o.dome * share
            val innerW = max(0f, hw - depth)
            val innerH = max(0f, hh - depth)
            val innerR = radius.coerceIn(0f, min(innerW, innerH))
            val ramp = if (depth > 0f) 1f / (depth * sqrt(2f)) else 1e6f
            val sag = max(0.01f, min(dome, min(hw, hh) - 1f))
            return LensGeometry(
                radius = radius,
                depth = depth,
                innerW = innerW,
                innerH = innerH,
                innerR = innerR,
                ramp = ramp,
                across = if (dome > 0f) arc(sag, hw) else null,
                down = if (dome > 0f) arc(sag, hh) else null,
            )
        }
    }
}

/** glisse's spectrum: the three taps' offsets as multiples of the bend, red furthest. */
fun spectrum(dispersion: Float): FloatArray = floatArrayOf(1f + 0.2f * dispersion, 1f + 0.1f * dispersion, 1f)
