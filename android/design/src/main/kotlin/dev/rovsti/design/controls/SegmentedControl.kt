// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang

/*
 * The puck recipe (a CombinedBackdrop of the page and a recorded copy of the tinted row, a DampedDragAnimation position,
 * lens + highlight + inner shadow while held, squash and stretch from the drag velocity) is Kyant0/AndroidLiquidGlass
 * (Apache License 2.0, Copyright 2025 Kyant0), catalog LiquidBottomTabs, as vendored by Convx.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/backdrop/catalog/components/LiquidBottomTabs.kt
 * Changes for Rovsti: a segmented control with text labels instead of a tab bar. The container is a flat capsule (iOS draws
 * the track as a grey fill, not glass), so the pill samples a recorded copy of that track and its labels. The gesture is the
 * tab bar's glisse rule set (150 ms hold before the page scroll, 4 dp drag start, tap within 24 dp of the control, a lost
 * touch changes nothing); each segment is a Role.Tab node; the touch height is 48 dp around a 36 dp track.
 */
package dev.rovsti.design.controls

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.Backdrop
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberCanvasBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberCombinedBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.backdrop.catalog.utils.DampedDragAnimation
import dev.rovsti.design.backdrop.drawBackdrop
import dev.rovsti.design.backdrop.effects.lens
import dev.rovsti.design.backdrop.highlight.Highlight
import dev.rovsti.design.backdrop.shadow.InnerShadow
import dev.rovsti.design.backdrop.shadow.Shadow
import dev.rovsti.design.controls.IosSwitchRules
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.press.HoldResult
import dev.rovsti.design.press.awaitHold
import dev.rovsti.design.shapes.ContinuousCapsule
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

private val TrackHeight = 36.dp
private val HitHeight = 48.dp
private val PuckInset = 2.dp

/** The slot index (0-based, in layout order) under [x] for a control [widthPx] wide with [count] slots. */
internal fun segmentUnder(
    x: Float,
    widthPx: Float,
    insetPx: Float,
    count: Int,
): Int {
    val slot = (widthPx - 2 * insetPx) / count
    return ((x - insetPx) / slot).toInt().coerceIn(0, count - 1)
}

/**
 * iOS's segmented control (Picker with the segmented style). The selected segment is a pill; pressing it lifts the pill
 * into lens glass that bends the track and the labels under it, and it can be dragged to another segment, landing on the
 * nearest one. A tap on a segment selects it. A vertical swipe that starts on the control scrolls the page.
 *
 * [backdrop] is what the held pill refracts besides the track (see [dev.rovsti.design.ios.component.IosToggle]).
 */
