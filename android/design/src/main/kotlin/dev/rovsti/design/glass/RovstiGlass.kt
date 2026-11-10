package dev.rovsti.design.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.design.backdrop.isRenderEffectSupported
import dev.rovsti.design.ios.foundation.IosGlassSpec
import dev.rovsti.design.ios.foundation.IosGlassStyle
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.ios.foundation.iosGlass
import dev.rovsti.design.ios.foundation.iosMenuSurface
import dev.rovsti.design.theme.RovstiTheme

/**
 * Rovsti's names for the glass treatments the Convx stack offers (Rovsti's own thin layer over the ported
 * [liquidGlass], [iosGlass] and [iosMenuSurface]; no Convx counterpart). Every call site goes through one of the
 * functions below, so a tag in [BackdropDebug] names the surface and there is exactly one place that decides between
 * the backdrop effect and the translucent fallback.
 */
enum class GlassPreset(
    internal val config: GlassEffectConfig,
    /** Exact rim opacity; null keeps the theme's rim. */
    internal val rimAlpha: Float? = null,
) {
    /** Tab bars, toolbar capsules, top chrome: Convx's nav-bar values (3 dp blur, lens 0.60 / 0.69). */
    Bar(GlassEffectConfig()),

    /** Round and capsule buttons: Convx's accessory glass, "thinner lens per spec Q17" (0.35 / 0.40). */
    Button(GlassEffectConfig(lensHeight = 0.35f, lensAmount = 0.40f)),

    /** Switch and slider thumbs, small selection pucks. */
    Thumb(GlassEffectConfig(blurRadius = 2f, lensHeight = 0.35f, lensAmount = 0.40f)),

    /**
     * The frosted discs of the call screens (Mute, Keypad, Audio ..., the collapse button): the Convx backdrop blur
     * of the call background under a white 20 % tint in both themes (a call screen is always dark), a 0.5 dp white
     * 35 % rim, no dark outer rim and no shadow, white glyphs.
     */
    CallControl(
        GlassEffectConfig(
            vibrancy = 1f,
            blurRadius = 10f,
            lensHeight = 0.35f,
            lensAmount = 0.40f,
            surfaceTintColor = Color.White,
            surfaceOpacity = CallControlTint,
            highlightColor = Color.White,
            highlightWidth = 0.5.dp,
            darkRim = false,
        ),
        rimAlpha = CallControlRim,
    ),
}

/**
 * Glass from the app backdrop ([LocalIosBackdrop], provided to chrome and overlays by `RovstiBackdropRoot` and to
 * the chrome of a `BackdropScope`) with a [GlassPreset].
 *
 * A control inside the recorded page content has no backdrop it may sample (a layer cannot draw into itself), so it
 * paints the grouped-surface fill with a hairline instead; Convx does the same for buttons in its screens
 * (`forceFlat`). That is a design decision, not a fallback, and it is not recorded in [BackdropDebug]. Reduce
 * transparency and old devices still take Convx's translucent fallback inside [liquidGlass] and are recorded.
 */
@Composable
fun Modifier.rovstiGlass(
    preset: GlassPreset,
    shape: CornerBasedShape,
    debugTag: String,
    outerShadow: Boolean = true,
): Modifier {
    if (preset == GlassPreset.CallControl) {
        val real =
            LocalIosBackdrop.current != null &&
                isGlassAllowed() &&
                !LocalGlassSettings.current.reduceTransparency &&
                isRenderEffectSupported()
        if (!real) {
            // The call screen is always dark: the plain translucent white disc, never the page-coloured fallback.
            if (LocalIosBackdrop.current != null) BackdropDebug.fallback(debugTag)
            return clip(shape).background(Color.White.copy(alpha = CallControlFallbackFill))
        }
    }
    if (LocalIosBackdrop.current == null) {
        val colors = RovstiTheme.colors
        val edge = RovstiTheme.glass.edge
        return clip(shape)
            .background(colors.groupedSurface.copy(alpha = ContentLevelFill))
            .drawWithContent {
                drawContent()
                drawOutline(shape.createOutline(size, layoutDirection, this), edge, style = Stroke(0.5.dp.toPx()))
            }
    }
    return liquidGlass(
        config = preset.config,
        shape = shape,
        outerShadow = outerShadow && preset != GlassPreset.CallControl,
        highlightAlpha = preset.rimAlpha ?: 0.55f,
        debugTag = debugTag,
    )
}

private const val CallControlTint = 0.20f
private const val CallControlRim = 0.35f
private const val CallControlFallbackFill = 0.24f

private const val ContentLevelFill = 0.96f

/** Convx's menu material (blur, saturation, lens, theme fill, panel shadow) for menus, popovers, alerts, action groups. */
@Composable
fun Modifier.panelGlass(
    shape: Shape,
    debugTag: String,
    shadow: Boolean = true,
): Modifier = iosMenuSurface(shape = shape, backdrop = IosTheme.backdrop, panelShadow = shadow, debugTag = debugTag)

/**
 * Convx's sheet material (`iosGlass` with [IosGlassSpec.Sheet]: 8 dp blur, tall lens, deep shadow). [wash] is laid over
 * the glass so list text on a sheet stays readable; Convx's sheet is the bare glass.
 */
@Composable
fun Modifier.sheetGlass(
    shape: Shape,
    debugTag: String,
    wash: Color = Color.Transparent,
): Modifier =
    iosGlass(
        shape = shape,
        style = IosGlassStyle.Regular,
        // No lens: a sheet runs off the bottom of the screen, where the refraction band showed as a grey seam.
        spec = IosGlassSpec.Sheet.copy(refractionHeight = 0.dp, refractionAmount = 0.dp),
        backdrop = IosTheme.backdrop,
        wash = wash,
        debugTag = debugTag,
    )
