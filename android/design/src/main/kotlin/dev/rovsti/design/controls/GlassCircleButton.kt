/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/GlassCircleButton.kt
 * Changes for Rovsti: package dev.rovsti.design.controls; the Haze fallback branch, the artwork ambient colour and the
 * ripple (Material) are gone; LocalContentColor became LocalIosContentColor; a debugTag names the call site in
 * BackdropDebug; with no app backdrop in scope the button takes Convx's own flat branch (forceFlat); touchTarget keeps
 * a 48 dp tap area around the 44 dp circle (the extra 2 dp are not drawn); elasticStretchLayerBlock lives in
 * ios/foundation/ConvxUtils.kt.
 */
package dev.rovsti.design.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.catalog.utils.InteractiveHighlight
import dev.rovsti.design.glass.GlassComponent
import dev.rovsti.design.glass.LocalGlassEffectConfig
import dev.rovsti.design.glass.glassUsesDarkAppearance
import dev.rovsti.design.glass.isGlassAllowed
import dev.rovsti.design.glass.liquidGlass
import dev.rovsti.design.glass.resolvedTextColor
import dev.rovsti.design.glass.withFixedStyle
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.ios.foundation.LocalIosContentColor
import dev.rovsti.design.ios.foundation.additiveChrome
import dev.rovsti.design.ios.foundation.elasticStretchLayerBlock
import dev.rovsti.design.shapes.ContinuousRoundedRectangle
import dev.rovsti.design.theme.GlassTintTokens

// Kyant0/Capsule's continuous (superellipse) circle instead of CircleShape's
// circular-arc corners: smoother, and it is what liquidGlass's lens effect is tuned against.
private val GlassCircleShape = ContinuousRoundedRectangle(percent = 50)

@Composable
internal fun rememberElasticStretch(): InteractiveHighlight {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope) { InteractiveHighlight(animationScope) }
}

/**
 * Wide, soft drop shadow under floating top chrome (back button, menu pills): 24 dp blur, nudged 4 dp down, so the
 * glass reads as lifted off the page. Shared by every top-bar button so they sit at the same height.
 */
fun Modifier.chromeShadow(shape: Shape): Modifier =
    dropShadow(
        shape,
        Shadow(
            radius = 24.dp,
            color = Color.Black,
            offset = DpOffset(0.dp, 4.dp),
            alpha = 0.18f,
        ),
    )

/**
 * A floating circular glass button (back, share, toolbar). Falls back to a translucent circle when glass is disabled
 * or unsupported. [size] is the drawn circle; [touchTarget] the tap area around it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    size: Dp = 44.dp,
    touchTarget: Dp = size,
    enabled: Boolean = true,
    // The caller's current background colour for the rim; Unspecified keeps the plain default rim.
    rimTintColor: Color = Color.Unspecified,
    // Photos (account avatar) must not run through additiveChrome: Plus adds white over every pixel.
    applyAdditiveChrome: Boolean = true,
    // Draw a flat fill while keeping the button interaction effects.
    forceFlat: Boolean = false,
    // Dims the glass without an outer layer: an alpha layer around the node would cut the 24 dp shadow to a square.
    alpha: Float = 1f,
    debugTag: String = "button.icon",
    content: @Composable () -> Unit,
) {
    // Non-nav glass: thinner lens per spec Q17 (.35/.40), fixed style.
    val glassConfig = LocalGlassEffectConfig.current.withFixedStyle().forAccessory()
    // These float over content the same way the nav bar does and are meant to read as the same material, so they
    // follow the nav bar's own on/off switch and its effect values rather than only the global one.
    val hasBackdrop = LocalIosBackdrop.current != null
    val useGlass = !forceFlat && hasBackdrop && glassConfig.isEnabledFor(GlassComponent.NAV_BAR) && isGlassAllowed()

    val isDark = glassUsesDarkAppearance()
    val isHeroContext = rimTintColor.isSpecified
    val highlightAlpha = GlassTintTokens.pluckOpacity(isDark, isHeroContext)
    val effectiveRimTintColor =
        if (rimTintColor.isSpecified) rimTintColor else GlassTintTokens.pluckColor(isDark, Color.Unspecified)

    val fallbackFill =
        GlassTintTokens.baseSurfaceColor(isDark).copy(
            alpha =
                if (isDark) GlassTintTokens.NavPillFillOpacityDark else GlassTintTokens.NavPillFillOpacityLight,
        )

    val backgroundModifier =
        if (useGlass) {
            Modifier.liquidGlass(
                config = glassConfig,
                shape = GlassCircleShape,
                highlightAlpha = highlightAlpha,
                rimTintColor = effectiveRimTintColor,
                debugTag = debugTag,
            )
        } else {
            Modifier.clip(GlassCircleShape).background(fallbackFill)
        }
    // Hero exemption Q14: tinted screens force dark chrome even in light mode.
    val effectiveDark = isDark || isHeroContext
    val contentColor =
        if (useGlass) {
            glassConfig.resolvedTextColor(activeTint = Color.Unspecified)
        } else {
            GlassTintTokens.glassContentColor(isDark = effectiveDark, activeTint = Color.Unspecified)
        }

    val interactionSource = remember { MutableInteractionSource() }
    val elasticStretch = rememberElasticStretch()
    val inset = (touchTarget - size).coerceAtLeast(0.dp) / 2

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .graphicsLayer(elasticStretchLayerBlock(elasticStretch))
                .size(touchTarget)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    role = Role.Button,
                    indication = null,
                    interactionSource = interactionSource,
                ).then(elasticStretch.gestureModifier),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .padding(inset)
                    .size(size)
                    .chromeShadow(GlassCircleShape)
                    .then(if (alpha < 1f) Modifier.graphicsLayer { this.alpha = alpha } else Modifier)
                    .clip(GlassCircleShape)
                    .then(backgroundModifier)
                    .then(elasticStretch.modifier),
        ) {
            CompositionLocalProvider(LocalIosContentColor provides contentColor) {
                Box(modifier = Modifier.additiveChrome(applyAdditiveChrome && useGlass && effectiveDark)) {
                    content()
                }
            }
        }
    }
}

/**
 * A flat circular button whose fill and content colour both come from a tint: the back/share/favourite buttons on a
 * tinted screen that wants its whole chrome in that colour rather than floating glass over it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContrastCircleButton(
    onTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    size: Dp = 44.dp,
    filled: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val elasticStretch = rememberElasticStretch()

    val bg = if (filled) onTint else onTint.copy(alpha = 0.16f)
    val contentColor =
        if (filled) {
            if (onTint == Color.White) Color.Black else Color.White
        } else {
            onTint
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .graphicsLayer(elasticStretchLayerBlock(elasticStretch))
                .size(size)
                .chromeShadow(GlassCircleShape)
                .clip(GlassCircleShape)
                .background(bg)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    role = Role.Button,
                    indication = null,
                    interactionSource = interactionSource,
                ).then(elasticStretch.modifier)
                .then(elasticStretch.gestureModifier),
    ) {
        CompositionLocalProvider(LocalIosContentColor provides contentColor) {
            content()
        }
    }
}
