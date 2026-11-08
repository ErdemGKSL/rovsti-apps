// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.press

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * How long a touch rests on a control before it belongs to the control rather than the page, like the delay before
 * `UIScrollView` hands a touch to its content (glisse `src/internal/hold.ts`). A quick swipe that starts on a control
 * still scrolls the page; a press held this long, or a drag already under way, keeps the touch.
 */
const val HOLD_MS = 150L

/** True once the touch that went down at [downAtMs] belongs to the control at [nowMs]. */
fun held(
    downAtMs: Long,
    nowMs: Long,
    moved: Boolean,
): Boolean = moved || nowMs - downAtMs >= HOLD_MS

/** How the first [HOLD_MS] of a touch ended. */
enum class HoldResult {
    /** The control owns the touch: it rested [HOLD_MS], or a horizontal drag is under way. */
    Held,

    /** The touch turned into a vertical scroll (or a parent consumed it) before it was held. */
    Scrolled,

    /** The finger lifted before it was held: a plain tap. */
    Released,
}

/**
 * Waits for the outcome of the first [HOLD_MS] of the touch that started with [down]. Watches on the Final pass and
 * never consumes, so a scrollable parent still gets the vertical movement. Detectors (tab bar, switch, slider) call it
 * after `awaitFirstDown` and only start consuming on [HoldResult.Held].
 */
suspend fun AwaitPointerEventScope.awaitHoldOrScroll(
    down: PointerInputChange,
    touchSlop: Float,
): HoldResult = awaitHold(down, touchSlop).result

/** How the first [HOLD_MS] ended and where the finger was when it did (the down position if it simply rested). */
class HoldOutcome(
    val result: HoldResult,
    val position: androidx.compose.ui.geometry.Offset,
)

/** Like [awaitHoldOrScroll], but also reports the last position seen, which a tap needs for its release test. */
suspend fun AwaitPointerEventScope.awaitHold(
    down: PointerInputChange,
    touchSlop: Float,
): HoldOutcome {
    var last = down.position
    val early =
        withTimeoutOrNull(HOLD_MS) {
            var result: HoldResult? = null
            while (result == null) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change != null) last = change.position
                result =
                    when {
                        change == null || !change.pressed -> {
                            HoldResult.Released
                        }

                        change.isConsumed -> {
                            HoldResult.Scrolled
                        }

                        else -> {
                            val delta = change.position - down.position
                            val vertical = abs(delta.y) > touchSlop && abs(delta.y) >= abs(delta.x)
                            val horizontal = abs(delta.x) > touchSlop && abs(delta.x) > abs(delta.y)
                            when {
                                vertical -> HoldResult.Scrolled
                                held(down.uptimeMillis, change.uptimeMillis, moved = horizontal) -> HoldResult.Held
                                else -> null
                            }
                        }
                    }
            }
            result
        }
    return HoldOutcome(early ?: HoldResult.Held, last)
}
