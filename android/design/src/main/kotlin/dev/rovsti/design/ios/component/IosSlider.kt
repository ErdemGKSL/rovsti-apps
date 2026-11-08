// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang

/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The thumb, lens and spring recipe are Kyant0/AndroidLiquidGlass (Apache License 2.0, Copyright 2025 Kyant0),
 * catalog LiquidSlider, as vendored by Convx in backdrop/catalog/components/LiquidSlider.kt.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosSlider.kt
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/backdrop/catalog/components/LiquidSlider.kt
 * Changes for Rovsti: Convx's IosSlider draws a flat circle knob; this one keeps its API (value, step, ticks, labels,
 * onEditingChanged) on the LiquidSlider thumb (DampedDragAnimation, lens while held). The track ends are inset by the
 * thumb's half width so the resting thumb never leaves the component; the gesture waits the glisse hold time before it
 * takes a touch from a scrolling page (press/Hold.kt); a tap jumps the thumb; colours come from our tokens; semantics
 * (progress range, setProgress) and the 48 dp touch height are added.
 */
package dev.rovsti.design.ios.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
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
import dev.rovsti.design.press.HoldResult
import dev.rovsti.design.press.awaitHold
import dev.rovsti.design.theme.RovstiTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val SliderThumbWidth = 40.dp
private val SliderThumbHeight = 24.dp
private val SliderTrackHeight = 6.dp

/** The slider's touch height. */
private val SliderHitHeight = 48.dp

/** The resting thumb's half width: the track is inset by this much so the thumb stays inside the component. */
private val ThumbInset = SliderThumbWidth / 2

/** SwiftUI's SliderTick: a mark drawn on the track at [value] in the slider's range. */
@Immutable
data class IosSliderTick(
    val value: Float,
    val label: String? = null,
)

/** Rounds [raw] to the nearest multiple of [step] counted from [start]; no step returns [raw]. */
internal fun snapToStep(
    raw: Float,
    start: Float,
    end: Float,
    step: Float?,
): Float {
    if (step == null || step <= 0f) return raw.coerceIn(start, end)
    return (start + ((raw - start) / step).roundToInt() * step).coerceIn(start, end)
}

