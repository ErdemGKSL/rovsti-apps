package dev.rosty.messages.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rosty.messages.theme.MessagesMetrics
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The scroll place of one conversation page, saved with the page (plan `thread-scroll.md`, 5.2): feeds [reduce] from
 * the list and runs its commands. The model is a plain field; only [jumpVisible] and [unseen] are snapshot state, and
 * only the jump button reads them, so no bubble recomposes for it.
 */
@Stable
class ThreadScrollState internal constructor(
    initial: ThreadScroll,
) {
    internal var model: ThreadScroll = initial
        private set

    private var shown by mutableStateOf(initial.jumpVisible)
    private var badge by mutableIntStateOf(initial.unseen)

    /** The jump button shows: the reader holds a place above the newest message. */
    val jumpVisible: Boolean get() = shown

    /** Incoming messages that arrived below the screen. */
    val unseen: Int get() = badge

    internal lateinit var listState: LazyListState
    internal var limits = ScrollLimits(slopPx = 0)
    internal var unreadRoomPx = 0
    internal var now: () -> Long = System::currentTimeMillis
    internal var held: () -> Boolean = { false }
    internal var scope: CoroutineScope? = null

    /** Bumped after every instant command, so a [ScrollEvent.Moved] follows even when the spot did not change. */
    internal var tick by mutableIntStateOf(0)

    /** A scroll this state started is running: its samples are not the reader's. */
    internal var programmatic = false
        private set
    private var generation = 0
    private var animation: Job? = null
    private var lastItems: ImmutableList<ConversationItem>? = null
    internal var rows: ScrollRows = ItemRows(kotlinx.collections.immutable.persistentListOf())
        private set

    /** The round button: an animated scroll to the newest message. */
    fun jumpToNewest() {
        dispatch(ScrollEvent.JumpToNewest)
    }

    /** Find, jump to date, a reveal: an animated scroll to [index]; the place it ends at is the reader's. */
    suspend fun jumpTo(index: Int) {
        programmatic { listState.animateScrollToItem(index) }
    }

    internal fun onItems(
        items: ImmutableList<ConversationItem>,
        env: ScrollEnv,
    ) {
        if (items === lastItems && model.phase != ScrollPhase.Opening) return
        lastItems = items
        rows = ItemRows(items)
        dispatch(ScrollEvent.Items(rows, env))
    }

    internal fun onMoved(scrolling: Boolean) {
        if (programmatic) return
        val index = listState.firstVisibleItemIndex
        val key =
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == index }
                ?.key as? String
        val spot = ScrollSpot(index, listState.firstVisibleItemScrollOffset, key)
        dispatch(ScrollEvent.Moved(spot, scrolling, rows, held()))
    }

    internal fun dispatch(event: ScrollEvent) {
        val (next, command) = reduce(model, event, limits)
        model = next
        if (shown != next.jumpVisible) shown = next.jumpVisible
        if (badge != next.unseen) badge = next.unseen
        command?.let(::run)
    }

    private fun run(command: ScrollCommand) {
        when (command) {
            is ScrollCommand.ToNewest -> {
                if (command.animated) animate { listState.animateScrollToItem(0) } else request(0, 0)
            }

            is ScrollCommand.ToKey -> {
                val index = indexOf(command.key)
                if (index >= 0) request(index, command.offsetPx) else request(0, 0)
            }

            is ScrollCommand.ToUnread -> {
                if (listState.layoutInfo.viewportSize.height > 0) {
                    toUnread(command.key)
                } else {
                    // A reopened thread whose rows were already there: the list is not measured yet.
                    scope?.launch {
                        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
                        toUnread(command.key)
                    }
                }
            }
        }
    }

    /** The divider [key] near the top of the list area; the list clamps at the newest row when the run fits. */
    private fun toUnread(key: String) {
        val index = indexOf(key)
        if (index < 0) return
        val info = listState.layoutInfo
        val height = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        request(index, -(height - unreadRoomPx).coerceAtLeast(0))
    }

    private fun request(
        index: Int,
        offsetPx: Int,
    ) {
        // Recorded for the next measure (the one of this frame when called from a SideEffect); it wins over the
        // lazy list's keep-the-first-visible-key rule and cancels a scroll in progress.
        listState.requestScrollToItem(index, offsetPx)
        tick++
    }

    private fun animate(block: suspend () -> Unit) {
        val scope = scope ?: return
        animation?.cancel()
        // Undispatched: the flag is up before the frame that would sample the list half way.
        animation = scope.launch(start = CoroutineStart.UNDISPATCHED) { programmatic { block() } }
    }

    private suspend fun programmatic(block: suspend () -> Unit) {
        val mine = ++generation
        programmatic = true
        try {
            block()
        } finally {
            if (generation == mine) {
                programmatic = false
                onMoved(scrolling = false)
            }
        }
    }

    private fun indexOf(key: String): Int {
        val rows = rows
        for (i in 0 until rows.size) if (rows.key(i) == key) return i
        return -1
    }

    internal companion object {
        /** `[anchorKey or "", offsetPx, seenKey or "", leftAtMs]`; restoring gives [ScrollPhase.Restoring]. */
        fun saver(now: () -> Long): Saver<ThreadScrollState, Any> =
            Saver(
                save = { state ->
                    val model = state.model
                    val anchored = model.mode as? ScrollMode.Anchored
                    // Saved because the page goes away (pushed over, the app stopped): it is left from now on.
                    val left = if (model.leftAtMs == NOT_LEFT) now() else model.leftAtMs
                    arrayListOf(anchored?.key.orEmpty(), anchored?.offsetPx ?: 0, model.seenKey.orEmpty(), left)
                },
                restore = { saved ->
                    val list = saved as List<*>
                    val key = list[0] as String
                    ThreadScrollState(
                        ThreadScroll(
                            mode =
                                if (key.isEmpty()) ScrollMode.Following else ScrollMode.Anchored(key, list[1] as Int),
                            phase = ScrollPhase.Restoring,
                            seenKey = (list[2] as String).ifEmpty { null },
                            leftAtMs = list[3] as Long,
                        ),
                    )
                },
            )
    }
}