@Composable
fun SegmentedControl(
    items: ImmutableList<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val count = items.size
    if (count == 0) return
    val colors = RovstiTheme.colors
    val surface = colors.groupedSurface
    val sourceBackdrop = backdrop ?: rememberCanvasBackdrop(remember(surface) { { drawRect(surface) } })
    val trackColor = colors.fillPrimary
    val pillColor = if (colors.isDark) Color.White.copy(alpha = 0.18f) else Color.White
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalRovstiHaptics.current
    val currentSelected by rememberUpdatedState(selected.coerceIn(0, count - 1))
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentHaptics by rememberUpdatedState(haptics)
    val last = (count - 1).coerceAtLeast(1).toFloat()

    val damped =
        remember(scope, count) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = selected.coerceIn(0, count - 1).toFloat(),
                valueRange = 0f..last,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.2f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
        }
    LaunchedEffect(selected, damped) {
        val target = selected.coerceIn(0, count - 1).toFloat()
        if (damped.targetValue != target) damped.animateToValue(target)
    }

    fun commit(target: Int) {
        damped.animateToValue(target.toFloat())
        if (target != currentSelected) {
            currentHaptics.selection()
            currentOnSelect(target)
        }
    }

    // What the held pill looks through: the track and the labels, recorded once, drawn invisibly.
    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(HitHeight)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .selectableGroup()
            .pointerInput(enabled, count, isLtr) {
                if (!enabled) return@pointerInput
                val insetPx = PuckInset.toPx()
                val nearPx = IosSwitchRules.NEAR_DP.dp.toPx()
                val slop = viewConfiguration.touchSlop
                val pxPerDp = density.density
                val sign = if (isLtr) 1f else -1f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slot = (size.width - 2 * insetPx) / count
                    val anchorLeft = segmentUnder(down.position.x, size.width.toFloat(), insetPx, count)
                    val anchor = if (isLtr) anchorLeft else count - 1 - anchorLeft
                    val outcome = awaitHold(down, slop)
                    when (outcome.result) {
                        HoldResult.Scrolled -> {
                            return@awaitEachGesture
                        }

                        HoldResult.Released -> {
                            if (IosSwitchRules.nearBox(
                                    outcome.position.y,
                                    size.height.toFloat(),
                                    nearPx,
                                )
                            ) {
                                commit(anchor)
                            }
                            return@awaitEachGesture
                        }

                        HoldResult.Held -> {
                            Unit
                        }
                    }

                    damped.press()
                    down.consume()
                    val origin = damped.targetValue
                    var moved = IosSwitchRules.startsDrag((outcome.position.x - down.position.x) / pxPerDp)
                    var value = origin
                    // A lost touch (a parent took it, the window cancelled it) ends the coroutine: the finally restores.
                    var settled = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            val dx = (change.position.x - down.position.x) * sign
                            if (!moved && IosSwitchRules.startsDrag(dx / pxPerDp)) moved = true
                            if (moved) value = (origin + dx / slot).coerceIn(0f, last)
                            if (!change.pressed) {
                                change.consume()
                                settled = true
                                when {
                                    moved -> {
                                        commit(value.roundToInt().coerceIn(0, count - 1))
                                    }

                                    IosSwitchRules.nearBox(change.position.y, size.height.toFloat(), nearPx) -> {
                                        commit(anchor)
                                    }

                                    else -> {
                                        damped.animateToValue(currentSelected.toFloat())
                                    }
                                }
                                return@awaitEachGesture
                            }
                            if (moved) damped.updateValue(value)
                            change.consume()
                        }
                    } finally {
                        if (!settled) damped.animateToValue(currentSelected.toFloat())
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val totalPx = with(density) { maxWidth.toPx() }
        val insetPx = with(density) { PuckInset.toPx() }
        val slotPx = (totalPx - 2 * insetPx) / count
        val slotDp = with(density) { slotPx.toDp() }

        @Composable
        fun Labels(
            semanticsOn: Boolean,
            modifier: Modifier = Modifier,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(PuckInset)
                    .height(TrackHeight - PuckInset * 2),
            ) {
                // The resting pill sits UNDER the labels so the selected one stays readable; it fades as the glass pill
                // takes over (the recorded copy has it too, so the lens bends it).
                Box(
                    Modifier
                        .width(slotDp)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val x = damped.value * slotPx
                            translationX = if (isLtr) x else -x
                            alpha = 1f - damped.pressProgress
                        }.clip(ContinuousCapsule)
                        .drawBehind { drawRect(pillColor) },
                )
            }
            Row(modifier.fillMaxWidth().padding(horizontal = PuckInset).height(TrackHeight)) {
                items.forEachIndexed { index, text ->
                    val isSelected = index == selected
                    Box(
                        Modifier
                            .width(slotDp)
                            .fillMaxHeight()
                            .then(
                                if (semanticsOn) {
                                    Modifier.semantics(mergeDescendants = true) {
                                        role = Role.Tab
                                        this.selected = isSelected
                                        if (!enabled) disabled()
                                        onClick("select") {
                                            if (!enabled) return@onClick false
                                            commit(index)
                                            true
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        RovstiText(
                            text,
                            style = RovstiTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isSelected) colors.labelPrimary else colors.labelSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        // The recorded copy sits under the visible one and is not drawn twice for the eye: the visible track is the
        // same pixels, and the copy is only ever read by the pill.
        Box(Modifier.fillMaxWidth().height(TrackHeight)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .clip(ContinuousCapsule)
                    .drawBehind { drawRect(trackColor) },
            )
            Labels(semanticsOn = true)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(TrackHeight)
                .alpha(0f)
                .clearAndSetSemantics {}
                .layerBackdrop(trackBackdrop),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .clip(ContinuousCapsule)
                    .drawBehind { drawRect(trackColor) },
            )
            Labels(semanticsOn = false)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(TrackHeight)
                .padding(horizontal = PuckInset, vertical = PuckInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        val x = damped.value * slotPx
                        translationX = if (isLtr) x else -x
                    }.drawBackdrop(
                        backdrop = rememberCombinedBackdrop(sourceBackdrop, trackBackdrop),
                        shape = { ContinuousCapsule },
                        effects = {
                            val progress = damped.pressProgress
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true,
                            )
                        },
                        highlight = {
                            val progress = damped.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        shadow = {
                            val progress = damped.pressProgress
                            Shadow(alpha = progress)
                        },
                        innerShadow = {
                            val progress = damped.pressProgress
                            InnerShadow(radius = 8f.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            scaleX = damped.scaleX
                            scaleY = damped.scaleY
                            val velocity = damped.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                        },
                        debugTag = "segmented.puck",
                    ).width(slotDp)
                    .fillMaxHeight(),
            )
        }
    }
}
