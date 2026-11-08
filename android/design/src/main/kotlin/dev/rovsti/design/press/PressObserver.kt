package dev.rovsti.design.press

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange

/**
 * Follows one pointer from down to up for a press effect (ORIGINAL).
 *
 * Observes on the Final pass and never consumes, so a `clickable` on the same element still fires. Gives up when a
 * scrolling parent consumed movement. [onEnd] runs in `finally`, so it also runs when the pointer-input coroutine is
 * cancelled (node detached, composable left the tree) and the effect can never stay stuck pressed.
 */
internal suspend fun AwaitPointerEventScope.observePress(
    enabled: () -> Boolean,
    onDown: (PointerInputChange) -> Unit,
    onMove: (down: PointerInputChange, offset: Offset) -> Unit,
    onEnd: () -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
    if (!enabled()) return
    try {
        onDown(down)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.isConsumed && change.positionChange() != Offset.Zero) break
            onMove(down, change.position - down.position)
        }
    } finally {
        onEnd()
    }
}
