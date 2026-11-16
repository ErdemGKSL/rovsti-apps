package dev.rosty.messages.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Away from a conversation this long, it opens again at the newest message instead of where it was left. */
const val THREAD_STALE_MS: Long = 15 * 60 * 1_000L

/**
 * True when a conversation left at [leftAtMs] (wall clock; [NOT_LEFT] when it was never left) and shown again at
 * [nowMs] should jump to the newest message: away for [THREAD_STALE_MS] or more, or the clock went back.
 */
fun returnsToNewest(
    leftAtMs: Long,
    nowMs: Long,
    staleMs: Long = THREAD_STALE_MS,
): Boolean = leftAtMs != NOT_LEFT && (nowMs - leftAtMs >= staleMs || nowMs < leftAtMs)

/** [returnsToNewest]: the conversation was not left yet. */
const val NOT_LEFT: Long = -1L

/**
 * Scrolls [listState] (a reversed list: item 0 is the newest message) to the newest message when the conversation is
 * shown again after [THREAD_STALE_MS] away: the app was in the background, another tab was open, or the page sat
 * under another one. The time it was left is saved with the page, so it survives a tab switch and a process restart.
 */
@Composable
fun ScrollToNewestAfterAway(
    listState: LazyListState,
    now: () -> Long = System::currentTimeMillis,
) {
    val leftAt = rememberSaveable { mutableLongStateOf(NOT_LEFT) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Shown again (composed after a tab switch, or the app back in front).
    LaunchedEffect(lifecycle, listState) {
        lifecycle.currentStateFlow.collect { state ->
            if (state.isAtLeast(Lifecycle.State.RESUMED)) {
                if (returnsToNewest(leftAt.longValue, now()) && listState.awayFromNewest()) {
                    listState.scrollToItem(0)
                }
                leftAt.longValue = NOT_LEFT
            } else if (leftAt.longValue == NOT_LEFT) {
                leftAt.longValue = now()
            }
        }
    }
    // Left without the lifecycle moving (another tab, another page on top).
    DisposableEffect(listState) { onDispose { if (leftAt.longValue == NOT_LEFT) leftAt.longValue = now() } }
}

private fun LazyListState.awayFromNewest(): Boolean = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0
