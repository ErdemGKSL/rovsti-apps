package dev.rovsti.design.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.rovsti.design.theme.GlassTokens
import dev.rovsti.design.theme.RovstiColors
import kotlin.math.max
import kotlin.math.min

/** Drop shadow under a surface. [radiusDp] 0 means none. */
@Immutable
data class GlassShadow(
    val radiusDp: Float,
    val yDp: Float,
    val color: Color,
) {
    companion object {
        val None = GlassShadow(0f, 0f, Color.Transparent)
    }
}

/**
 * Everything the glass node needs, resolved for a tier and a theme. Pure data, so the node can key its cached
 * effects on it.
 *
 * Capture scale and blur: the blur radius and every lens distance are in dp here and are multiplied by
 * [captureScale] when the effects are built.
 */
@Immutable
data class GlassSpec(
    val tier: GlassTier,
    val blurDp: Float,
    val saturation: Float,
    val lens: LensOptions?,
    val lensAmount: Float,
    /** Translucent fill laid over the replay (Liquid, Frost). */
    val fill: Color,
    /** Solid base of the Flat fallback, drawn at [FLAT_ALPHA]. */
    val flatBase: Color,
    /** Sits between the backdrop and [fill] so labels keep 4.5:1 over any backdrop. */
    val scrim: Color,
    val rimShine: Float,
    /** Hairline colour of Liquid and Frost. */
    val edge: Color,
    /** Hairline colour of Flat: white 0.12 on dark, black 0.08 on light. */
    val flatEdge: Color,
    val cornerDp: Float,
    val shadow: GlassShadow,
) {
    /** Resolution at which the backdrop is captured; see [captureScale]. */
    val captureScale: Float get() = captureScale(blurDp)

    fun withTier(tier: GlassTier): GlassSpec = if (tier == this.tier) this else copy(tier = tier)

    companion object {
        const val FLAT_ALPHA = 0.85f

        /** Corner value meaning "capsule": clamped to half the shorter side when drawn. */
        const val CAPSULE = 10_000f

        /** Tab bar, top chrome, Stops floating controls. */
        fun bar(
            tokens: GlassTokens,
            colors: RovstiColors,
            tier: GlassTier,
            cornerDp: Float = CAPSULE,
        ) = GlassSpec(
            tier = tier,
            blurDp = 3f,
            saturation = if (colors.isDark) 1.7f else 1.5f,
            lens = LensOptions.Glass,
            lensAmount = 1f,
            fill = tokens.glass,
            flatBase = flatBase(colors),
            scrim = colors.glassScrim,
            rimShine = tokens.rimShine,
            edge = tokens.edge,
            flatEdge = flatEdge(colors),
            cornerDp = cornerDp,
            shadow = GlassShadow(24f, 4f, Color.Black.copy(alpha = 0.18f)),
        )

        /** Menus and popovers. A weak lens (amount 0.3) and a quieter rim. */
        fun popover(
            tokens: GlassTokens,
            colors: RovstiColors,
            tier: GlassTier,
            cornerDp: Float = 24f,
        ) = GlassSpec(
            tier = tier,
            blurDp = 8f,
            saturation = 1.8f,
            lens = LensOptions.Glass,
            lensAmount = 0.3f,
            fill = tokens.surface,
            flatBase = flatBase(colors),
            scrim = colors.glassScrim,
            rimShine = 0.2f,
            edge = tokens.surfaceEdge,
            flatEdge = flatEdge(colors),
            cornerDp = cornerDp,
            shadow = GlassShadow(40f, 8f, Color.Black.copy(alpha = 0.12f)),
        )

        /** Sheets: blur, no lens, no rim light, hairline only. */
        fun sheet(
            tokens: GlassTokens,
            colors: RovstiColors,
            tier: GlassTier,
            cornerDp: Float = 28f,
        ) = GlassSpec(
            tier = tier,
            blurDp = if (colors.isDark) 14f else 12f,
            saturation = if (colors.isDark) 1.7f else 1.5f,
            lens = null,
            lensAmount = 0f,
            fill = tokens.surface,
            flatBase = flatBase(colors),
            scrim = colors.glassScrim,
            rimShine = 0f,
            edge = tokens.surfaceEdge,
            flatEdge = flatEdge(colors),
            cornerDp = cornerDp,
            shadow = GlassShadow.None,
        )

        /** Switch and slider thumbs: a small blur, the thumb lens, a white face. */
        fun thumb(
            tokens: GlassTokens,
            colors: RovstiColors,
            tier: GlassTier,
            lens: LensOptions = LensOptions.SwitchThumb,
        ) = GlassSpec(
            tier = tier,
            blurDp = 2f,
            saturation = 1.5f,
            lens = lens,
            lensAmount = 1f,
            fill = tokens.segmentThumb.copy(alpha = max(tokens.segmentThumb.alpha, 0.3f)),
            flatBase = Color.White,
            scrim = Color.Transparent,
            rimShine = tokens.rimShine,
            edge = tokens.edge,
            flatEdge = flatEdge(colors),
            cornerDp = CAPSULE,
            shadow = GlassShadow(24f, 6f, Color.Black.copy(alpha = 0.2f)),
        )

        private fun flatBase(colors: RovstiColors) = if (colors.isDark) Color(0xFF1C1C1F) else Color.White

        private fun flatEdge(colors: RovstiColors) =
            if (colors.isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    }
}

/**
 * Capture resolution for a blur radius: clear glass at full resolution (crisp lens edges), a blur of 2.5 dp or more at
 * 30 %. Every pixel parameter of the effects is multiplied by the same scale.
 */
fun captureScale(blurDp: Float): Float = 1f - min(max(blurDp / 2.5f, 0f), 1f) * 0.7f
