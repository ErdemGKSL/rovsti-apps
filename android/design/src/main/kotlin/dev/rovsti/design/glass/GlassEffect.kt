package dev.rovsti.design.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.geometry.Size

/**
 * The effect chain of one glass layer: blur first, then the lens bends the blurred image (`createChainEffect(outer,
 * inner)` runs the inner effect first). Saturation is not here: it is the layer's colour filter.
 *
 * [size] and [cornerPx] are in the layer's pixels (already multiplied by the capture scale); [unit] is px per dp at
 * that resolution. Returns null, never an identity effect: an identity effect still costs a pass.
 */
internal fun glassEffect(
    size: Size,
    cornerPx: Float,
    spec: GlassSpec,
    unit: Float,
): RenderEffect? {
    val blurPx = spec.blurDp * unit
    val blur =
        if (blurPx > 0f) RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP) else null
    val lensOptions = spec.lens
    if (spec.tier != GlassTier.Liquid || lensOptions == null || spec.lensAmount <= 0f) return blur
    val lens = GlassLensShader.effect(size, cornerPx, lensOptions, spec.lensAmount, unit)
    return if (blur != null) RenderEffect.createChainEffect(lens, blur) else lens
}
