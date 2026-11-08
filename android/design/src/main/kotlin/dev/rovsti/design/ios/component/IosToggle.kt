// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang

/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The thumb, lens and spring recipe are Kyant0/AndroidLiquidGlass (Apache License 2.0, Copyright 2025 Kyant0),
 * catalog LiquidToggle, as vendored by Convx in backdrop/catalog/components/LiquidToggle.kt.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosToggle.kt
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/backdrop/catalog/components/LiquidToggle.kt
 * Changes for Rovsti: Convx's IosToggle is a flat 51x31 switch and its glass switch is the unused LiquidToggle demo; this file
 * is the demo's thumb (DampedDragAnimation, squash and stretch, lens while held) under Convx's IosToggle API. Colours come
 * from our tokens; the gesture is replaced by one that follows glisse's rules (hold before the page scroll, 4 dp drag start,
 * a tap counts within 24 dp, a cancelled touch changes nothing; see IosSwitchRules); semantics and one haptic are added;
 * the touch target is 64 x 48 dp.
 */
package dev.rovsti.design.ios.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.rovsti.design.backdrop.Backdrop
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberCanvasBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberCombinedBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.backdrop.catalog.utils.DampedDragAnimation
import dev.rovsti.design.backdrop.drawBackdrop
import dev.rovsti.design.backdrop.effects.blur
import dev.rovsti.design.backdrop.effects.lens
import dev.rovsti.design.backdrop.highlight.Highlight
import dev.rovsti.design.backdrop.shadow.InnerShadow
import dev.rovsti.design.backdrop.shadow.Shadow
import dev.rovsti.design.controls.IosSwitchRules
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.press.HoldResult
import dev.rovsti.design.press.awaitHold
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme

/** Size of the switch track (Kyant0 LiquidToggle: 64 x 28). */
private val TrackWidth = 64.dp
private val TrackHeight = 28.dp

/** Touch target: as wide as the track, 48 dp tall (Android's minimum; the track is 28 dp). */
private val HitHeight = 48.dp

/** How far the thumb travels (Kyant0: `dragWidth = 20.dp`). */
private val ThumbTravel = 20.dp

private const val DisabledAlpha = 0.4f

/**
 * SwiftUI's Toggle as a liquid-glass switch. The resting thumb is a white capsule; while it is held it grows to 1.5x,
 * turns into clear lens glass that bends the track under it, and stretches with the drag velocity.
 *
 * - [onCheckedChange] null makes the switch a read-out (the row around it owns the click).
 * - [backdrop] is what the held thumb refracts besides its own track. Pages that are recorded by the root backdrop pass
 *   nothing: the thumb then refracts a flat copy of the surface colour and the track. Pass a layer to refract a busy
 *   background.
 * - [label] puts a text before the switch, space-between, like SwiftUI's labelled Toggle.
 * - [exposeSemantics] false hides the node from accessibility (a row that carries the switch semantics itself).
 */
@Composable
fun IosToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    exposeSemantics: Boolean = true,
) {
    if (label == null) {
        IosSwitch(checked, onCheckedChange, modifier, tint, enabled, backdrop, exposeSemantics)
    } else {
        Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RovstiText(label, style = RovstiTheme.type.body)
            IosSwitch(checked, onCheckedChange, Modifier, tint, enabled, backdrop, exposeSemantics)
        }
    }
}