/**
 * SwiftUI's Slider on the liquid-glass thumb: a capsule that turns into lens glass while it is held, stretching with the
 * drag velocity. [step] snaps the value and, when [ticks] is empty and there are at most 20 steps, draws a tick per
 * step. [backdrop] is what the held thumb refracts besides its own track (see [IosToggle]).
 */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    step: Float? = null,
    ticks: List<IosSliderTick> = emptyList(),
    tint: Color = Color.Unspecified,
    minimumValueLabel: (@Composable () -> Unit)? = null,
    maximumValueLabel: (@Composable () -> Unit)? = null,
    onEditingChanged: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val colors = RovstiTheme.colors
    val activeColor = if (tint.isSpecified) tint else colors.accent
    val trackColor = colors.fillPrimary
    val tickColor = colors.labelTertiary
    val surface = colors.groupedSurface
    val sourceBackdrop = backdrop ?: rememberCanvasBackdrop(remember(surface) { { drawRect(surface) } })

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it > 0f } ?: 1f
    val resolvedTicks =
        when {
            ticks.isNotEmpty() -> {
                ticks
            }

            step != null && step > 0f -> {
                val count = (span / step).roundToInt()
                if (count in 1..20) (0..count).map { IosSliderTick(start + it * step) } else emptyList()
            }

            else -> {
                emptyList()
            }
        }
    val steps = if (step != null && step > 0f) (span / step).roundToInt() - 1 else 0

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentEditing by rememberUpdatedState(onEditingChanged)
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var lastEmitted by remember { mutableFloatStateOf(Float.NaN) }

    val damped =
        remember(scope, start, end) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = value.coerceIn(start, end),
                valueRange = start..end,
                visibilityThreshold = span * 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
        }
    // A value that did not come from this gesture (a reset, a preference reload) moves the thumb straight there.
    LaunchedEffect(value, damped) {
        if (value != lastEmitted && abs(damped.targetValue - value) > span * 0.0005f) {
            damped.updateValue(value.coerceIn(start, end))
        }
    }

    fun emit(next: Float) {
        val snapped = snapToStep(next, start, end, step)
        lastEmitted = snapped
        if (snapped != currentValue) currentOnChange(snapped)
    }

    val trackBackdrop = rememberLayerBackdrop()
    val thumbBackdrop =
        rememberCombinedBackdrop(
            sourceBackdrop,
            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                val progress = damped.pressProgress
                scale(lerp(2f / 3f, 1f, progress), lerp(0f, 1f, progress)) {
                    drawBackdrop()
                }
            },
        )

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        minimumValueLabel?.invoke()
        Box(
            Modifier
                .weight(1f)
                .height(SliderHitHeight)
                .then(if (enabled) Modifier else Modifier.alpha(0.4f))
                .semantics {
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(value.coerceIn(start, end), start..end, steps.coerceAtLeast(0))
                    if (!enabled) disabled()
                    setProgress { target ->
                        if (!enabled) return@setProgress false
                        val snapped = snapToStep(target, start, end, step)
                        damped.updateValue(snapped)
                        emit(snapped)
                        true
                    }
                }.pointerInput(enabled, start, end, step, isLtr) {
                    if (!enabled) return@pointerInput
                    val insetPx = ThumbInset.toPx()
                    val slop = viewConfiguration.touchSlop

                    // Value under an x in this box's own coordinates (the thumb centre travels between the two insets).
                    fun valueAt(x: Float): Float {
                        val usable = (size.width - 2 * insetPx).coerceAtLeast(1f)
                        val f = ((x - insetPx) / usable).coerceIn(0f, 1f)
                        return start + span * (if (isLtr) f else 1f - f)
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val outcome = awaitHold(down, slop)
                        when (outcome.result) {
                            HoldResult.Scrolled -> {
                                return@awaitEachGesture
                            }

                            HoldResult.Released -> {
                                val target = snapToStep(valueAt(outcome.position.x), start, end, step)
                                currentEditing?.invoke(true)
                                damped.animateToValue(target)
                                emit(target)
                                currentEditing?.invoke(false)
                                return@awaitEachGesture
                            }

                            HoldResult.Held -> {
                                Unit
                            }
                        }

                        currentEditing?.invoke(true)
                        damped.press()
                        down.consume()
                        val usable = (size.width - 2 * insetPx).coerceAtLeast(1f)
                        val sign = if (isLtr) 1f else -1f
                        // Grabbing the thumb keeps its offset from the finger; grabbing the track moves it under the finger.
                        val thumbX =
                            insetPx + usable * ((damped.targetValue - start) / span).let { if (isLtr) it else 1f - it }
                        val grabbed = abs(down.position.x - thumbX) <= SliderThumbWidth.toPx() / 2
                        val origin =
                            if (grabbed) {
                                damped.targetValue
                            } else {
                                snapToStep(
                                    valueAt(down.position.x),
                                    start,
                                    end,
                                    step,
                                )
                            }
                        if (!grabbed) {
                            damped.updateValue(origin)
                            emit(origin)
                        }
                        // A lost touch (a parent took it, the window cancelled it) ends the coroutine: the finally lets go; the value stays.
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                val next =
                                    snapToStep(
                                        origin + span * sign * (change.position.x - down.position.x) / usable,
                                        start,
                                        end,
                                        step,
                                    )
                                if (change.pressed) {
                                    damped.updateValue(next)
                                    emit(next)
                                } else {
                                    emit(next)
                                    change.consume()
                                    return@awaitEachGesture
                                }
                                change.consume()
                            }
                        } finally {
                            damped.release()
                            currentEditing?.invoke(false)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // The track runs the full width; the thumb centre travels between the two insets.
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        trackWidthPx = placeable.width.toFloat()
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }.layerBackdrop(trackBackdrop),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SliderTrackHeight)
                        .clip(RoundedCornerShape(percent = 50))
                        .drawBehind {
                            drawRect(trackColor)
                            val inset = ThumbInset.toPx()
                            val usable = size.width - 2 * inset
                            for (tick in resolvedTicks) {
                                val f = ((tick.value - start) / span).coerceIn(0f, 1f)
                                val x = inset + usable * (if (isLtr) f else 1f - f)
                                drawRoundRect(
                                    color = tickColor,
                                    topLeft = Offset(x - 1.dp.toPx(), size.height / 2 - 5.dp.toPx()),
                                    size = Size(2.dp.toPx(), 10.dp.toPx()),
                                    cornerRadius = CornerRadius(1.dp.toPx()),
                                )
                            }
                        },
                )
                Box(
                    Modifier
                        .height(SliderTrackHeight)
                        .clip(RoundedCornerShape(percent = 50))
                        .drawBehind { drawRect(activeColor) }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val inset = ThumbInset.roundToPx()
                            val usable = (constraints.maxWidth - 2 * inset).coerceAtLeast(0)
                            val reach = (usable * damped.progress).roundToInt() + inset
                            layout(reach.coerceAtLeast(0), placeable.height) { placeable.place(0, 0) }
                        },
                )
            }

            Box(
                Modifier
                    .graphicsLayer {
                        val inset = ThumbInset.toPx()
                        val usable = (trackWidthPx - 2 * inset).coerceAtLeast(0f)
                        // Start-aligned in its box, so in RTL the layout origin is the right edge and the offset flips.
                        translationX = (inset + usable * damped.progress - size.width / 2f) * (if (isLtr) 1f else -1f)
                    }.drawBackdrop(
                        backdrop = thumbBackdrop,
                        shape = { RoundedCornerShape(percent = 50) },
                        effects = {
                            val progress = damped.pressProgress
                            blur(8f.dp.toPx() * (1f - progress))
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
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
                            val velocity = damped.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = damped.pressProgress
                            drawRect(Color.White.copy(alpha = 1f - progress))
                        },
                        debugTag = "slider.thumb",
                    ).size(SliderThumbWidth, SliderThumbHeight),
            )
        }
        maximumValueLabel?.invoke()
    }
}
