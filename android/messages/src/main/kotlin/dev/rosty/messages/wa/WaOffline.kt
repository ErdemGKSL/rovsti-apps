package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The link the offline store follows: whose store to open, and what the event socket depends on. */
data class WaStoreLink(
    val instanceId: String,
    /** Changes when the gateway or the key does (same instance): the socket is reopened, the store kept. */
    val socketKey: String = "",
)

/** Asks for an outbox drain [delayMs] from now, once the phone has a network (a WorkManager job; no polling). */
fun interface WaWake {
    fun at(delayMs: Long)
}

/** One open store with what fills and drains it. */
class WaSession(
    val link: WaStoreLink,
    val store: WaSyncStore,
    val runner: WaSyncRunner,
    val outbox: WaOutbox,
)

/**
 * The WhatsApp offline side of the app (E6.4s). The inbox and the threads read WhatsApp only from the store of the
 * linked instance; this class keeps that store open, filled and drained:
 *
 * - it opens the [WaSyncStore] of the stored link and closes it on unlink; another instance is another store;
 * - it runs the sync: `plan`, the event socket after the plan's `seq`, the chat list and the gap pages the plan owes,
 *   every stream event applied in batches, the chat list again after a `resync`;
 * - it queues what the user does ([enqueue]) and drains the outbox while the socket is connected, when it reconnects
 *   and when the [wake] job fires.
 *
 * [changes] tells the readers which chats to read again (null: all); [online] is whether the gateway is reachable.
 */
