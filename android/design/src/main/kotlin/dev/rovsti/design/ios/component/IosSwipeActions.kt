/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosSwipeActions.kt
 * Changes for Rovsti: package and imports; the rest of the row is Convx's (velocity now tracks summed drag, action height). Added: custom accessibility actions (one per
 * swipe action, so TalkBack users reach them without a swipe), one haptic when a full swipe fires, test tags
 * `swipe-row`, `swipe-action-<n>`, and `close()` on a semantics action.
 */

package dev.rovsti.design.ios.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.LocalIosContentColor
import dev.rovsti.design.ios.foundation.nearestProjectedSnap
import dev.rovsti.design.ios.foundation.projectedEndpoint
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One button revealed by swiping an [IosSwipeableRow]. */
data class IosSwipeAction(
    val label: String,
    val tint: Color,
    val icon: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

/** How wide each revealed action button is. */
private val ActionWidth = 76.dp

/**
 * SwiftUI's `swipeActions`: the row slides aside to reveal coloured buttons underneath.
 *
 * Two behaviours make this read as iOS rather than as a generic swipe-to-dismiss:
 * the row settles at exactly the width of the revealed buttons (it does not fly off),
 * and a swipe past [fullSwipeFraction] of the row commits the *first* trailing action
 * outright — which is how Mail deletes a message without you lifting a finger.
 *
 * @param onFullSwipe runs when the row is swiped all the way. Defaults to the first
 *   trailing action, and passing null disables the full-swipe shortcut.
 */
@Composable
fun IosSwipeableRow(
    modifier: Modifier = Modifier,
    leadingActions: List<IosSwipeAction> = emptyList(),
    trailingActions: List<IosSwipeAction> = emptyList(),
    fullSwipeFraction: Float = 0.55f,
    onFullSwipe: (() -> Unit)? = trailingActions.firstOrNull()?.onClick,
    content: @Composable () -> Unit,
) {
    if (leadingActions.isEmpty() && trailingActions.isEmpty()) {
        Box(modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val haptics = dev.rovsti.design.haptics.LocalRovstiHaptics.current

    val trailingWidthPx = with(density) { (ActionWidth * trailingActions.size).toPx() }
    val leadingWidthPx = with(density) { (ActionWidth * leadingActions.size).toPx() }
    val rowWidth = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    fun settle(
        target: Float,
        velocity: Float = 0f,
    ) {
        scope.launch {
            offset.animateTo(
                target,
                spring(dampingRatio = 1f, stiffness = 500f),
                initialVelocity = velocity,
            )
        }
    }

    fun close(velocity: Float = 0f) = settle(0f, velocity)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                // Rovsti: the action buttons fill the row's height (Convx's Box wrapped them to their label height).
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .clipToBounds()
                .onSizeChanged { rowWidth.floatValue = it.width.toFloat() }
                .testTag("swipe-row")
                .semantics {
                    customActions =
                        (leadingActions + trailingActions).map { a ->
                            androidx.compose.ui.semantics.CustomAccessibilityAction(a.label) {
                                a.onClick()
                                true
                            }
                        }
                },
    ) {
        // The buttons live behind the row, pinned to the edge they belong to, so the
        // row sliding away is what uncovers them.
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.Start,
        ) {
            leadingActions.forEach { ActionButton(it, ::close) }
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.End,
        ) {
            trailingActions.forEach { ActionButton(it, ::close) }
        }

        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .background(IosTheme.colors.secondarySystemGroupedBackground)
                .pointerInput(trailingActions.size, leadingActions.size) {
                    val tracker = VelocityTracker()
                    // Rovsti fix: the layer translation moves this node's own coordinates with the finger, so
                    // change.position stays put and the tracker saw no velocity; track the summed drag instead.
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            travelled = 0f
                        },
                        onDragEnd = {
                            val x = offset.value
                            val v = tracker.calculateVelocity().x
                            val full = rowWidth.floatValue * fullSwipeFraction
                            // Flick throws: decide from projected endpoint, hand off velocity.
                            val projected = projectedEndpoint(x, v)
                            when {
                                onFullSwipe != null && -projected > full -> {
                                    haptics.confirm()
                                    onFullSwipe()
                                    close(v)
                                }

                                else -> {
                                    val snaps =
                                        buildList {
                                            if (trailingActions.isNotEmpty()) add(-trailingWidthPx)
                                            add(0f)
                                            if (leadingActions.isNotEmpty()) add(leadingWidthPx)
                                        }
                                    settle(nearestProjectedSnap(x, v, snaps) ?: 0f, v)
                                }
                            }
                        },
                        onDragCancel = { close() },
                    ) { change, drag ->
                        change.consume()
                        travelled += drag
                        tracker.addPosition(change.uptimeMillis, Offset(travelled, 0f))
                        val next =
                            (offset.value + drag)
                                .coerceIn(-rowWidth.floatValue, leadingWidthPx)
                        scope.launch { offset.snapTo(next) }
                    }
                },
        ) {
            content()
        }
    }

    // A row that has been swiped open closes itself when its actions disappear.
    LaunchedEffect(trailingActions.size, leadingActions.size) {
        if (abs(offset.value) > 0f) offset.animateTo(0f)
    }
}

@Composable
private fun ActionButton(
    action: IosSwipeAction,
    onDone: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .width(ActionWidth)
                .fillMaxHeight()
                .testTag("swipe-action-" + action.label)
                .background(action.tint)
                .clickable {
                    action.onClick()
                    onDone()
                }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalIosContentColor provides Color.White,
        ) {
            if (action.icon != null) {
                action.icon.invoke()
            } else {
                IosText(
                    text = action.label,
                    style = IosTheme.typography.footnote,
                    color = Color.White,
                )
            }
        }
    }
}
