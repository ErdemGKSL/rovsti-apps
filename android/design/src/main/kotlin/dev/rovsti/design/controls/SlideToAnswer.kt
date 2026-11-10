// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code; the glass track is the CallControl material of the call screens.
package dev.rovsti.design.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassPreset
import dev.rovsti.design.glass.rovstiGlass
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.motion.Fling
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.shapes.ContinuousRoundedRectangle
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The test tags of [SlideToAnswer]. */
object SlideToAnswerTags {
    const val Track = "slide-answer-track"
    const val Handle = "slide-answer-handle"
}

/** The numbers of [SlideToAnswer]. */
object SlideToAnswerDefaults {
    val TrackHeight: Dp = 76.dp
    val HandleSize: Dp = 68.dp

    /** The side margin of the track on the screen; the screen applies it, the control fills what it is given. */
    val Gutter: Dp = 16.dp

    /** Release past this share of the travel (or a fling that would end past it) answers. */
    const val AnswerAt = 0.8f

    /** The haptic tick is armed again once the handle is back below this share. */
    const val RearmBelow = 0.7f

    const val SettleDamping = 0.75f
    const val SettleStiffness = 500f
    internal val GlyphSize: Dp = 28.dp
}

/**
 * "Slide to answer" for the locked screen: a frosted capsule ([GlassPreset.CallControl]) with a round handle that
 * follows the finger one to one. Release past [SlideToAnswerDefaults.AnswerAt] of the travel, or a fling whose
 * momentum would end there ([Fling.restingPoint]), answers once and parks the handle at the end; anything less springs
 * back (0.75 / 500). One [HapticEvent.ThresholdTick] marks the crossing and re-arms only below 70 %. The label fades
 * out with progress. Right-to-left mirrors the whole control. For TalkBack the track is a button with an "answer"
 * custom action, so no drag is needed.
 */
@Composable
fun SlideToAnswer(
    label: String,
    onAnswer: () -> Unit,
    modifier: Modifier = Modifier,
    handleColor: Color = Color(0xFF34C759),
    enabled: Boolean = true,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dir = if (rtl) -1f else 1f
    val reduce = LocalReduceMotion.current
    val haptics = LocalRovstiHaptics.current
    val scope = rememberCoroutineScope()
    val latestAnswer by rememberUpdatedState(onAnswer)
    val density = LocalDensity.current
    val inset = with(density) { ((SlideToAnswerDefaults.TrackHeight - SlideToAnswerDefaults.HandleSize) / 2).toPx() }
    val handlePx = with(density) { SlideToAnswerDefaults.HandleSize.toPx() }
    var trackWidth by remember { mutableStateOf(0) }
    val travel = (trackWidth - handlePx - 2 * inset).coerceAtLeast(1f)
    val offset = remember { Animatable(0f) }
    var answered by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(true) }

    fun progress(px: Float) = (px / travel).coerceIn(0f, 1f)

    fun answer() {
        if (answered || !enabled) return
        answered = true
        scope.launch {
            if (reduce) {
                offset.snapTo(travel)
            } else {
                offset.animateTo(
                    travel,
                    spring(SlideToAnswerDefaults.SettleDamping, SlideToAnswerDefaults.SettleStiffness),
                )
            }
        }
        latestAnswer()
    }

    val drag =
        rememberDraggableState { delta ->
            if (answered) return@rememberDraggableState
            val next = (offset.value + delta * dir).coerceIn(0f, travel)
            // Snap without suspending in practice: the handle must track the finger within the same frame.
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
            val p = progress(next)
            if (armed && p >= SlideToAnswerDefaults.AnswerAt) {
                armed = false
                haptics.play(HapticEvent.ThresholdTick)
            } else if (!armed && p < SlideToAnswerDefaults.RearmBelow) {
                armed = true
            }
        }

    val shape = ContinuousRoundedRectangle(percent = 50)
    Box(
        modifier
            .fillMaxWidth()
            .height(SlideToAnswerDefaults.TrackHeight)
            .onSizeChanged { trackWidth = it.width }
            .testTag(SlideToAnswerTags.Track)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                if (enabled) {
                    onClick(label = label) {
                        answer()
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(label) {
                                answer()
                                true
                            },
                        )
                }
            }.rovstiGlass(GlassPreset.CallControl, shape, "call.slide", outerShadow = false)
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                enabled = enabled && !answered,
                onDragStopped = { velocity ->
                    val v = velocity * dir
                    val at = offset.value
                    val rest = if (v > 0f) Fling.restingPoint(at, v) else at
                    if (progress(at) >= SlideToAnswerDefaults.AnswerAt ||
                        progress(rest) >= SlideToAnswerDefaults.AnswerAt
                    ) {
                        answer()
                    } else {
                        armed = true
                        if (reduce) {
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(
                                0f,
                                spring(SlideToAnswerDefaults.SettleDamping, SlideToAnswerDefaults.SettleStiffness),
                            )
                        }
                    }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The label sits in the free space past the handle and fades as the handle leaves the start.
        RovstiText(
            label,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(start = SlideToAnswerDefaults.HandleSize / 2)
                    .graphicsLayer { alpha = (1f - progress(offset.value) * 2f).coerceIn(0f, 1f) },
            style = RovstiTheme.type.body,
            color = Color.White,
            maxLines = 1,
        )
        Box(
            Modifier
                .padding(start = with(density) { inset.toDp() })
                .offset { IntOffset(offset.value.roundToInt(), 0) } // rtl-aware: mirrors by itself
                .size(SlideToAnswerDefaults.HandleSize)
                .testTag(SlideToAnswerTags.Handle)
                .clip(CircleShape)
                .background(handleColor),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(
                RovstiIcons.PhoneFill,
                null,
                tint = Color.White,
                size = SlideToAnswerDefaults.GlyphSize,
            )
        }
    }
}
