// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported); the press stretch is the one of GlassCircleButton.
package dev.rovsti.design.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassPreset
import dev.rovsti.design.glass.rovstiGlass
import dev.rovsti.design.ios.foundation.LocalIosContentColor
import dev.rovsti.design.ios.foundation.elasticStretchLayerBlock
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.shapes.ContinuousRoundedRectangle

/** The glyph colour of an active (solid white) call toggle. */
val CallControlActiveGlyph = Color(0xFF1C1C1E)

/** The test tag of the solid white fill an active toggle shows (the tests read its presence and alpha). */
const val CallControlActiveFillTag = "call-control-active-fill"

private val CallDisc = ContinuousRoundedRectangle(percent = 50)
private const val ActiveFadeMillis = 180
private const val CallDisabledAlpha = 0.45f
private const val KickScale = 0.9f

/**
 * A circular control of the call screens: frosted glass ([GlassPreset.CallControl]) over the call background, white
 * glyph. [active] (mute on, speaker on, hold on) cross-fades in 180 ms to a solid white disc whose glyph turns
 * `#1C1C1E`, with a 0.8 / 300 spring on the scale; a disabled control is drawn at 45 % alpha. [solid] (the End
 * button) paints a solid colour instead of glass. The content lambda receives the glyph colour to draw with.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    touchTarget: Dp = size,
    active: Boolean = false,
    enabled: Boolean = true,
    solid: Color? = null,
    debugTag: String = "call.control",
    content: @Composable (glyph: Color) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val fill by animateFloatAsState(
        if (active) 1f else 0f,
        if (reduce) tween(0) else tween(ActiveFadeMillis),
        label = "callActiveFill",
    )
    val glyph by animateColorAsState(
        if (active) CallControlActiveGlyph else Color.White,
        if (reduce) tween(0) else tween(ActiveFadeMillis),
        label = "callGlyph",
    )
    // A toggle that turns on or off kicks the scale down and springs it back (not on the first composition).
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(active) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (reduce) return@LaunchedEffect
        scale.snapTo(KickScale)
        scale.animateTo(1f, RovstiMotion.callToggle(1f))
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
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = if (enabled) 1f else CallDisabledAlpha
                    }.clip(CallDisc)
                    .then(
                        if (solid != null) {
                            Modifier.background(solid)
                        } else {
                            Modifier.rovstiGlass(GlassPreset.CallControl, CallDisc, debugTag, outerShadow = false)
                        },
                    ).then(elasticStretch.modifier),
        ) {
            if (solid == null && fill > 0f) {
                Box(
                    Modifier
                        .size(size)
                        .graphicsLayer { alpha = fill }
                        .background(Color.White)
                        .testTag(CallControlActiveFillTag),
                )
            }
            CompositionLocalProvider(LocalIosContentColor provides (if (solid != null) Color.White else glyph)) {
                content(if (solid != null) Color.White else glyph)
            }
        }
    }
}
