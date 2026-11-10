/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/GlassEffect.kt
 * Changes for Rovsti: package dev.rovsti.design.glass ; music-only locals (ambient background, player blur, artwork hero tint, loop bucket, Apple Music UI) dropped; appearance comes from LocalSampledChromeDark or the Rovsti theme.
 */
package dev.rovsti.design.glass

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.Backdrop
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.design.backdrop.BackdropEffectScope
import dev.rovsti.design.backdrop.backdrops.LayerBackdrop
import dev.rovsti.design.backdrop.drawBackdrop
import dev.rovsti.design.backdrop.effects.blur
import dev.rovsti.design.backdrop.effects.colorControls
import dev.rovsti.design.backdrop.effects.lens
import dev.rovsti.design.backdrop.highlight.Highlight
import dev.rovsti.design.backdrop.highlight.HighlightStyle
import dev.rovsti.design.backdrop.isRenderEffectSupported
import dev.rovsti.design.backdrop.poolLens
import dev.rovsti.design.backdrop.shadow.Shadow
import dev.rovsti.design.ios.foundation.LocalIosColors
import dev.rovsti.design.ios.foundation.outerDarkRim
import dev.rovsti.design.theme.GlassTintTokens
import dev.rovsti.design.theme.isHueless

/**
 * User-configurable parameters of the liquid glass effect, sourced from DataStore
 * preferences in [com.convx.music.MainActivity] and distributed through
 * [LocalGlassEffectConfig].
 */
@Stable
data class GlassEffectConfig(
    val globalEnabled: Boolean = true,
    val vibrancy: Float = 2.0f,
    /** Blur in dp applied to glass pills. Nav spec = 3dp (Q16). */
    val blurRadius: Float = 3f,
    /** 0..1, mapped to 0..[LENS_MAX_DP] dp of lens refraction height. Nav spec .60 (Q16). */
    val lensHeight: Float = 0.60f,
    /** 0..1, mapped to 0..[LENS_MAX_DP] dp of lens refraction amount. Nav spec .69 (Q16). */
    val lensAmount: Float = 0.69f,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = false,
    /** [Color.Unspecified] means adaptive: derived from the theme surface. */
    val surfaceTintColor: Color = Color.Unspecified,
    /** Specular rim ("pluck") colour. [Color.Unspecified] keeps the default white. */
    val highlightColor: Color = Color.Unspecified,
    /** Specular rim opacity, 0..1. */
    val highlightOpacity: Float = EdgeHighlightAlpha,
    /** Which rendering style every glass surface uses. */
    val style: GlassStyle = GlassStyle.LIQUID,
    /** Wash painted over the nav bar's selection puck. [Color.Unspecified] adapts
     *  to the theme: a dark wash on dark, a light one on light. */
    val puckColor: Color = Color.Unspecified,
    /** Puck wash opacity at rest. It eases toward clear while pressed. */
    val puckOpacity: Float = 0.8f,
    val surfaceOpacity: Float = 0.5f,
    /** [Color.Unspecified] means adaptive monochromatic label (dark over light
     *  content, light over dark) — resolved by [resolvedTextColor]. */
    val textColor: Color = Color.Unspecified,
    val miniPlayerEnabled: Boolean = true,
    val navBarEnabled: Boolean = true,
    /** Tablet side panel — split from [navBarEnabled] so it can differ from the
     *  phone bottom bar's glass setting instead of always mirroring it. */
    val sidePanelEnabled: Boolean = true,
    /** Side panel gets its own effect tuning (unlike the other components,
     *  which all share [vibrancy]/[blurRadius]/[lensHeight]/[lensAmount]) —
     *  defaults match those shared values so it looks identical until
     *  explicitly customized. */
    val sidePanelVibrancy: Float = 1.2f,
    val sidePanelBlurRadius: Float = 2f,
    val sidePanelLensHeight: Float = 0.4f,
    val sidePanelLensAmount: Float = 0.6f,
    val sidePanelColor: Color = Color.Unspecified,
    val sidePanelSurfaceOpacity: Float = 0.75f,
    val sidePanelTextColor: Color = Color.Unspecified,
    /** Rovsti: width of the specular rim (Convx: fixed 0.8 dp). */
    val highlightWidth: androidx.compose.ui.unit.Dp = EdgeHighlightWidth,
    /** Rovsti: the normal-blend dark outer rim; the frosted call controls turn it off. */
    val darkRim: Boolean = true,
) {
    /** Copies the side panel's effect settings into the shared fields. */
    fun forSidePanel(isDark: Boolean? = null): GlassEffectConfig =
        copy(
            vibrancy = sidePanelVibrancy,
            blurRadius = sidePanelBlurRadius,
            lensHeight = sidePanelLensHeight,
            lensAmount = sidePanelLensAmount,
            surfaceTintColor =
                if (sidePanelColor.isSpecified || isDark == null) {
                    sidePanelColor
                } else if (isDark) {
                    Color.Black
                } else {
                    Color.White
                },
            surfaceOpacity = sidePanelSurfaceOpacity,
            textColor = sidePanelTextColor,
        )

    /** Uses the shared blur and lens settings for accessory glass. */
    fun forAccessory(): GlassEffectConfig = this

    /** Whether glass is enabled globally and for [component]. */
    fun isEnabledFor(component: GlassComponent): Boolean =
        globalEnabled &&
            when (component) {
                GlassComponent.MINI_PLAYER -> miniPlayerEnabled
                GlassComponent.NAV_BAR -> navBarEnabled
                GlassComponent.SIDE_PANEL -> sidePanelEnabled
            }

    /** Whether any glass surface may use the captured app backdrop. */
    val anyComponentEnabled: Boolean
        get() = globalEnabled
}

