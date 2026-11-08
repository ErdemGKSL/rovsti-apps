package dev.rovsti.design.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.ChromeMetrics
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.ios.foundation.IosGlassSpec
import dev.rovsti.design.ios.foundation.IosGlassStyle
import dev.rovsti.design.ios.foundation.iosGlass
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Dimensions and timings of the toast (SKILL overlays 6). */
object ToastMetrics {
    const val ShowMs = 3000L
    const val UndoMs = 5000L
    const val InMs = 350
    const val OutMs = 180

    /** Opacity of the page-colour wash over the toast's glass. */
    const val GlassWash = 0.55f
    const val MaxLines = 2

    /** Space between the toast and the bar (or the navigation inset) under it. */
    val Gap = 8.dp
    val PaddingX = 18.dp
    val PaddingY = 12.dp
    val Radius = 24.dp
    val MaxWidth = 420.dp
    val EnterRise = 24.dp
    val ActionPadX = 8.dp
    val RingSize = 20.dp
    val RingStroke = 2.dp
    val DownThreshold = 32.dp
    val SideThreshold = 80.dp
    val Shadow = 8.dp
}

/** What an undo pill offers: the action label, how long it stays and what runs (once). */
internal class ToastAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A toast (ORIGINAL; SKILL overlays 6): a dark capsule that says one short thing and leaves by itself. It is not modal,
 * so the page keeps taking touches and Back; it sits above the measured tab bar ([ChromeMetrics] as registered with the
 * controller) or 8 dp above the navigation inset, rises in 350 ms and leaves in 180 ms. The timer runs on the frame
 * clock (so system animation scales cannot cut it short) and holds while a finger is on the toast. A swipe down or to
 * either side throws it away using the fling projection; with an [action] it is the undo pill, which draws a countdown
 * ring in the draw phase and runs the action once, never after it has gone.
 */
