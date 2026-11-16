package dev.rosty.messages.conversation

/*
 * Where a conversation's list stays when messages arrive, the page comes back or a thread opens (plan
 * `_bmad-output/kotlin-ui/plans/thread-scroll.md`, section 2). A pure model: [reduce] takes an event and answers the
 * next state and at most one scroll command; [ThreadScrollState] feeds it from the list and runs the commands.
 *
 * The list is reversed: index 0 is the newest row, the first visible item is the one at the bottom edge, and its
 * offset is how many pixels of it lie below the content start. A reader at the newest is [ScrollMode.Following]
 * (new messages are shown); a reader who scrolled up is [ScrollMode.Anchored] (the place is kept, the jump button
 * counts what arrived).
 */

/** Away from a conversation this long, a reader who scrolled up comes back at the newest message. */
const val THREAD_STALE_MS: Long = 15 * 60 * 1_000L

/** [returnsToNewest]: the conversation was not left yet. */
const val NOT_LEFT: Long = -1L

/**
 * True when a conversation left at [leftAtMs] (wall clock; [NOT_LEFT] when it was never left) and shown again at
 * [nowMs] should jump to the newest message: away for [THREAD_STALE_MS] or more, or the clock went back.
 */
fun returnsToNewest(
    leftAtMs: Long,
    nowMs: Long,
    staleMs: Long = THREAD_STALE_MS,
): Boolean = leftAtMs != NOT_LEFT && (nowMs - leftAtMs >= staleMs || nowMs < leftAtMs)

/** Pinned to the newest message, or holding a place. */
sealed interface ScrollMode {
    /** New messages are shown. */
    data object Following : ScrollMode

    /** The row [key] stays where it is, [offsetPx] of it below the bottom edge. */
    data class Anchored(
        val key: String,
        val offsetPx: Int,
    ) : ScrollMode
}

/** [ScrollPhase.Opening]: a new page; [ScrollPhase.Restoring]: a saved page came back; then [ScrollPhase.Live]. */
enum class ScrollPhase { Opening, Restoring, Live }

/** What a row is, for the rules: only [Incoming] counts on the badge, [OutgoingPending] pulls the list down. */
enum class RowKind { Incoming, Outgoing, OutgoingPending, Unread, Label }

/** The rows, newest first; read in place, never copied. */
interface ScrollRows {
    val size: Int

    fun key(index: Int): String

    fun kind(index: Int): RowKind
}

/** The first visible item: its index, how many pixels of it lie below the bottom edge, and its key. */
data class ScrollSpot(
    val index: Int,
    val offsetPx: Int,
    val key: String?,
)

/** The model's state. [mode], [seenKey] and [leftAtMs] are saved with the page; the rest is rebuilt. */
data class ThreadScroll(
    val mode: ScrollMode = ScrollMode.Following,
    val phase: ScrollPhase = ScrollPhase.Opening,
    /** The newest row the reader had on screen since the thread opened (only moves towards newer). */
    val seenKey: String? = null,
    /** Incoming bubbles newer than [seenKey]: the badge of the jump button. */
    val unseen: Int = 0,
    /** Wall clock of when the page stopped being visible; [NOT_LEFT] while it is. */
    val leftAtMs: Long = NOT_LEFT,
    /** The key of row 0 at the last [ScrollEvent.Items]. */
    val newestKey: String? = null,
    val wasEmpty: Boolean = true,
    /** Following, but a new message waits for a hold (a finger, an overlay, select mode) to end. */
    val followPending: Boolean = false,
    /** The row that was the newest when the follow became pending: still "at the newest" for a scroll in progress. */
    val pendingKey: String? = null,
    val resumedAtMs: Long = 0,
) {
    /** The jump button shows exactly while the reader holds a place (I3): hidden means new messages are shown. */
    val jumpVisible: Boolean get() = phase == ScrollPhase.Live && mode is ScrollMode.Anchored
}

