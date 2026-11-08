// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// The rim light measured by glisse's author from iOS 26.1 `.glassEffect()`; see glisse src/internal/refraction.ts:28-94.
package dev.rovsti.design.glass

import android.graphics.RuntimeShader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/** Width of the lit rim in px: brightest at the very edge, gone 1.22 px in. */
internal const val RIM_WIDTH = 1.22f

/** Width of the hairline under the light, px. */
internal const val RIM_EDGE_WIDTH = 0.5f

/**
 * The rim shader. The light comes from the top left and lights the left and top sides, its reflection the right and
 * bottom; it fades over the other two corners. The result is premultiplied. [shine] is 0.3 on a light page and 0.13
 * on a dark one; [edge] is the hairline colour with straight alpha.
 */
const val RIM_AGSL: String = """
uniform float2 size;
uniform float radius;
uniform float shine;
uniform half4 edge;

float sdRound(float2 p, float2 hb, float r) {
    float2 q = abs(p) - hb + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    const float RIM = 1.22;
    const float EDGE = 0.5;
    float2 hb = size * 0.5;
    float2 p = coord - hb;
    float corner = clamp(radius, 0.0, min(hb.x, hb.y));
    float depth = -sdRound(p, hb, corner);
    if (depth < -0.5 || depth > 2.0 * RIM) return half4(0.0);
    float qx = abs(p.x) - hb.x + corner;
    float qy = abs(p.y) - hb.y + corner;
    float2 n = float2(0.0);
    if (qx > 0.0 && qy > 0.0) n = float2(sign(p.x) * qx, sign(p.y) * qy) / length(float2(qx, qy));
    else if (qx > qy) n.x = sign(p.x);
    else n.y = sign(p.y);
    float facing = abs(n.x + n.y);
    float along = max(0.0, depth) / (RIM * sqrt(max(1.0, facing)));
    float cover = clamp(depth + 0.5, 0.0, 1.0);
    float lined = clamp(min(EDGE, depth + 0.5) - max(0.0, depth - 0.5), 0.0, 1.0);
    float light = along < 1.0 ? shine * pow(min(1.0, facing), 1.5) * (1.0 - pow(along, 1.5)) * cover : 0.0;
    float line = edge.a * lined * (1.0 - light);
    return half4(half3(light) + edge.rgb * line, light + line);
}
"""

/** The two parts of the rim at one point: white [light] and the hairline [line] under it. */
data class RimSample(
    val light: Float,
    val line: Float,
) {
    val alpha: Float get() = light + line
}

/**
 * The CPU twin of [RIM_AGSL] (glisse `rimMap`, per pixel). [x] and [y] are px from the top-left corner of a [w] x [h]
 * rounded rectangle with corner [r]. [density] is the sampling density (pixels per px): the shader runs at 1, glisse's
 * test at 3. [edgeAlpha] is the hairline's straight alpha.
 */
fun rimSample(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
    shine: Float,
    edgeAlpha: Float = 0f,
    density: Float = 1f,
): RimSample {
    val hw = w / 2f
    val hh = h / 2f
    val px = x - hw
    val py = y - hh
    val corner = r.coerceIn(0f, min(hw, hh))
    val half = 0.5f / density
    val depth = -roundRectSdf(px, py, hw, hh, corner)
    if (depth < -half || depth > 2f * RIM_WIDTH) return RimSample(0f, 0f)
    val qx = abs(px) - hw + corner
    val qy = abs(py) - hh + corner
    var nx = 0f
    var ny = 0f
    if (qx > 0f && qy > 0f) {
        val length = hypot(qx, qy)
        nx = sign(px) * qx / length
        ny = sign(py) * qy / length
    } else if (qx > qy) {
        nx = sign(px)
    } else {
        ny = sign(py)
    }
    val facing = abs(nx + ny)
    val along = max(0f, depth) / (RIM_WIDTH * sqrt(max(1f, facing)))
    val cover = ((depth + half) * density).coerceIn(0f, 1f)
    val lined = ((min(RIM_EDGE_WIDTH, depth + half) - max(0f, depth - half)) * density).coerceIn(0f, 1f)
    val light = if (along < 1f) shine * min(1f, facing).pow(1.5f) * (1f - along.pow(1.5f)) * cover else 0f
    val line = edgeAlpha * lined * (1f - light)
    return RimSample(light, line)
}

/** [rimSample]'s alpha only. */
fun rimAlpha(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
    shine: Float,
    edgeAlpha: Float = 0f,
    density: Float = 1f,
): Float = rimSample(x, y, w, h, r, shine, edgeAlpha, density).alpha

/**
 * The rim drawn by the CPU twin into a bitmap, [density] pixels per px (the catalog shows it at 4x). Straight-alpha
 * colour like glisse: white light over the [edge] hairline.
 */
fun rimImage(
    w: Int,
    h: Int,
    r: Float,
    shine: Float,
    edge: Color,
    density: Int = 4,
): ImageBitmap {
    val columns = w * density
    val rows = h * density
    val pixels = IntArray(columns * rows)
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val s =
                rimSample(
                    (column + 0.5f) / density,
                    (row + 0.5f) / density,
                    w.toFloat(),
                    h.toFloat(),
                    r,
                    shine,
                    edge.alpha,
                    density.toFloat(),
                )
            val a = s.alpha
            if (a <= 0f) continue

            fun channel(edgeValue: Float) =
                ((255f * s.light + 255f * edgeValue * s.line) / a).roundToInt().coerceIn(0, 255)
            pixels[row * columns + column] =
                ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                (channel(edge.red) shl 16) or (channel(edge.green) shl 8) or channel(edge.blue)
        }
    }
    val bitmap = android.graphics.Bitmap.createBitmap(columns, rows, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, columns, 0, 0, columns, rows)
    return bitmap.asImageBitmap()
}

/**
 * A brush drawing the rim shader. Built once per (size, radius, shine, edge) and cached by the glass node; animate its
 * alpha, not its geometry.
 */
class RimBrush(
    private val size: Size,
    private val radius: Float,
    private val shine: Float,
    private val edge: Color,
) : ShaderBrush() {
    private val shader: RuntimeShader =
        RuntimeShader(RIM_AGSL).also {
            it.setFloatUniform("size", size.width, size.height)
            it.setFloatUniform("radius", radius)
            it.setFloatUniform("shine", shine)
            // Straight alpha colour, as the AGSL reads `edge.rgb * edge.a` itself.
            it.setFloatUniform("edge", edge.red, edge.green, edge.blue, edge.alpha)
        }

    override fun createShader(size: Size): Shader = shader

    internal fun matches(
        size: Size,
        radius: Float,
        shine: Float,
        edge: Color,
    ) = this.size == size && this.radius == radius && this.shine == shine && this.edge == edge
}
