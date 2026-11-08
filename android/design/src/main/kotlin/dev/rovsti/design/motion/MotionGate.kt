package dev.rovsti.design.motion

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One boolean for "the screen is moving": scrolling, a running transition or an active gesture.
 * Expensive ambient work (glass capture, shimmer, glows) reads [frozen] and pauses while it is true.
 *
 * [frozen] is written on edges only: once when motion starts, once [tailMs] after the last source stops.
 * Reading it in a draw lambda therefore invalidates twice per gesture, not once per frame.
 * Call from the main thread.
 */
@Stable
class MotionGate(
    private val scope: CoroutineScope,
    private val tailMs: Long = DefaultTailMs,
) {
    private var scrolling = false
    private var transitioning = false
    private var gesturing = false
    private var tailJob: Job? = null

    /** True while anything moves, and for [tailMs] after. */
    var frozen by mutableStateOf(false)
        private set

    /** Number of real writes to [frozen]; tests assert it counts edges, not frames. */
    internal var frozenWrites = 0
        private set

    /** Attach with `Modifier.nestedScroll`. Takes no scroll, only observes it. */
    val scrollConnection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                update(scroll = true)
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                update(scroll = false)
                return Velocity.Zero
            }
        }

    fun transition(running: Boolean) = update(transition = running)

    fun gesture(active: Boolean) = update(gesture = active)

    /** Safety net for a scroll that never reports its fling end: follows the list's own scroll state. */
    suspend fun watch(state: LazyListState) {
        snapshotFlow { state.isScrollInProgress }.collect { update(scroll = it) }
    }

    private fun update(
        scroll: Boolean = scrolling,
        transition: Boolean = transitioning,
        gesture: Boolean = gesturing,
    ) {
        scrolling = scroll
        transitioning = transition
        gesturing = gesture
        if (scrolling || transitioning || gesturing) {
            tailJob?.cancel()
            tailJob = null
            writeFrozen(true)
        } else if (frozen && tailJob == null) {
            tailJob =
                scope.launch {
                    delay(tailMs)
                    tailJob = null
                    writeFrozen(false)
                }
        }
    }

    private fun writeFrozen(value: Boolean) {
        if (frozen != value) {
            frozen = value
            frozenWrites++
        }
    }

    companion object {
        const val DefaultTailMs = 120L
    }
}

@Composable
fun rememberMotionGate(tailMs: Long = MotionGate.DefaultTailMs): MotionGate {
    val scope = rememberCoroutineScope()
    return remember(scope, tailMs) { MotionGate(scope, tailMs) }
}