/** What the page knows when the rows change. */
data class ScrollEnv(
    val nowMs: Long,
    val resumed: Boolean,
    val held: Boolean,
    /** Opening only: the unread divider of this opening is in the rows. */
    val openReady: Boolean = true,
    /** Opening only: a reveal target will be scrolled to. */
    val openAtTarget: Boolean = false,
)

sealed interface ScrollEvent {
    /** The rows changed (or are seen for the first time). */
    data class Items(
        val rows: ScrollRows,
        val env: ScrollEnv,
    ) : ScrollEvent

    /** The spot changed, or a command was applied. [scrolling]: a scroll that the screen did not start is running. */
    data class Moved(
        val spot: ScrollSpot,
        val scrolling: Boolean,
        val rows: ScrollRows,
        val held: Boolean,
    ) : ScrollEvent

    /** A hold ended. */
    data class Released(
        val rows: ScrollRows,
    ) : ScrollEvent

    /** The page is not visible any more. */
    data class Left(
        val nowMs: Long,
    ) : ScrollEvent

    /** The page is visible again; its composition was kept. */
    data class Returned(
        val nowMs: Long,
    ) : ScrollEvent

    /** A notification opened the thread that is up. */
    data object OpenedAgain : ScrollEvent

    /** The round button. */
    data object JumpToNewest : ScrollEvent
}

/** What the list has to do. */
sealed interface ScrollCommand {
    data class ToNewest(
        val animated: Boolean,
    ) : ScrollCommand

    /** Restores an anchor. */
    data class ToKey(
        val key: String,
        val offsetPx: Int,
    ) : ScrollCommand

    /** The unread divider near the top of the list area. */
    data class ToUnread(
        val key: String,
    ) : ScrollCommand
}

/** [slopPx]: how much of the newest row may be hidden while the reader is still "at the newest" (48 dp). */
data class ScrollLimits(
    val slopPx: Int,
    val staleMs: Long = THREAD_STALE_MS,
    /** At most this many new rows slide in; more appear at once. */
    val animateMaxRows: Int = 3,
    /** Rows that arrive this soon after the page came back appear at once. */
    val returnSettleMs: Long = 600,
)

/** How far down [ScrollEvent.Items] looks for the previous newest row. */
private const val ADDED_SEARCH = 50

/** The next state for [event], and the command the list has to run (null: none). */
fun reduce(
    state: ThreadScroll,
    event: ScrollEvent,
    limits: ScrollLimits,
): Pair<ThreadScroll, ScrollCommand?> =
    when (event) {
        is ScrollEvent.Items -> {
            onItems(state, event.rows, event.env, limits)
        }

        is ScrollEvent.Moved -> {
            onMoved(state, event, limits)
        }

        is ScrollEvent.Released -> {
            onReleased(state)
        }

        is ScrollEvent.Left -> {
            state.copy(leftAtMs = if (state.leftAtMs == NOT_LEFT) event.nowMs else state.leftAtMs) to null
        }

        is ScrollEvent.Returned -> {
            onReturned(state, event.nowMs, limits)
        }

        ScrollEvent.OpenedAgain -> {
            onOpenedAgain(state)
        }

        ScrollEvent.JumpToNewest -> {
            state to ScrollCommand.ToNewest(animated = true)
        }
    }