/**
 * Rendering modes for glass surfaces, from refractive liquid to a translucent fill.
 */
enum class GlassStyle {
    /** Blur + saturation + lens refraction + specular rim. The default. */
    LIQUID,

    /** Blur and saturation without lens refraction or an edge rim. */
    BLUR,

    /** Translucent tint without backdrop capture or blur. */
    TRANSPARENT,
}

/** Uses a translucent tint when selected or when RenderEffect is unsupported. */
fun shouldUseTranslucentGlassFallback(
    style: GlassStyle,
    renderEffectSupported: Boolean,
    reduceTransparency: Boolean = false,
): Boolean = style == GlassStyle.TRANSPARENT || !renderEffectSupported || reduceTransparency

enum class GlassComponent {
    MINI_PLAYER,
    NAV_BAR,
    SIDE_PANEL,
}

/** Maximum lens height and amount at a preference value of 1. */
internal const val LENS_MAX_DP = 48f

/** Minimum backdrop resolution used for heavily blurred surfaces. */
internal const val MIN_GLASS_RESOLUTION_SCALE = 0.30f

/** Blur radius at which the capture can use [MIN_GLASS_RESOLUTION_SCALE]. */
internal const val FULL_QUALITY_BLUR_DP = 2.5f

/** Scales capture resolution with blur; clear surfaces remain full resolution. */
fun glassResolutionScale(blurRadiusDp: Float): Float {
    val t = (blurRadiusDp / FULL_QUALITY_BLUR_DP).coerceIn(0f, 1f)
    return 1f - t * (1f - MIN_GLASS_RESOLUTION_SCALE)
}

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], which is available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/** Whether Android classifies this device as low-RAM. */
@Composable
fun isLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
    }
}

/** Combines the API-level check with the low-RAM device check; use this to gate glass. */
@Composable
fun isGlassAllowed(): Boolean = isGlassSupported() && !isLowRamDevice()

/** Maps the 0..2 vibrancy preference to a saturation multiplier. */
fun glassSaturation(vibrancy: Float): Float = 1f + 0.5f * vibrancy.coerceIn(0f, 2f)