@Composable
private fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    tint: Color,
    enabled: Boolean,
    backdrop: Backdrop?,
    exposeSemantics: Boolean,
) {
    val colors = RovstiTheme.colors
    val onColor = if (tint.isSpecified) tint else IosTheme.colors.systemGreen
    val trackColor = colors.fillPrimary
    val surface = colors.groupedSurface
    val sourceBackdrop = backdrop ?: rememberCanvasBackdrop(remember(surface) { { drawRect(surface) } })
    val haptics = LocalRovstiHaptics.current
    val interactive = enabled && onCheckedChange != null

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val travelPx = with(density) { ThumbTravel.toPx() }
    val scope = rememberCoroutineScope()
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)
    val currentHaptics by rememberUpdatedState(haptics)

    val damped =
        remember(scope) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = if (checked) 1f else 0f,
                valueRange = 0f..1f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
        }
    // The state changed from outside (a preference reload, a parent resetting it): follow it.
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (damped.targetValue != target) damped.animateToValue(target)
    }

    fun commit(next: Boolean) {
        damped.animateToValue(if (next) 1f else 0f)
        if (next != currentChecked) {
            currentHaptics.toggle(next)
            currentOnChange?.invoke(next)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val thumbBackdrop =
        rememberCombinedBackdrop(
            sourceBackdrop,
            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                val progress = damped.pressProgress
                val scaleX = lerp(2f / 3f, 0.75f, progress)
                val scaleY = lerp(0f, 0.75f, progress)
                scale(scaleX, scaleY) {
                    drawBackdrop()
                }
            },
        )

    val semanticsModifier =
        if (exposeSemantics) {
            Modifier.semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                if (!interactive) disabled()
                onClick("toggle") {
                    if (!interactive) return@onClick false
                    commit(!currentChecked)
                    true
                }
            }
        } else {
            Modifier.clearAndSetSemantics {}
        }

    Box(
        modifier
            .size(TrackWidth, HitHeight)
            .then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha))
            .then(semanticsModifier)
            .pointerInput(interactive, isLtr) {
                if (!interactive) return@pointerInput
                val sign = if (isLtr) 1f else -1f
                val pxPerDp = density.density
                val nearPx = IosSwitchRules.NEAR_DP.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val from = if (currentChecked) 1f else 0f
                    val outcome = awaitHold(down, slop)
                    when (outcome.result) {
                        // A quick vertical swipe belongs to the page: the switch does nothing at all.
                        HoldResult.Scrolled -> {
                            return@awaitEachGesture
                        }

                        HoldResult.Released -> {
                            val near = IosSwitchRules.nearBox(outcome.position.y, size.height.toFloat(), nearPx)
                            val next = IosSwitchRules.onRelease(currentChecked, false, from, near)
                            if (next != currentChecked) commit(next)
                            return@awaitEachGesture
                        }

                        HoldResult.Held -> {
                            Unit
                        }
                    }

                    damped.press()
                    down.consume()
                    var moved = IosSwitchRules.startsDrag((outcome.position.x - down.position.x) / pxPerDp)
                    var value = from
                    // A lost touch (a parent took it, the window cancelled it) ends the coroutine: the finally restores.
                    var settled = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            val dx = (change.position.x - down.position.x) * sign
                            if (!moved && IosSwitchRules.startsDrag(dx / pxPerDp)) moved = true
                            if (moved) value = (from + dx / travelPx).coerceIn(0f, 1f)
                            if (!change.pressed) {
                                val near = IosSwitchRules.nearBox(change.position.y, size.height.toFloat(), nearPx)
                                val next = IosSwitchRules.onRelease(currentChecked, moved, value, near)
                                change.consume()
                                settled = true
                                if (next != currentChecked) commit(next) else damped.animateToValue(from)
                                return@awaitEachGesture
                            }
                            if (moved) damped.updateValue(value)
                            change.consume()
                        }
                    } finally {
                        if (!settled) damped.animateToValue(from)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier, contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .layerBackdrop(trackBackdrop)
                    .clip(RoundedCornerShape(percent = 50))
                    .drawBehind { drawRect(lerp(trackColor, onColor, damped.value)) }
                    .size(TrackWidth, TrackHeight),
            )
            Box(
                Modifier
                    .graphicsLayer {
                        val padding = 2f.dp.toPx()
                        translationX =
                            if (isLtr) {
                                lerp(padding, padding + travelPx, damped.value)
                            } else {
                                lerp(-padding, -(padding + travelPx), damped.value)
                            }
                    }.drawBackdrop(
                        backdrop = thumbBackdrop,
                        shape = { RoundedCornerShape(percent = 50) },
                        effects = {
                            val progress = damped.pressProgress
                            blur(8f.dp.toPx() * (1f - progress))
                            lens(
                                5f.dp.toPx() * progress,
                                10f.dp.toPx() * progress,
                                chromaticAberration = true,
                            )
                        },
                        highlight = {
                            val progress = damped.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress,
                            )
                        },
                        shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.14f)) },
                        innerShadow = {
                            val progress = damped.pressProgress
                            InnerShadow(radius = 4f.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            scaleX = damped.scaleX
                            scaleY = damped.scaleY
                            val velocity = damped.velocity / 50f
                            scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = damped.pressProgress
                            drawRect(Color.White.copy(alpha = 1f - progress))
                        },
                        debugTag = "toggle.thumb",
                    ).size(40f.dp, 24f.dp),
            )
        }
    }
}