private fun onItems(
    state: ThreadScroll,
    rows: ScrollRows,
    env: ScrollEnv,
    limits: ScrollLimits,
): Pair<ThreadScroll, ScrollCommand?> {
    if (rows.size == 0) return state.copy(wasEmpty = true) to null
    var next = state
    var command: ScrollCommand? = null
    when (state.phase) {
        ScrollPhase.Opening -> {
            if (!env.openReady) return state to null
            val unread = (0 until rows.size).firstOrNull { rows.kind(it) == RowKind.Unread }
            next =
                when {
                    env.openAtTarget || unread == null -> {
                        state.copy(phase = ScrollPhase.Live, mode = ScrollMode.Following, seenKey = rows.key(0))
                    }

                    else -> {
                        command = ScrollCommand.ToUnread(rows.key(unread))
                        state.copy(phase = ScrollPhase.Live, mode = ScrollMode.Anchored(rows.key(unread), 0))
                    }
                }
        }

        ScrollPhase.Restoring -> {
            val mode = state.mode
            val stale = returnsToNewest(state.leftAtMs, env.nowMs, limits.staleMs)
            next = state.copy(phase = ScrollPhase.Live, leftAtMs = NOT_LEFT)
            if (mode is ScrollMode.Anchored && !stale && rows.indexOf(mode.key) >= 0) {
                command = ScrollCommand.ToKey(mode.key, mode.offsetPx)
            } else {
                next = next.copy(mode = ScrollMode.Following, seenKey = rows.key(0))
                command = ScrollCommand.ToNewest(animated = false)
            }
        }

        ScrollPhase.Live -> {
            val mode = state.mode
            when {
                state.wasEmpty -> {
                    if (mode is ScrollMode.Anchored && rows.indexOf(mode.key) >= 0) {
                        command = ScrollCommand.ToKey(mode.key, mode.offsetPx)
                    } else {
                        next = state.copy(mode = ScrollMode.Following, followPending = false)
                        command = ScrollCommand.ToNewest(animated = false)
                    }
                }

                rows.key(0) == state.newestKey -> {
                    // An edit, a tick, a reaction, paging: nothing to follow.
                    Unit
                }

                else -> {
                    val added = rows.indexOf(state.newestKey, ADDED_SEARCH)
                    val ownSend = added > 0 && (0 until added).any { rows.kind(it) == RowKind.OutgoingPending }
                    when {
                        ownSend -> {
                            next = state.copy(mode = ScrollMode.Following, followPending = false, pendingKey = null)
                            command = ScrollCommand.ToNewest(animated = true)
                        }

                        mode is ScrollMode.Following && env.held -> {
                            next =
                                state.copy(
                                    followPending = true,
                                    pendingKey = if (state.followPending) state.pendingKey else state.newestKey,
                                )
                        }

                        mode is ScrollMode.Following -> {
                            val animated =
                                env.resumed &&
                                    added in 1..limits.animateMaxRows &&
                                    env.nowMs - state.resumedAtMs >= limits.returnSettleMs
                            command = ScrollCommand.ToNewest(animated)
                        }

                        else -> {
                            // Anchored: the lazy list keeps the first visible row by key.
                            Unit
                        }
                    }
                }
            }
        }
    }
    next = next.copy(newestKey = rows.key(0), wasEmpty = false)
    // Following shows every row, so every row counts as seen; a pending follow keeps counting.
    if (next.mode is ScrollMode.Following && !next.followPending) next = next.copy(seenKey = rows.key(0))
    return recount(next, rows) to command
}