class WaOffline(
    private val scope: CoroutineScope,
    /** Emits whenever the link may have changed (link state, the stored link read); [link] is asked again each time. */
    private val linkChanges: Flow<*>,
    private val link: suspend () -> WaStoreLink?,
    private val open: suspend (instanceId: String) -> Outcome<WaSyncStore>,
    private val remote: WaRemote,
    private val now: () -> Long,
    private val wake: WaWake,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val replayGraceMs: Long = REPLAY_GRACE_MS,
) {
    private val sessionState = MutableStateFlow<WaSession?>(null)

    /** The open store and its runner; null while WhatsApp is not linked (or the store could not be opened). */
    val session: StateFlow<WaSession?> = sessionState.asStateFlow()

    /** True once the stored link has been looked at, so "no session" means "not linked" and not "still opening". */
    private val settledState = MutableStateFlow(false)
    val settled: StateFlow<Boolean> = settledState.asStateFlow()

    private val changeBus =
        MutableSharedFlow<Set<String>?>(
            extraBufferCapacity = CHANGE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** The chats whose stored rows changed; null: every chat (a chat list refresh, another store). */
    val changes: SharedFlow<Set<String>?> = changeBus.asSharedFlow()

    private val onlineState = MutableStateFlow(false)

    /** Whether the event socket is connected: the gateway can be reached right now. */
    val online: StateFlow<Boolean> = onlineState.asStateFlow()

    private val liveBus =
        MutableSharedFlow<GatewayEvent>(
            extraBufferCapacity = CHANGE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** The stream events as they come (typing, presence, status changes): for what is not stored. */
    val live: SharedFlow<GatewayEvent> = liveBus.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val refreshQueued = AtomicBoolean(false)
    private val drainQueued = AtomicBoolean(false)
    private var socket: Job? = null

    /** Follows the link from now on. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            linkChanges.collectLatest {
                follow(link())
                settledState.value = true
            }
        }
    }

    private suspend fun follow(next: WaStoreLink?) {
        val current = sessionState.value
        if (current != null && current.link == next) return
        if (current == null || next == null || current.link.instanceId != next.instanceId) {
            socket?.cancel()
            socket = null
            onlineState.value = false
            if (current != null) {
                sessionState.value = null
                current.store.close()
                changeBus.tryEmit(null)
            }
            if (next == null) return
            val store =
                when (val opened = open(next.instanceId)) {
                    is Outcome.Ok -> {
                        opened.value
                    }

                    is Outcome.Err -> {
                        RostyLog.w(TAG, "opening the store failed: ${opened.error.message}")
                        return
                    }
                }
            val changed: (Set<String>?) -> Unit = { changeBus.tryEmit(it) }
            val session =
                WaSession(next, store, WaSyncRunner(store, remote, now, changed), WaOutbox(store, remote, now, changed))
            sessionState.value = session
            changeBus.tryEmit(null)
            socket = scope.launch { run(session) }
        } else {
            // Same instance, another gateway handle (a new key, another URL): only the socket is reopened.
            val session = WaSession(next, current.store, current.runner, current.outbox)
            socket?.cancel()
            onlineState.value = false
            sessionState.value = session
            socket = scope.launch { run(session) }
        }
    }

    /** The sync of one session: the socket after the store's `seq`, events in batches, the owed fetches. */
    private suspend fun run(session: WaSession) =
        coroutineScope {
            val after = session.runner.afterSeq()
            // What a first run owes (the chat list) does not wait for the socket.
            launchCatchUp(session, this)
            val events =
                remote
                    .events(session.link.instanceId, after)
                    .buffer(Channel.UNLIMITED)
                    .produceIn(this)
            var carried: WaRemoteEvent? = null
            while (true) {
                val next = carried ?: events.receiveCatching().getOrNull() ?: break
                carried = null
                when (next) {
                    WaRemoteEvent.Connected -> {
                        onlineState.value = true
                        launchCatchUp(session, this)
                        launchDrain(session, this, networkBack = true)
                    }

                    WaRemoteEvent.Disconnected -> {
                        onlineState.value = false
                    }

                    WaRemoteEvent.Dropped -> {
                        launchRefresh(session, this, RECENT_MS)
                    }

                    is WaRemoteEvent.Event -> {
                        // A replay after a reconnect comes as a burst: one store call for what is already here.
                        val batch = arrayListOf(next.event)
                        while (batch.size < MAX_BATCH) {
                            val more = events.tryReceive().getOrNull() ?: break
                            if (more !is WaRemoteEvent.Event) {
                                carried = more
                                break
                            }
                            batch += more.event
                        }
                        val applied = session.runner.apply(batch)
                        batch.forEach { liveBus.tryEmit(it.event) }
                        if (applied?.resync == true) launchRefresh(session, this, null)
                    }
                }
            }
            onlineState.value = false
        }

    private fun launchCatchUp(
        session: WaSession,
        scope: CoroutineScope,
    ) {
        scope.launch {
            // Sends held back for the sync can go once it is done.
            if (session.runner.catchUp() && onlineState.value) launchDrain(session, scope, networkBack = false)
        }
    }

    private fun launchRefresh(
        session: WaSession,
        scope: CoroutineScope,
        recentMs: Long?,
    ) {
        if (!refreshQueued.compareAndSet(false, true)) return
        scope.launch {
            refreshQueued.set(false)
            if (session.runner.refresh(recentMs) && onlineState.value) launchDrain(session, scope, networkBack = false)
        }
    }

    /**
     * Drains after [replayGraceMs]: after a reconnect the missed events come first, so an echo confirms a send before
     * it is retried.
     */
    private fun launchDrain(
        session: WaSession,
        scope: CoroutineScope,
        networkBack: Boolean,
    ) {
        if (!networkBack && !drainQueued.compareAndSet(false, true)) return
        scope.launch {
            delay(replayGraceMs)
            if (!networkBack) drainQueued.set(false)
            drainNow(session, networkBack)
        }
    }

    private suspend fun drainNow(
        session: WaSession,
        networkBack: Boolean,
    ) {
        val result = session.outbox.drain(networkBack)
        val at = result.nextWakeMs ?: return
        wake.at((at - now()).coerceAtLeast(0))
    }

    /**
     * Queues [action] for [chat]: it shows in the thread at once and goes out now (connected) or when the connection
     * is back (the wake job, constrained on a network). False when WhatsApp is not linked.
     */
    suspend fun enqueue(
        chat: String,
        action: WaSyncAction,
    ): Boolean {
        val session = sessionState.value ?: return false
        session.outbox.enqueue(newId(), chat, action) ?: return false
        kick(session)
        return true
    }

    /** Lets a failed row go out again. */
    suspend fun retry(
        chat: String,
        clientId: String,
    ): Boolean {
        val session = sessionState.value ?: return false
        if (!session.outbox.retry(chat, clientId)) return false
        kick(session)
        return true
    }

    /** Takes a row that has not gone out from the queue. */
    suspend fun cancel(
        chat: String,
        clientId: String,
    ): Boolean = sessionState.value?.outbox?.cancel(chat, clientId) == true

    private fun kick(session: WaSession) {
        if (onlineState.value) {
            scope.launch { drainNow(session, networkBack = false) }
        } else {
            wake.at(0)
        }
    }

    /** The page before the oldest stored message of [chat] (the thread reached the end of what is stored). */
    suspend fun older(chat: String): OlderResult = sessionState.value?.runner?.older(chat) ?: OlderResult.Offline

    /**
     * The wake job's work (a network is there): open the store if this is a cold process, fetch what the plan owes and
     * drain the outbox. Schedules the next wake itself when a row waits for its retry.
     */
    suspend fun runFromWork() {
        start()
        val session = withTimeoutOrNull(OPEN_TIMEOUT_MS) { sessionState.filterNotNull().first() } ?: return
        // The socket replays what was missed (the echoes of sends that did go out) before anything is sent again.
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { onlineState.first { it } }
        delay(replayGraceMs)
        session.runner.catchUp()
        drainNow(session, networkBack = true)
    }

    companion object {
        private const val TAG = "wa-offline"
        private const val CHANGE_BUFFER = 256
        private const val MAX_BATCH = 200
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** How long after a reconnect the outbox waits for the replayed events (the echoes of what did go out). */
        const val REPLAY_GRACE_MS = 1_500L

        /** A local drop of events: chats active within this long get a page again. */
        const val RECENT_MS = 15 * 60_000L
    }
}