class ToastEntry internal constructor(
    private val message: String,
    private val durationMs: Long,
    private val action: ToastAction?,
) : OverlayEntry(modal = false, title = message, dismissOnBack = false) {
    override val passesBack: Boolean get() = true

    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val colors = RovstiTheme.colors
        val density = LocalDensity.current
        val transition = rememberTransition(state, label = "toast")
        val progress =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(100)
                        targetState -> tween(ToastMetrics.InMs, easing = RovstiMotion.PushEasing)
                        else -> tween(ToastMetrics.OutMs, easing = EaseIn)
                    }
                },
                label = "toast-p",
            ) { if (it) 1f else 0f }
        val haptics by rememberUpdatedState(LocalRovstiHaptics.current)
        val scope = rememberCoroutineScope()
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val elapsed = remember { mutableFloatStateOf(0f) }
        var holding by remember { mutableStateOf(false) }
        var fired by remember { mutableStateOf(false) }
        val entry = this

        // Frame-clock timer: independent of animator scale, held while a finger is down.
        LaunchedEffect(Unit) {
            val total = durationMs * NanosPerMs
            var last = -1L
            var spent = 0L
            while (spent < total && state.targetState) {
                val now = withFrameNanos { it }
                if (last >= 0 && !holding) spent += now - last
                last = now
                elapsed.floatValue = spent.toFloat() / total
            }
            if (state.targetState) host.controller.dismiss(entry)
        }

        val downPx = with(density) { ToastMetrics.DownThreshold.toPx() }
        val sidePx = with(density) { ToastMetrics.SideThreshold.toPx() }

        fun release(velocity: Offset) {
            val outcome = ToastMath.outcome(offset.value, velocity, downPx, sidePx)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (outcome == ToastSwipe.Stay) {
                    offset.animateTo(
                        Offset.Zero,
                        RovstiMotion.swipeSettle(Offset.VisibilityThreshold),
                        initialVelocity = velocity,
                    )
                } else {
                    host.controller.dismiss(entry)
                    val target =
                        when (outcome) {
                            ToastSwipe.Down -> Offset(offset.value.x, host.windowSize.height)
                            ToastSwipe.Left -> Offset(-host.windowSize.width, offset.value.y)
                            else -> Offset(host.windowSize.width, offset.value.y)
                        }
                    offset.animateTo(
                        target,
                        RovstiMotion.swipeSettle(Offset.VisibilityThreshold),
                        initialVelocity = velocity,
                    )
                }
            }
        }

        // Convx glass (iosGlass, the chrome spec) with a page-colour wash so the label reads over any backdrop.
        val wash = colors.page.copy(alpha = ToastMetrics.GlassWash)
        val textColor = colors.labelPrimary
        val actionColor = colors.accent
        val shape = remember { RoundedCornerShape(ToastMetrics.Radius) }

        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val side = Spacing.gutter.roundToPx()
                    val width = min(ToastMetrics.MaxWidth.roundToPx(), (host.safe.width - 2 * side).roundToInt())
                    val p = measurable.measure(Constraints(0, width.coerceAtLeast(0), 0, Constraints.Infinity))
                    // Read here, in the layout phase: a bar that resizes moves the toast without recomposing it.
                    val gap =
                        ToastMath
                            .bottomGapPx(
                                systemBottomPx = host.windowSize.height - host.safe.bottom,
                                barPx = host.controller.chromeMetrics?.bottomBarPx ?: 0,
                                barMarginPx = ChromeMetrics.BarMargin.toPx(),
                                gapPx = ToastMetrics.Gap.toPx(),
                            ).roundToInt()
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val x = ((constraints.maxWidth - p.width) / 2f).roundToInt()
                        val y = constraints.maxHeight - gap - p.height
                        val rise = ToastMetrics.EnterRise.toPx()
                        p.placeWithLayer(x, y) {
                            val v = progress.value.coerceIn(0f, 1f)
                            alpha = v
                            translationX = offset.value.x
                            translationY = offset.value.y + if (reduceMotion) 0f else rise * (1f - v)
                        }
                    }
                },
        ) {
            Row(
                Modifier
                    .iosGlass(shape, IosGlassStyle.Regular, IosGlassSpec.Chrome, wash = wash, debugTag = "toast")
                    .testTag("toast")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            holding = true
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var dragging = false
                            var total = Offset.Zero
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.position - change.previousPosition
                                tracker.addPosition(change.uptimeMillis, change.position)
                                total += delta
                                if (!dragging && total.getDistance() > touchSlop) {
                                    dragging = true
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        offset.snapTo(Offset(total.x, ToastMath.rubberY(total.y)))
                                    }
                                } else if (dragging) {
                                    change.consume()
                                    val next = Offset(offset.value.x + delta.x, offset.value.y + delta.y)
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
                                }
                            }
                            holding = false
                            if (dragging) {
                                val v = tracker.calculateVelocity()
                                release(Offset(v.x, v.y))
                            }
                        }
                    }.semantics {
                        // Not merged: the Undo button stays a node of its own for a screen reader.
                        liveRegion = LiveRegionMode.Polite
                        dismiss {
                            host.controller.dismiss(entry)
                            true
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RovstiText(
                    message,
                    modifier =
                        Modifier
                            .padding(
                                start = ToastMetrics.PaddingX,
                                end = if (action == null) ToastMetrics.PaddingX else 0.dp,
                                top = ToastMetrics.PaddingY,
                                bottom = ToastMetrics.PaddingY,
                            ).testTag("toast-text"),
                    style = RovstiTheme.type.subheadline,
                    color = textColor,
                    maxLines = ToastMetrics.MaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (action != null) {
                    val source = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .heightIn(min = Sizes.touch)
                            .clickable(
                                interactionSource = source,
                                indication = null,
                                role = Role.Button,
                            ) {
                                // Once, and never after the toast has started to leave (timeout, swipe, replacement).
                                if (!fired && state.targetState) {
                                    fired = true
                                    haptics.confirm()
                                    host.controller.dismiss(entry)
                                    action.onClick()
                                }
                            }.padding(start = Spacing.m, end = ToastMetrics.PaddingX - ToastMetrics.ActionPadX)
                            .testTag("toast-action"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RovstiText(
                            action.label,
                            style = RovstiTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                            color = actionColor,
                        )
                        Box(
                            Modifier
                                .padding(start = Spacing.s)
                                .size(ToastMetrics.RingSize)
                                .testTag("toast-ring")
                                .drawBehind {
                                    val stroke = ToastMetrics.RingStroke.toPx()
                                    val inset = stroke / 2f
                                    val arc = Size(size.width - stroke, size.height - stroke)
                                    drawArc(
                                        color = textColor.copy(alpha = RingTrackAlpha),
                                        startAngle = 0f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        topLeft = Offset(inset, inset),
                                        size = arc,
                                        style = Stroke(stroke),
                                    )
                                    drawArc(
                                        color = actionColor,
                                        startAngle = -90f,
                                        sweepAngle = ToastMath.ringSweep(elapsed.floatValue),
                                        useCenter = false,
                                        topLeft = Offset(inset, inset),
                                        size = arc,
                                        style = Stroke(stroke, cap = StrokeCap.Round),
                                    )
                                },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val NanosPerMs = 1_000_000L
        const val RingTrackAlpha = 0.25f
    }
}

/**
 * Shows [message] as a toast for [durationMs]. One toast at a time: a toast on show (or leaving) is dismissed and this
 * one takes its place.
 */
fun OverlayController.showToast(
    message: String,
    durationMs: Long = ToastMetrics.ShowMs,
): ToastEntry = presentToast(ToastEntry(message, durationMs, null))

/**
 * The undo pill: [message] with an [actionLabel] and a five-second countdown ring. [onUndo] runs once, only while the
 * pill is still on show.
 */
fun OverlayController.showUndo(
    message: String,
    onUndo: () -> Unit,
    actionLabel: String = "Undo",
    durationMs: Long = ToastMetrics.UndoMs,
): ToastEntry = presentToast(ToastEntry(message, durationMs, ToastAction(actionLabel, onUndo)))

private fun OverlayController.presentToast(entry: ToastEntry): ToastEntry {
    toast?.let { dismiss(it) }
    toast = entry
    return present(entry) as ToastEntry
}