private fun onMoved(
    state: ThreadScroll,
    event: ScrollEvent.Moved,
    limits: ScrollLimits,
): Pair<ThreadScroll, ScrollCommand?> {
    if (state.phase != ScrollPhase.Live || event.rows.size == 0) return state to null
    val spot = event.spot
    val rows = event.rows
    val key = spot.key ?: rows.keyOrNull(spot.index)
    val atNewest =
        spot.offsetPx <= limits.slopPx &&
            (spot.index == 0 || (state.followPending && key != null && key == state.pendingKey))
    val evaluated: ScrollMode =
        when {
            atNewest -> ScrollMode.Following
            key != null -> ScrollMode.Anchored(key, spot.offsetPx)
            else -> state.mode
        }
    var next = state
    var command: ScrollCommand? = null
    if (event.scrolling) {
        next = state.copy(mode = evaluated)
    } else {
        when (state.mode) {
            is ScrollMode.Anchored -> {
                // At rest after a data change: re-anchor, or follow when the list came to rest at the newest.
                next = state.copy(mode = evaluated)
            }

            ScrollMode.Following -> {
                // Moved by someone else (an instant scroll) unless a pending follow explains it.
                if (!atNewest && !state.followPending) next = state.copy(mode = evaluated)
                if (state.followPending && !event.held) {
                    next = next.copy(followPending = false, pendingKey = null)
                    command = ScrollCommand.ToNewest(animated = false)
                }
            }
        }
    }
    if (next.mode is ScrollMode.Anchored) next = next.copy(followPending = false, pendingKey = null)
    // The seen key only moves towards newer rows.
    if (key != null) {
        val seenAt = next.seenKey?.let { rows.indexOf(it) } ?: -1
        val at = if (key == rows.keyOrNull(spot.index)) spot.index else rows.indexOf(key)
        if (seenAt < 0 || (at in 0 until seenAt)) next = next.copy(seenKey = key)
    }
    if (next.mode is ScrollMode.Following && spot.index == 0 && atNewest && command == null) {
        next = next.copy(seenKey = rows.key(0), unseen = 0)
        return next to null
    }
    return recount(next, rows, force = next.seenKey != state.seenKey) to command
}

private fun onReleased(state: ThreadScroll): Pair<ThreadScroll, ScrollCommand?> =
    if (state.phase == ScrollPhase.Live && state.mode is ScrollMode.Following && state.followPending) {
        state.copy(followPending = false, pendingKey = null) to ScrollCommand.ToNewest(animated = false)
    } else {
        state to null
    }

private fun onReturned(
    state: ThreadScroll,
    nowMs: Long,
    limits: ScrollLimits,
): Pair<ThreadScroll, ScrollCommand?> {
    val stale = returnsToNewest(state.leftAtMs, nowMs, limits.staleMs)
    val next = state.copy(leftAtMs = NOT_LEFT, resumedAtMs = nowMs)
    return if (state.phase == ScrollPhase.Live && state.mode is ScrollMode.Anchored && stale) {
        next.copy(mode = ScrollMode.Following, followPending = false, pendingKey = null) to
            ScrollCommand.ToNewest(animated = false)
    } else {
        next to null
    }
}

private fun onOpenedAgain(state: ThreadScroll): Pair<ThreadScroll, ScrollCommand?> =
    if (state.phase == ScrollPhase.Live) {
        state.copy(mode = ScrollMode.Following, followPending = false, pendingKey = null) to
            ScrollCommand.ToNewest(animated = false)
    } else {
        state to null
    }

/** Recounts [ThreadScroll.unseen]: incoming rows newer than the seen key (2.4). */
private fun recount(
    state: ThreadScroll,
    rows: ScrollRows,
    force: Boolean = true,
): ThreadScroll {
    if (!force) return state
    if (state.unseen == 0 && state.mode is ScrollMode.Following && !state.followPending) return state
    var seen = state.seenKey
    var seenAt = seen?.let { rows.indexOf(it) } ?: -1
    if (seenAt < 0) {
        // The seen row is gone: the anchor stands in for it.
        val anchor = (state.mode as? ScrollMode.Anchored)?.key
        seenAt = anchor?.let { rows.indexOf(it) } ?: -1
        seen = anchor.takeIf { seenAt >= 0 }
    }
    if (seenAt <= 0) return state.copy(seenKey = seen ?: state.seenKey, unseen = 0)
    var count = 0
    for (i in 0 until seenAt) if (rows.kind(i) == RowKind.Incoming) count++
    return state.copy(seenKey = seen, unseen = count)
}

private fun ScrollRows.indexOf(
    key: String?,
    limit: Int = size,
): Int {
    if (key == null) return -1
    for (i in 0 until minOf(size, limit)) if (key(i) == key) return i
    return -1
}

private fun ScrollRows.keyOrNull(index: Int): String? = if (index in 0 until size) key(index) else null
