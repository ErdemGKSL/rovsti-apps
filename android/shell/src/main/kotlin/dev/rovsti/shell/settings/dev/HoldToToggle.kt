package dev.rovsti.shell.settings.dev

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** How long the Version row must be held to switch developer mode. */
const val DEV_HOLD_MS = 10_000L

/** True once a press that went down at [downMs] has been held [holdMs] at [nowMs]. */
fun holdReached(
    downMs: Long,
    nowMs: Long,
    holdMs: Long = DEV_HOLD_MS,
): Boolean = nowMs - downMs >= holdMs

/**
 * Runs [onToggle] when one finger rests on this element for [holdMs] without moving past the touch slop, leaving its
 * bounds, lifting or being taken by a scroll. Nothing is shown before then (the feature is hidden); the caller plays
 * the haptic and the toast. Watches on the Final pass and never consumes, so the page still scrolls and the row keeps
 * its own click.
 */
@Composable
fun Modifier.holdToToggle(
    holdMs: Long = DEV_HOLD_MS,
    onToggle: () -> Unit,
): Modifier {
    val toggle by rememberUpdatedState(onToggle)
    return pointerInput(holdMs) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val slop = viewConfiguration.touchSlop
            // true: the press ended early; false: an event already came after the hold time; null: timed out held.
            val cancelled =
                withTimeoutOrNull(holdMs) {
                    var early = true
                    while (true) {
                        val change =
                            awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                                ?: break
                        val p = change.position
                        val outside = p.x < 0 || p.y < 0 || p.x > size.width || p.y > size.height
                        if (!change.pressed || change.isConsumed || outside) break
                        if ((p - down.position).getDistance() > slop) break
                        if (holdReached(down.uptimeMillis, change.uptimeMillis, holdMs)) {
                            early = false
                            break
                        }
                    }
                    early
                }
            if (cancelled != true) toggle()
        }
    }
}
