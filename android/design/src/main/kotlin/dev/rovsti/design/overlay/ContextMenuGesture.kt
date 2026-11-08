package dev.rovsti.design.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import dev.rovsti.design.motion.LocalReduceMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The press feedback of a row waiting for a long press (ORIGINAL): a value that climbs to 1 over 300 ms while the
 * finger is down and springs back to 0 (damping 0.7, stiffness 600) when it is lifted or taken away. Screens read
 * [value] in the draw phase only (`graphicsLayer {}`, `drawWithContent {}`), so the climb never recomposes anything.
 */
@Stable
class PressRamp internal constructor(
    private val scope: CoroutineScope,
) {
    private val progress = Animatable(0f)

    /** 0 at rest, 1 after 300 ms of holding. The release spring may undershoot 0 a little; this never does. */
    fun value(): Float = progress.value.coerceAtLeast(0f)

    fun start() {
        scope.launch { progress.animateTo(1f, tween(RampMs, easing = LinearOutSlowInEasing)) }
    }

    fun release() {
        scope.launch { progress.animateTo(0f, spring(ReleaseDamping, ReleaseStiffness, 0.001f)) }
    }

    private companion object {
        const val RampMs = 300
        const val ReleaseDamping = 0.7f
        const val ReleaseStiffness = 600f
    }
}

@Composable
fun rememberPressRamp(): PressRamp {
    val scope = rememberCoroutineScope()
    return remember(scope) { PressRamp(scope) }
}

/** How a touch on a row ends. Decided once per touch. */
internal enum class ContextOutcome { Tap, Cancel, ChildTook, Long }

private val ZeroCover: () -> Float = { 0f }

/** Row scale and black veil at full press (SKILL overlays 3.1). */
internal const val PressShrink = 0.04f
internal const val PressVeil = 0.14f

/**
 * Turns a touch on this element into one of four outcomes (ORIGINAL; SKILL overlays 3.1):
 *
 * - **Tap**: lifted before the long-press timeout without moving: [onTap].
 * - **Cancel**: it moved past touch slop, or something above took the gesture (a list scrolling): nothing happens.
 * - **ChildTook**: a child already used the touch (a "..." button inside the row clicks): nothing happens here.
 * - **Long**: still down at the timeout: [onOpen] is called with the element's window rect, and the same finger goes
 *   on to drag the preview, [onPreviewDrag] (offset from where it went down) until it lifts, then [onPreviewRelease].
 *
 * The decision is taken on the Main pass, after the children saw the event, so a child's click wins. Once open, the
 * events are consumed on the Initial pass, so the lift does not click a child. Meanwhile [press] drives a small
 * shrink and veil. The element also exposes `onLongClick` and, when [onTap] is set, `onClick` to accessibility
 * services, and every [MenuItem.Action] of [actions] as a custom action (screen readers cannot long-press).
 *
 * [cover] (0..1) hides the element while its lifted copy stands in for it: 1 is fully hidden. Nothing about layout changes: the shrink and the veil are draw-phase only, and [anchor] records the element's
 * unscaled window bounds.
 */
@Composable
fun Modifier.contextMenu(
    anchor: AnchorHolder,
    press: PressRamp,
    onTap: (() -> Unit)?,
    onOpen: (Rect) -> Unit,
    onPreviewDrag: (Offset) -> Unit,
    onPreviewRelease: () -> Unit,
    actions: ImmutableList<MenuItem> = persistentListOf(),
    label: String = "Show actions",
    cover: () -> Float = ZeroCover,
): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val latestTap by rememberUpdatedState(onTap)
    val latestOpen by rememberUpdatedState(onOpen)
    val latestDrag by rememberUpdatedState(onPreviewDrag)
    val latestRelease by rememberUpdatedState(onPreviewRelease)
    val accessibilityActions =
        remember(actions) {
            actions.filterIsInstance<MenuItem.Action>().filter { it.enabled }.map { item ->
                CustomAccessibilityAction(item.label) {
                    item.onClick()
                    true
                }
            }
        }
    return this
        .captureAnchor(anchor)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A child that took the touch on the way down (a button) owns it entirely.
                if (down.isConsumed) return@awaitEachGesture
                press.start()
                val slop = viewConfiguration.touchSlop
                val outcome =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result: ContextOutcome? = null
                        while (result == null) {
                            val change =
                                awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                            result =
                                when {
                                    change == null -> {
                                        ContextOutcome.Cancel
                                    }

                                    change.changedToUp() -> {
                                        if (change.isConsumed) ContextOutcome.ChildTook else ContextOutcome.Tap
                                    }

                                    change.isConsumed || (change.position - down.position).getDistance() > slop -> {
                                        ContextOutcome.Cancel
                                    }

                                    else -> {
                                        null
                                    }
                                }
                        }
                        result
                    } ?: ContextOutcome.Long
                when (outcome) {
                    ContextOutcome.Tap -> {
                        press.release()
                        latestTap?.invoke()
                    }

                    ContextOutcome.Cancel, ContextOutcome.ChildTook -> {
                        press.release()
                    }

                    ContextOutcome.Long -> {
                        latestOpen(anchor.rect)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                event.changes.forEach { it.consume() }
                                if (!change.pressed) break
                                latestDrag(change.position - down.position)
                            }
                        } finally {
                            press.release()
                            latestRelease()
                        }
                    }
                }
            }
        }.semantics(mergeDescendants = true) {
            // Merged so a screen reader reads the row's own text for the tap and the long press.
            onLongClick(label = label) {
                latestOpen(anchor.rect)
                true
            }
            if (latestTap != null) {
                onClick {
                    latestTap?.invoke()
                    true
                }
            }
            if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
        }.graphicsLayer {
            val p = press.value()
            if (!reduceMotion) {
                scaleX = 1f - PressShrink * p
                scaleY = scaleX
            }
            alpha = 1f - cover().coerceIn(0f, 1f)
        }.drawWithContent {
            drawContent()
            val p = press.value()
            if (p > 0f) drawRect(Color.Black.copy(alpha = PressVeil * p * (1f - cover().coerceIn(0f, 1f))))
        }
}