/** The rows of a conversation as the model reads them; no copy. */
private class ItemRows(
    private val items: ImmutableList<ConversationItem>,
) : ScrollRows {
    override val size: Int get() = items.size

    override fun key(index: Int): String = items[index].key

    override fun kind(index: Int): RowKind =
        when (val item = items[index]) {
            is ConversationItem.Day -> {
                RowKind.Label
            }

            is ConversationItem.Divider -> {
                RowKind.Unread
            }

            is ConversationItem.Bubble -> {
                val bubble = item.bubble
                when {
                    !bubble.outgoing -> RowKind.Incoming
                    bubble.state == BubbleState.Waiting -> RowKind.OutgoingPending
                    bubble.state == BubbleState.Sending -> RowKind.OutgoingPending
                    else -> RowKind.Outgoing
                }
            }
        }
}

/** The scroll place of the conversation page that calls it; saved with the page, never keyed by the thread. */
@Composable
fun rememberThreadScroll(
    listState: LazyListState,
    now: () -> Long = System::currentTimeMillis,
): ThreadScrollState {
    val clock by rememberUpdatedState(now)
    val scroll =
        rememberSaveable(saver = ThreadScrollState.saver { clock() }) { ThreadScrollState(ThreadScroll()) }
    val density = LocalDensity.current
    scroll.listState = listState
    scroll.now = { clock() }
    scroll.limits = ScrollLimits(slopPx = with(density) { MessagesMetrics.AtNewestSlop.roundToPx() })
    scroll.unreadRoomPx = with(density) { (MessagesMetrics.UnreadRoom * density.fontScale).roundToPx() }
    scroll.scope = rememberCoroutineScope()
    return scroll
}

/**
 * Wires [scroll] to the list: the rows before each measure, the spot after each move, holds, the page going away and
 * coming back, and a notification tap for this thread ([reopen]).
 */
@Composable
internal fun ThreadScrollEffects(
    scroll: ThreadScrollState,
    items: ImmutableList<ConversationItem>,
    ready: Boolean,
    openAtTarget: Boolean,
    held: () -> Boolean,
    reopen: Flow<Unit>?,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holding by rememberUpdatedState(held)
    scroll.held = { holding() }
    val listState = scroll.listState
    // The rows, before the measure of this frame: a follow lands in the same frame.
    SideEffect {
        scroll.onItems(
            items,
            ScrollEnv(
                nowMs = scroll.now(),
                resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                held = holding(),
                openReady = ready,
                openAtTarget = openAtTarget,
            ),
        )
    }
    LaunchedEffect(scroll, listState) {
        snapshotFlow {
            Sample(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                scroll.tick,
            )
        }.collect { scroll.onMoved(scrolling = it.inProgress && !scroll.programmatic) }
    }
    LaunchedEffect(scroll, listState) {
        snapshotFlow { (listState.isScrollInProgress && !scroll.programmatic) || holding() }
            .collect { if (!it) scroll.dispatch(ScrollEvent.Released(scroll.rows)) }
    }
    DisposableEffect(scroll, lifecycle) {
        var stopped = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        stopped = true
                        scroll.dispatch(ScrollEvent.Left(scroll.now()))
                    }

                    // Not the first start of a new composition: only a return after a stop.
                    Lifecycle.Event.ON_START -> {
                        if (stopped) scroll.dispatch(ScrollEvent.Returned(scroll.now()))
                        stopped = false
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            scroll.dispatch(ScrollEvent.Left(scroll.now()))
        }
    }
    LaunchedEffect(scroll, reopen) { reopen?.collect { scroll.dispatch(ScrollEvent.OpenedAgain) } }
}

private data class Sample(
    val inProgress: Boolean,
    val index: Int,
    val offset: Int,
    val tick: Int,
)
