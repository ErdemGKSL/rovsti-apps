// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Lens shape after glisse's implementation of Aave's web liquid glass (dome slope, erf falloff) and kube.io's
// liquid glass article (refraction profile); see glisse src/internal/lens.ts:96-252,525.
package dev.rovsti.design.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.geometry.Size
import kotlin.math.min

/**
 * The lens as AGSL: per pixel, a rounded-rect distance, a circular-dome slope, an optional splay and an erf falloff
 * that confines the bend to a rim band; three taps with a spectrum make the colour fringe. The sampling offset points
 * inward. glisse's two displacement passes collapse into this one 2D offset.
 *
 * `tanh` is written out because AGSL follows GLSL ES 1.0, which has no hyperbolic functions.
 */
const val LENS_AGSL: String = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float ramp;
uniform float2 innerHalf;
uniform float innerRadius;
uniform float2 arcR;
uniform float2 arcS;
uniform float splay;
uniform float splayReach;
uniform float reach;
uniform float3 spectrum;

float sdRound(float2 p, float2 hb, float r) {
    float2 q = abs(p) - hb + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float tanhA(float x) { float e = exp(2.0 * clamp(x, -9.0, 9.0)); return (e - 1.0) / (e + 1.0); }
float slopeOf(float a, float R, float S) { float i = min(a, 0.999 * R); return i / sqrt(R * R - i * i) * S; }

half4 main(float2 coord) {
    float2 hb = size * 0.5;
    float2 p = coord - hb;
    if (sdRound(p, hb, radius) >= 0.0) return content.eval(coord);
    float2 a = abs(p);
    float dx = arcR.x > 0.0 ? sign(p.x) * slopeOf(a.x, arcR.x, arcS.x) : clamp(p.x / hb.x, -1.0, 1.0);
    float dy = arcR.y > 0.0 ? sign(p.y) * slopeOf(a.y, arcR.y, arcS.y) : clamp(p.y / hb.y, -1.0, 1.0);
    if (splay < 1.0 && splayReach > 0.0) {
        float kx = max(0.0, 1.0 - (hb.y - a.y) / splayReach) * (1.0 - splay);
        float ky = max(0.0, 1.0 - (hb.x - a.x) / splayReach) * (1.0 - splay);
        float before = length(float2(dx, dy));
        dx *= 1.0 - kx;
        dy *= 1.0 - ky;
        float after = length(float2(dx, dy));
        if (after > 0.001) { dx *= before / after; dy *= before / after; }
    }
    float fall = 0.5 * (1.0 + tanhA(1.7724538509 * sdRound(p, innerHalf, innerRadius) * ramp));
    float2 off = -0.5 * float2(dx, dy) * fall * reach;
    half4 r = content.eval(coord + off * spectrum.x);
    half4 g = content.eval(coord + off * spectrum.y);
    half4 b = content.eval(coord + off * spectrum.z);
    return half4(r.r, g.g, b.b, max(r.a, max(g.a, b.a)));
}
"""

/** Names of the lens uniforms, in the order [lensUniforms] returns them. */
val LensUniformNames: List<String> =
    listOf(
        "size",
        "radius",
        "ramp",
        "innerHalf",
        "innerRadius",
        "arcR",
        "arcS",
        "splay",
        "splayReach",
        "reach",
        "spectrum",
    )

/**
 * Packs the lens parameters for a layer of [size] px with corner [cornerPx], without touching AGSL. [unit] is px per
 * dp at the layer's resolution (density times capture scale); [amount] (0..1) scales the bend so it never pops on at
 * full strength.
 *
 * `reach = strength * min(w, h) * amount` px. Arc radii are 0 when the lens has no dome (linear pull).
 */
fun lensUniforms(
    size: Size,
    cornerPx: Float,
    options: LensOptions,
    amount: Float,
    unit: Float = 1f,
): List<Pair<String, FloatArray>> {
    val w = size.width
    val h = size.height
    val hw = w / 2f
    val hh = h / 2f
    val radius = cornerPx.coerceIn(0f, min(hw, hh))
    val g = LensGeometry.of(w, h, radius, options, unit)
    return listOf(
        "size" to floatArrayOf(w, h),
        "radius" to floatArrayOf(radius),
        "ramp" to floatArrayOf(g.ramp),
        "innerHalf" to floatArrayOf(g.innerW, g.innerH),
        "innerRadius" to floatArrayOf(g.innerR),
        "arcR" to floatArrayOf(g.across?.radius ?: 0f, g.down?.radius ?: 0f),
        "arcS" to floatArrayOf(g.across?.scale ?: 1f, g.down?.scale ?: 1f),
        "splay" to floatArrayOf(options.splay),
        "splayReach" to floatArrayOf(0.5f * min(hw, hh)),
        "reach" to floatArrayOf(options.strength * min(w, h) * amount),
        "spectrum" to spectrum(options.dispersion),
    )
}

/** Builds the lens [RenderEffect] (API 33). One [RuntimeShader] per effect: shaders carry their own uniforms. */
object GlassLensShader {
    fun effect(
        size: Size,
        cornerPx: Float,
        options: LensOptions,
        amount: Float,
        unit: Float = 1f,
    ): RenderEffect {
        val shader = RuntimeShader(LENS_AGSL)
        for ((name, values) in lensUniforms(size, cornerPx, options, amount, unit)) {
            shader.setFloatUniform(name, values)
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
