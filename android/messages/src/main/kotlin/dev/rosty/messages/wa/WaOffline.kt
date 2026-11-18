package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncHistory
import dev.rosty.core.model.wa.WaSyncMediaStats
import dev.rosty.core.model.wa.WaSyncWiped
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.wa.sync.WaRequestGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/**
 * Drops what the app keeps for a WhatsApp link outside the offline store and the media directory: drafts and mutes of
 * its chats, notifications, reminders, scheduled texts in memory, names asked through it. Called only when the store
 * said another account was linked, and on an explicit unlink.
 */
fun interface WaLinkCleanup {
    suspend fun forget(instanceId: String)
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
 *   and when the [wake] job fires;
 * - it keeps the media files in step with the store through [downloads]: reconciled when the store opens, fetched by
 *   the store's queue and rules, under the store's cap;
 * - when the store says another account was linked, and on [unlink], everything kept for the link goes: the media
 *   directory, and through [cleanup] what is kept per chat outside the store. Nothing else ever deletes it.
 *
 * [history] is the progress of the history sync as the store keeps it (so it survives a restart).
 *
 * [changes] tells the readers which chats to read again (null: all); [online] is whether the gateway is reachable.
 *
 * One person is one chat, whichever id the gateway names them by: when the store learns that two chats are one it
 * moves its rows and reports the step, and [rekeys] moves what the app keeps per chat outside the store (the steps
 * logged while the app was not running are followed when the store opens). Ids that reach the app outside the store
 * are resolved here before anyone compares them with a chat: the events of [live], and [keyOf] for a route, a
 * notification tag or a hand-off from another app.
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
    /** Moves what is kept per chat outside the store when two chats become one. */
    val rekeys: WaRekeys = WaRekeys(),
    /** The media files of the store; null: nothing is downloaded (tests of other things). */
    private val downloads: WaDownloads? = null,
    private val cleanup: WaLinkCleanup = WaLinkCleanup { },
    /** Deletes the store files of an instance, open or not (`WaSyncStore.wipe`); the answer names its media files. */
    private val wipe: suspend (instanceId: String) -> Outcome<WaSyncWiped> = {
        Outcome.Ok(WaSyncWiped(emptyList(), emptyList()))
    },
    /** The gateway budget of the lazy sync (`plans/wa-vault.md` 14); null: unlimited. */
    private val gate: WaRequestGate? = null,
    /**
     * What else [lock] tears down: the repository's gateway and event socket (and the event streams and call-audio
     * handles opened from it), the Status pictures, the thumbnails in memory, and `vault.lock`.
     */
    private val onLock: suspend () -> Unit = {},
    /**
     * The store lives in memory only (`plans/wa-vault.md` 13.1): a cold process (the wake job) has no outbox to drain
     * and starts no sync of its own.
     */
    private val memoryOnly: Boolean = false,
    /** Start locked (the vault is not open yet): no store opens and no request goes out until [unlock]. */
    startLocked: Boolean = false,
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

    /**
     * The stream events as they come (typing, presence, status changes): for what is not stored. The chat and the
     * people of a message, a typing line and a presence are named by the keys the store keeps them under, so a
     * listener compares them with its chat as they are.
     */
    val live: SharedFlow<GatewayEvent> = liveBus.asSharedFlow()

    private val historyState = MutableStateFlow<WaSyncHistory?>(null)

    /**
     * How far the phone's history has arrived at the gateway, as the store last heard; null before the first report
     * and while WhatsApp is not linked. Read from the store when it opens, after every status and after the events
     * that move it.
     */
    val history: StateFlow<WaSyncHistory?> = historyState.asStateFlow()

    private val started = AtomicBoolean(false)

    /** True between [lock] and [unlock]: no store is open and no request goes out. */
    private val lockedState = MutableStateFlow(startLocked)
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()
    private val refreshQueued = AtomicBoolean(false)
    private val drainQueued = AtomicBoolean(false)
    private var socket: Job? = null

    /** E6-WA R4: drops disappearing messages when they expire, while a store is open. */
    private var expiry: Job? = null

    /** One link change at a time: opening, closing and wiping a store never interleave. */
    private val linking = Mutex()

    /** Follows the link from now on. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        gate?.budget?.let { if (it.locked && !lockedState.value) it.unlocked() }
        scope.launch {
            combine(linkChanges, lockedState) { _, locked -> locked }.collectLatest { locked ->
                follow(if (locked) null else link())
                settledState.value = true
            }
        }
    }

    /** Not cancelled half way: a store that was opened is published or closed, never lost. */
    private suspend fun follow(next: WaStoreLink?) =
        linking.withLock { withContext(NonCancellable) { followLocked(next) } }

    private suspend fun closeLocked() {
        // Joined: what the old socket still held is dropped, not applied after the next one started.
        socket?.cancelAndJoin()
        socket = null
        expiry?.cancelAndJoin()
        expiry = null
        onlineState.value = false
        val current = sessionState.value ?: return
        sessionState.value = null
        historyState.value = null
        // Round 7a: the recent chats and messages go into the DK-sealed history file while the vault is still open
        // (a lock closes the store before `vault.lock`), so the next unlock shows them at once. Refused while locked.
        if (memoryOnly) current.store.saveHistory()
        current.store.close()
        changeBus.tryEmit(null)
    }

    private suspend fun followLocked(next: WaStoreLink?) {
        val current = sessionState.value
        if (current != null && current.link == next) return
        if (current == null || next == null || current.link.instanceId != next.instanceId) {
            closeLocked()
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
            // Merges logged while nothing followed them (at the store's open, in another run): before anyone reads.
            rekeys.replay(store)
            // Media lives in RAM: the store's LRU keeps it near 64 MiB (`plans/wa-vault.md` 7).
            if (store.opened.lazy) store.setMediaCap(RAM_CAP_BYTES)
            // Rows and files agree again, and the cache is under its cap, before anyone reads a path.
            downloads?.opened(store)
            val runner =
                WaSyncRunner(
                    store,
                    remote,
                    now,
                    changed,
                    onRekeyed = { rekeys.follow(store, it) },
                    onAccountChanged = { files -> accountChanged(store, files) },
                    onHistory = { readHistory(store) },
                    onMedia = { refs -> wantMedia(store, refs) },
                    gate = gate,
                )
            val session = WaSession(next, store, runner, WaOutbox(store, remote, now, changed))
            readHistory(store)
            sessionState.value = session
            changeBus.tryEmit(null)
            socket = scope.launch { run(session) }
            expiry = scope.launch { expireLoop(store) }
            kickMedia(session)
        } else {
            // Same instance, another gateway handle (a new key, another URL): only the socket is reopened.
            val session = WaSession(next, current.store, current.runner, current.outbox)
            socket?.cancelAndJoin()
            onlineState.value = false
            sessionState.value = session
            socket = scope.launch { run(session) }
        }
    }

    /**
     * E6-WA R4: disappearing messages go when their timer runs out. The store drops what expired while the app was
     * closed as soon as it opens; after that one wait runs until the next expiry the store names, cut short when rows
     * change (a new message may expire sooner). Each expiry time is waited for once, so a clock that does not move
     * (tests) never spins. The media files of a dropped row are deleted with it.
     */
    private suspend fun expireLoop(store: WaSyncStore) {
        var fired: Long? = null
        while (true) {
            val out = store.expire(now()).getOrNull()
            if (out != null) {
                downloads?.expired(out.files)
                if (out.removed > 0 || out.chats.isNotEmpty()) changeBus.tryEmit(out.chats.toSet())
            }
            val next = out?.nextMs
            if (next == null || next == fired) {
                changes.first()
                continue
            }
            val wait = (next - now()).coerceAtLeast(0L)
            val changed = withTimeoutOrNull(wait) { changes.first().let { true } }
            if (changed == null) fired = next
        }
    }

    private suspend fun readHistory(store: WaSyncStore) {
        historyState.value = store.history().getOrNull()
    }

    /**
     * The store emptied itself: another account is linked to the instance. Its media files and what the app keeps
     * for the link outside the store belonged to the old account and go with it. The caller refreshes the chat list.
     */
    private suspend fun accountChanged(
        store: WaSyncStore,
        files: List<String>,
    ) {
        RostyLog.i(TAG, "another account is linked: the cache of the old one is dropped")
        forgetLink(store.instanceId, files)
    }

    private suspend fun forgetLink(
        instanceId: String,
        files: List<String>,
    ) {
        downloads?.wipe(instanceId, files)
        rekeys.forget(instanceId)
        cleanup.forget(instanceId)
        changeBus.tryEmit(null)
    }

    /**
     * Forgets the link: [forget] removes it (the repository's own unlink), then the store of the instance is deleted
     * with its media directory and what is kept per chat outside the store. Nothing is deleted when [forget] fails.
     */
    suspend fun unlink(forget: suspend () -> Outcome<Unit>): Outcome<Unit> {
        val instance = sessionState.value?.link?.instanceId ?: link()?.instanceId
        val removed = forget()
        if (removed is Outcome.Err || instance == null) return removed
        linking.withLock {
            withContext(NonCancellable) {
                closeLocked()
                val files =
                    when (val wiped = wipe(instance)) {
                        is Outcome.Ok -> {
                            wiped.value.mediaFiles
                        }

                        is Outcome.Err -> {
                            RostyLog.w(TAG, "wiping the store failed: ${wiped.error.message}")
                            emptyList()
                        }
                    }
                forgetLink(instance, files)
            }
        }
        return removed
    }

    /** Offers media rows that arrived or came into view to the auto-download rules and fetches what they queue. */
    suspend fun wantMedia(refs: List<WaMediaRef>) {
        sessionState.value?.let { wantMedia(it.store, refs) }
    }

    private suspend fun wantMedia(
        store: WaSyncStore,
        refs: List<WaMediaRef>,
    ) {
        // The lazy sync has no auto-download: media is fetched on a tap only.
        if (store.opened.lazy) return
        val files = downloads ?: return
        if (files.auto(store, refs)) files.kick(store, ::noteChanged)
    }

    /** The media cache in numbers (the settings); null while WhatsApp is not linked. */
    suspend fun mediaStats(): WaSyncMediaStats? =
        sessionState.value
            ?.store
            ?.mediaStats(now())
            ?.getOrNull()

    /**
     * Stores the cap of the media cache ([capBytes] null: no limit) and brings the cache under it at once: the
     * files the store lets go of are deleted, their messages keep the thumbnail. False when it was not stored.
     */
    suspend fun setMediaCap(capBytes: Long?): Boolean {
        val store = sessionState.value?.store ?: return false
        if (store.setMediaCap(capBytes) is Outcome.Err) return false
        val gone = downloads?.evict(store).orEmpty()
        changeBus.tryEmit(gone)
        return true
    }

    /** Starts the download worker on what is due; join the answer to wait for a tapped file. Null: no downloads. */
    fun kickMedia(session: WaSession): Job? = downloads?.kick(session.store, ::noteChanged)

    /** The sync of one session: the socket after the store's `seq`, events in batches, the owed fetches. */
    private suspend fun run(session: WaSession) =
        coroutineScope {
            // The event socket is one request of the cold start; none while locked.
            if (gate != null && gate.call { Outcome.Ok(Unit) } is Outcome.Err) return@coroutineScope
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
                        kickMedia(session)
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
                        // A socket that was closed meanwhile: its events are dropped, the next one replays them.
                        ensureActive()
                        val applied = session.runner.apply(batch)
                        batch.forEach { liveBus.tryEmit(keyed(session.store, it.event)) }
                        if (applied?.resync == true || applied?.accountChanged == true) {
                            launchRefresh(session, this, null)
                        }
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

    /**
     * The owner played the received voice note [messageId] of [chat]: its played receipt is queued (once per message)
     * and goes out like any outbox row. True when a receipt was queued now.
     */
    suspend fun markPlayed(
        chat: String,
        messageId: String,
    ): Boolean {
        val session = sessionState.value ?: return false
        if (!session.outbox.markPlayed(newId(), chat, messageId)) return false
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

    /**
     * [event] with its ids as the store keys them (the batch that carried it was applied, so the pair an alt id
     * taught is known). Only what listeners match against a chat is touched.
     */
    private suspend fun keyed(
        store: WaSyncStore,
        event: GatewayEvent,
    ): GatewayEvent =
        when (event) {
            is GatewayEvent.Message -> {
                event.copy(chat = keyOf(store, event.chat), sender = event.sender?.let { keyOf(store, it) })
            }

            is GatewayEvent.Typing -> {
                event.copy(chat = keyOf(store, event.chat), jid = keyOf(store, event.jid))
            }

            is GatewayEvent.Presence -> {
                event.copy(jid = keyOf(store, event.jid))
            }

            else -> {
                event
            }
        }

    private suspend fun keyOf(
        store: WaSyncStore,
        jid: String,
    ): String = store.resolve(jid).getOrNull()?.key ?: WaIds.bare(jid) ?: jid

    /**
     * The key the chat of [jid] is stored under: resolve an id that comes from outside the store (a route argument,
     * a notification tag, a hand-off from Phone or Contacts, a push) before opening or comparing. Without an open
     * store the id itself, without its device.
     */
    suspend fun keyOf(jid: String): String {
        val store = sessionState.value?.store ?: return WaIds.bare(jid) ?: jid
        return keyOf(store, jid)
    }

    /**
     * The phone numbers of the person behind [jid], the current one first, then the former ones; without an open
     * store the number [jid] itself carries, if any.
     */
    suspend fun numbersOf(jid: String): List<String> =
        sessionState.value
            ?.store
            ?.numbers(jid)
            ?.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(WaIds.number(jid))

    /**
     * Group and profile answers fetched outside the sync: they name people by both ids, the store learns from them.
     */
    suspend fun observe(
        groups: List<GroupInfo> = emptyList(),
        profiles: List<WaProfile> = emptyList(),
    ) {
        sessionState.value?.runner?.observe(groups, profiles)
    }

    /** Rows of [chats] changed outside the sync (a thumbnail or a download was stored): the readers read again. */
    fun noteChanged(chats: Set<String>) {
        changeBus.tryEmit(chats)
    }

    /** The page before the oldest stored message of [chat] (the thread reached the end of what is stored). */
    suspend fun older(chat: String): OlderResult = sessionState.value?.runner?.older(chat) ?: OlderResult.Offline

    /**
     * The wake job's work (a network is there): open the store if this is a cold process, fetch what the plan owes and
     * drain the outbox. Schedules the next wake itself when a row waits for its retry.
     */
    suspend fun runFromWork() {
        // A memory-only store: a cold process has nothing queued, and a running one drains on its own socket.
        if (memoryOnly) {
            if (!started.get() || lockedState.value) return
            sessionState.value?.let { drainNow(it, networkBack = true) }
            return
        }
        start()
        val session = withTimeoutOrNull(OPEN_TIMEOUT_MS) { sessionState.filterNotNull().first() } ?: return
        // The socket replays what was missed (the echoes of sends that did go out) before anything is sent again.
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { onlineState.first { it } }
        delay(replayGraceMs)
        session.runner.catchUp()
        drainNow(session, networkBack = true)
        // Downloads that waited for a network or for their next attempt; the job keeps the process until they end.
        kickMedia(session)?.let { pass -> withTimeoutOrNull(MEDIA_WORK_MS) { pass.join() } }
    }

    /**
     * The chat [chat] is on screen (lazy sync): one page when it was not paged in this session, and it becomes the
     * chat a refresh pages. Null: nothing was owed (or the store is not lazy).
     */
    suspend fun opened(chat: String): OlderResult? = sessionState.value?.runner?.opened(chat)

    /** The chat on screen left it. */
    suspend fun closed() {
        sessionState.value?.runner?.closed()
    }

    /**
     * Locks WhatsApp (`plans/wa-vault.md` 6, 13.2; wired to the lifecycle in round 4): the one teardown path. The
     * budget closes first (no request leaves after this call starts), then the event socket and the in-memory store
     * close, the media in RAM is freed, and [onLock] closes the gateway, its streams and the vault session. Unsent
     * messages and everything read are gone (`plans/wa-vault.md` 7).
     */
    suspend fun lock() {
        gate?.budget?.lock()
        lockedState.value = true
        linking.withLock {
            withContext(NonCancellable) {
                closeLocked()
                downloads?.dropRam()
            }
        }
        onLock()
    }

    /**
     * The app went to the background (round 7a): the recent chats and messages are written into the DK-sealed history
     * file now too, so a process the system ends before the lock still shows them at the next unlock.
     */
    suspend fun saveHistory() {
        if (!memoryOnly || lockedState.value) return
        linking.withLock { sessionState.value?.store?.saveHistory() }
    }

    /** The vault was unlocked: a fresh budget, and the store opens again (lazily filled). */
    fun unlock() {
        gate?.budget?.unlocked()
        lockedState.value = false
    }

    companion object {
        private const val TAG = "wa-offline"

        /** The media cache in RAM (`plans/wa-vault.md` 7). */
        const val RAM_CAP_BYTES = 64L * 1024 * 1024
        private const val CHANGE_BUFFER = 256
        private const val MAX_BATCH = 200
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MEDIA_WORK_MS = 8 * 60_000L

        /** How long after a reconnect the outbox waits for the replayed events (the echoes of what did go out). */
        const val REPLAY_GRACE_MS = 1_500L

        /** A local drop of events: chats active within this long get a page again. */
        const val RECENT_MS = 15 * 60_000L
    }
}