/** Picks readable content color from the glass tint composited over [behind]. */
fun glassContentColorFor(
    behind: Color,
    tint: Color,
    opacity: Float,
): Color {
    val effective =
        if (tint.isSpecified) {
            lerp(behind, tint, opacity.coerceIn(0f, 1f))
        } else {
            behind
        }
    // Match the app's standard on-surface text colors.
    return if (effective.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
}

/** Width of the subtle top-edge highlight. */
internal val EdgeHighlightWidth = 0.8f.dp
private const val EdgeHighlightAlpha = 0.55f

/** Range and timing for the highlight's slow angle drift. */
private const val HighlightAngleMin = 25f
private const val HighlightAngleMax = 65f
private const val HighlightDriftMillis = 7000

/** Midpoint of the [HighlightAngleMin]..[HighlightAngleMax] sweep — see the use site. */
internal const val HighlightAngleFrozen = (HighlightAngleMin + HighlightAngleMax) / 2f

val LocalGlassEffectConfig = staticCompositionLocalOf { GlassEffectConfig() }

/**
 * Whether the chrome has sampled what is actually behind it as dark (null = not sampled).
 * Convx: ui/component/ChromeToneSampler.kt (the sampler itself is not ported).
 */
val LocalSampledChromeDark = compositionLocalOf<Boolean?> { null }

/**
 * Appearance follows the app theme -- unless the chrome has sampled what is actually behind it
 * ([LocalSampledChromeDark]), which wins. (Convx also mixed in the artwork page's ambient dark chrome.)
 */
@Composable
fun glassUsesDarkAppearance(): Boolean = LocalSampledChromeDark.current ?: LocalIosColors.current.isDark

/** Forces glass on for fixed controls such as floating buttons. */
fun GlassEffectConfig.withFixedStyle(): GlassEffectConfig = copy(globalEnabled = true)

/** Resolves configured or adaptive monochromatic text color for glass chrome. */
@Composable
fun GlassEffectConfig.resolvedTextColor(
    activeTint: Color = Color.Unspecified,
    isSelected: Boolean = false,
    isTitle: Boolean = false,
    isUnselectedTabLabel: Boolean = false,
): Color {
    if (textColor.isSpecified) return textColor
    val isDark = glassUsesDarkAppearance()
    val ambient = activeTint
    // Use sampled backdrop appearance when available; otherwise account for artwork tint.
    val effectiveDark = LocalSampledChromeDark.current ?: (isDark || (ambient.isSpecified && !ambient.isHueless()))
    return dev.rovsti.design.theme.GlassTintTokens.glassContentColor(
        isDark = effectiveDark,
        activeTint = ambient,
        isSelected = isSelected,
        isTitle = isTitle,
        isUnselectedTabLabel = isUnselectedTabLabel,
    )
}

/** The backdrop content (app UI) that glass surfaces sample from. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { error("No AppBackdrop provided") }

/** Processed backdrop shared by surfaces with matching blur and vibrancy settings. */
@Stable
class PooledGlass(
    val backdrop: LayerBackdrop,
    val blurRadiusDp: Float,
    val vibrancy: Float,
)

val LocalPooledGlass = staticCompositionLocalOf<PooledGlass?> { null }

/** True while app-level motion is active. Freeze nested captures too, since recording them each frame redraws their full subtree. */
val LocalAppBackdropFrozen = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Reactive state mirror used to pause animations when backdrop capture is frozen. */
val LocalAppMotionFrozen =
    staticCompositionLocalOf<State<Boolean>> {
        object : State<Boolean> {
            override val value: Boolean get() = false
        }
    }

/**
 * Draws a glass surface from [LocalAppBackdrop], applying configured blur,
 * vibrancy, tint, and optional edge effects. On unsupported devices it paints
 * a readable tinted surface. [shape] must be a [CornerBasedShape] for lens effects.
 */
@Composable
fun Modifier.liquidGlass(
    config: GlassEffectConfig,
    shape: CornerBasedShape = RoundedCornerShape(0.dp),
    applyEdgeEffects: Boolean = true,
    blurRadiusDp: Float = config.blurRadius,
    // Component-specific specular rim opacity.
    highlightAlpha: Float = EdgeHighlightAlpha,
    // Capture resolution; use 1f for crisp, unblurred glass controls.
    backdropScale: Float = glassResolutionScale(blurRadiusDp),
    // Hold the last capture while frozen. Add any component-specific freeze at the call site.
    frozen: () -> Boolean = LocalAppBackdropFrozen.current,
    // Cache key for reusing glass output over looping backdrops; none in Rovsti.
    loopBucket: (() -> Int)? = null,
    /** Optional artwork tint for the rim; [Color.Unspecified] uses the configured highlight. */
    rimTintColor: Color = Color.Unspecified,
    /** Disable for surfaces that draw their own elevation shadow. */
    outerShadow: Boolean = true,
    /** Rovsti: names this call site in [BackdropDebug] records. */
    debugTag: String? = null,
): Modifier {
    val reduceTransparency = LocalGlassSettings.current.reduceTransparency
    if (!isGlassAllowed()) {
        BackdropDebug.fallback(debugTag)
        val dark = glassUsesDarkAppearance()
        val tint = config.surfaceTintColor.takeIf { it.isSpecified }
        return this
            .clip(shape)
            .background(opaqueFallbackFill(dark))
            .then(
                if (tint !=
                    null
                ) {
                    Modifier.background(tint.copy(alpha = config.surfaceOpacity.coerceIn(0f, 1f)))
                } else {
                    Modifier
                },
            )
    }
    val pool = LocalPooledGlass.current
    // Only when the pool holds this exact treatment; a caller that asked for its own
    // radius (the full player's heavier blur) still runs its own chain.
    // Dispersion needs the single-shape shader, so a surface asking for it keeps its own chain.
    val usePool =
        pool != null &&
            blurRadiusDp == pool.blurRadiusDp &&
            config.vibrancy == pool.vibrancy &&
            !config.chromaticAberration
    val backdrop = if (usePool) pool.backdrop else LocalAppBackdrop.current
    val density = LocalDensity.current
    // The pool is already blurred, so its sample is taken at full surface resolution --
    // there is nothing left for a reduced capture to save, and the lens reads sharper.
    val resolutionScale = if (usePool) 1f else backdropScale.coerceIn(0.05f, 1f)
    // Pixel-sized effect parameters operate on the backdrop layer at its recorded
    // resolution, so they are pre-multiplied by the resolution scale to keep the
    // same visual size.
    val blurPx = with(density) { blurRadiusDp.dp.toPx() } * resolutionScale
    val saturation = glassSaturation(config.vibrancy)
    val lensHeightPx = with(density) { (config.lensHeight * LENS_MAX_DP).dp.toPx() } * resolutionScale
    val lensAmountPx = with(density) { (config.lensAmount * LENS_MAX_DP).dp.toPx() } * resolutionScale
    // Convx tints glass with the artwork page's ambient colour; Rovsti has none, so glass is always neutral.
    val ambientColor = Color.Unspecified
    val isDark = glassUsesDarkAppearance()
    val baseSurfaceColor =
        dev.rovsti.design.theme.GlassTintTokens
            .glassBaseColor(isDark)
    // A configured tint already carries the chosen opacity. Painting the base
    // beneath it made the transparency slider barely visible, especially when
    // the default tint and base were the same color.
    val baseSurfaceOpacity =
        if (config.surfaceTintColor.isSpecified) {
            0f
        } else if (isDark) {
            dev.rovsti.design.theme.GlassTintTokens.BaseSurfaceOpacityDark
        } else {
            dev.rovsti.design.theme.GlassTintTokens.BaseSurfaceOpacityLight
        }

    val isHeroContext = ambientColor.isSpecified && !ambientColor.isHueless()
    val tintColor =
        when {
            config.surfaceTintColor.isSpecified -> {
                config.surfaceTintColor
            }

            isHeroContext -> {
                ambientColor
            }

            else -> {
                dev.rovsti.design.theme.GlassTintTokens
                    .neutralTintColor(isDark)
            }
        }
    val tintOpacity =
        when {
            config.surfaceTintColor.isSpecified -> {
                config.surfaceOpacity.coerceIn(0f, 1f)
            }

            isHeroContext -> {
                dev.rovsti.design.theme.GlassTintTokens
                    .heroTintOpacity(isDark)
            }

            else -> {
                1f
            }
        }
    // Neutral tints carry their own spec alpha (0.06/0.05); copy(alpha=1) would
    // make them opaque. Multiply so 1f means "use tint's own alpha".
    val effectiveTintAlpha = (tintColor.alpha * tintOpacity).coerceIn(0f, 1f)

    // Glass crossfades between its light and dark look instead of snapping, so entering a
    // dark-forced screen from a light one does not pop. Read in the draw phase, so animating
    // never recomposes or rebuilds the backdrop effects.
    val glassFade =
        androidx.compose.animation.core
            .tween<Color>(GlassThemeFadeMillis)
    val animBaseColor =
        androidx.compose.animation.animateColorAsState(
            baseSurfaceColor.copy(alpha = baseSurfaceOpacity),
            glassFade,
            label = "glassBase",
        )
    val animTintColor =
        androidx.compose.animation.animateColorAsState(
            tintColor.copy(alpha = effectiveTintAlpha),
            glassFade,
            label = "glassTint",
        )

    if (shouldUseTranslucentGlassFallback(config.style, isRenderEffectSupported(), reduceTransparency)) {
        BackdropDebug.fallback(debugTag)
        // Rovsti: reduce transparency means "do not show the page through me", so it gets the near-opaque fill.
        if (reduceTransparency) return this.clip(shape).background(opaqueFallbackFill(isDark))
        return this
            .clip(shape)
            .background(baseSurfaceColor.copy(alpha = baseSurfaceOpacity))
            .background(tintColor.copy(alpha = effectiveTintAlpha))
    }

    // Keep this angle fixed during composition; animated state here would rebuild
    // the effects and recapture the full backdrop on every frame.
    val animatedHighlightAngle = HighlightAngleFrozen

    // Stable lambda identities keep recomposition from invalidating the backdrop.
    val shapeBlock: () -> Shape = remember(shape) { { shape } }

    // BLUR keeps the frosted capture but drops the refraction and the rim, which
    // are the parts that read as "liquid". Cheaper too: the lens is the most
    // expensive link in the effect chain.
    val plainBlur = config.style == GlassStyle.BLUR

    val effectsBlock: BackdropEffectScope.() -> Unit =
        remember(
            saturation,
            blurPx,
            applyEdgeEffects,
            plainBlur,
            lensHeightPx,
            lensAmountPx,
            config.depthEffect,
            config.chromaticAberration,
            usePool,
        ) {
            {
                if (!usePool && saturation != 1f) {
                    colorControls(saturation = saturation)
                }
                if (!usePool && blurPx > 0f) {
                    blur(blurPx)
                }
                if (!plainBlur &&
                    applyEdgeEffects &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (lensHeightPx > 0f || lensAmountPx > 0f)
                ) {
                    if (usePool) {
                        // Refracted by the pool in its single pass for every bar surface: this
                        // layer then carries no effect, so it needs no offscreen buffer and no
                        // lens pass of its own. Same shader math, same region.
                        poolLens(
                            refractionHeight = lensHeightPx,
                            refractionAmount = lensAmountPx,
                            depthEffect = config.depthEffect,
                        )
                    } else {
                        lens(
                            refractionHeight = lensHeightPx,
                            refractionAmount = lensAmountPx,
                            depthEffect = config.depthEffect,
                            chromaticAberration = config.chromaticAberration,
                        )
                    }
                }
            }
        }

    // The rim color is separate from the surface tint; an explicit highlight wins.
    val isHeroContextForPluck = (ambientColor.isSpecified && !ambientColor.isHueless()) || rimTintColor.isSpecified
    val rimColor =
        when {
            config.highlightColor.isSpecified -> {
                config.highlightColor
            }

            rimTintColor.isSpecified -> {
                rimTintColor
            }

            else -> {
                dev.rovsti.design.theme.GlassTintTokens
                    .pluckColor(isDark, ambientColor)
            }
        }
    val baseHighlightAlpha =
        when {
            highlightAlpha != EdgeHighlightAlpha -> {
                highlightAlpha
            }

            else -> {
                dev.rovsti.design.theme.GlassTintTokens
                    .pluckOpacity(isDark, isHeroContextForPluck)
            }
        }
    // Apply both component-specific and preference opacity.
    val rimAlpha = (baseHighlightAlpha * (config.highlightOpacity / EdgeHighlightAlpha)).coerceIn(0f, 1f)

    val animRim =
        androidx.compose.animation.animateColorAsState(
            rimColor.copy(alpha = rimAlpha),
            glassFade,
            label = "glassRim",
        )
    val highlightBlock: (() -> Highlight?)? =
        remember(applyEdgeEffects, plainBlur, rimTintColor, animatedHighlightAngle, config.highlightWidth) {
            if (applyEdgeEffects && !plainBlur) {
                {
                    Highlight(
                        width = config.highlightWidth,
                        style =
                            HighlightStyle.Default(
                                color = animRim.value,
                                angle = animatedHighlightAngle,
                            ),
                    )
                }
            } else {
                null
            }
        }

    // Pooled bar surfaces draw their own elevation shadow.
    val shadowBlock: (() -> Shadow?)? =
        remember(applyEdgeEffects, usePool, outerShadow) {
            if (applyEdgeEffects && !usePool && outerShadow) ({ Shadow.Default }) else null
        }

    // The normal-blend dark rim keeps glass edges visible on light or busy surfaces.
    // A black Plus-blend stroke is invisible.
    val darkRimAlpha = (DarkRimAlpha * (config.highlightOpacity / EdgeHighlightAlpha)).coerceIn(0f, 1f)

    // RenderEffect is guaranteed supported past this point — the unsupported
    // case already returned the translucent-tint fallback above.
    val surfaceBlock: DrawScope.() -> Unit =
        remember(usePool, shape) {
            {
                // A pooled surface clips to its bounds only (the pool masks its glass), so its
                // tint is filled to the exact outline instead of relying on a shape clip.
                val outline = if (usePool) shape.createOutline(size, layoutDirection, this) else null
                val base = animBaseColor.value
                if (base.alpha > 0f) {
                    if (outline != null) drawOutline(outline, base) else drawRect(color = base, size = size)
                }
                val tint = animTintColor.value
                if (tint.alpha > 0f) {
                    if (outline != null) drawOutline(outline, tint) else drawRect(color = tint, size = size)
                }
            }
        }

    return drawBackdrop(
        backdrop = backdrop,
        shape = shapeBlock,
        effects = effectsBlock,
        highlight = highlightBlock,
        shadow = shadowBlock,
        onDrawSurface = surfaceBlock,
        backdropScale = resolutionScale,
        frozen = frozen,
        loopBucket = loopBucket,
        debugTag = debugTag,
    ).then(
        if (applyEdgeEffects && !plainBlur && config.darkRim && darkRimAlpha > 0f) {
            Modifier.outerDarkRim(
                shape = shape,
                width = DarkRimWidth,
                alpha = darkRimAlpha,
                angle = animatedHighlightAngle,
                // Pooled surfaces have no shape clip, so draw only the inner rim.
                insideOnly = usePool,
            )
        } else {
            Modifier
        },
    )
}

/**
 * Rovsti: the fill of a surface that cannot (old device, low RAM) or must not (reduce transparency) show the page through
 * it. Convx painted [GlassTintTokens.glassBaseColor] at 0.85, which is white in dark mode.
 */
internal fun opaqueFallbackFill(dark: Boolean): Color = if (dark) Color(0xF51C1C1E) else Color(0xF5F2F2F7)

/** Length of the light-to-dark glass crossfade. */
private const val GlassThemeFadeMillis = 280

private val DarkRimWidth = 1.6.dp
private const val DarkRimAlpha = 0.32f
