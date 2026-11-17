package dev.rosty.wa

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.map
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.core.model.wa.WaStreamEvent
import dev.rosty.core.ops.WaEventStream
import dev.rosty.core.ops.WaEvents
import dev.rosty.core.ops.WaFeed
import dev.rosty.core.ops.WaGateway
import dev.rosty.core.ops.WaOps
import dev.rosty.platform.PlatformEvent
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.wa.WaPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Opens the gateway of a stored link; the default is [WaGateway.open], tests pass a fake. */
fun interface WaGatewayOpener {
    suspend fun open(link: StoredLink): Outcome<WaGateway>
}

/**
 * The real [WaRepository]. [scope] owns the event collectors; [waEvents] is the `wa.event` feed of the process and
 * [platformEvents] the bus on which the app's `FirebaseMessagingService` reports `wa_push`.
 *
 * What it guarantees:
 * - one gateway and one event stream at a time, both closed on [forget] and on [relinkTo];
 * - an event whose `seq` jumps ahead of the last one seen (a gap), a `resync` event and a [WaFeed.Resync] (this app
 *   fell behind) each cause exactly one reload of the lists, however many events arrive while it runs (conflated);
 * - the event stream resumes `after` the last `seq` when it is reopened.
 */
class DefaultWaRepository(
    private val store: WaLinkStore,
    private val scope: CoroutineScope,
    private val opener: WaGatewayOpener,
    private val waEvents: WaEvents = WaEvents(),
    private val platformEvents: Flow<PlatformEvent> = PlatformEvents.events,
) : WaRepository {
    constructor(
        store: WaLinkStore,
        ops: WaOps,
        scope: CoroutineScope,
        cacheDir: String? = null,
    ) : this(
        store = store,
        scope = scope,
        opener = WaGatewayOpener { WaGateway.open(it.gatewayUrl, it.key, cacheDir, ops) },
    )

    /** What is open for the current link. Only touched under [lock]. */
    private class Session(
        val link: StoredLink,
        val gateway: WaGateway,
        var stream: WaEventStream? = null,
        var feed: Job? = null,
    ) {
        fun close() {
            feed?.cancel()
            stream?.close()
            gateway.close()
        }
    }

    private val lock = Mutex()
    private var session: Session? = null
    private var lastSeq: Long? = null

    /** The gateway process [lastSeq] counts in (`hello`); null until one said. */
    private var epoch: String? = null

    /** The vault is locked: nothing opens the gateway. */
    private var locked = false
    private val started = AtomicBoolean(false)

    private val state = MutableStateFlow<WaLinkState>(WaLinkState.None)
    override val linkState: StateFlow<WaLinkState> = state.asStateFlow()

    private val settledState = MutableStateFlow(false)
    override val settled: StateFlow<Boolean> = settledState.asStateFlow()

    private val bus =
        MutableSharedFlow<GatewayEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: SharedFlow<GatewayEvent> = bus.asSharedFlow()

    /** A reload request: the chat it concerns, or null for everything. Never dropped (the buffer is generous). */
    private val reloads = MutableSharedFlow<String?>(extraBufferCapacity = EVENT_BUFFER)

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            platformEvents.filter { it.kind == WaPush.EVENT }.collect { wake() }
        }
        scope.launch { lock.withLock { connect() } }
    }

    override suspend fun gateway(): WaGateway? =
        lock.withLock {
            connect()
        }

    override suspend fun wake(): Outcome<Unit> {
        val stream = lock.withLock { session?.stream } ?: return Outcome.Err(RustError.NotFound("no event stream"))
        return stream.reconnectNow().map { }
    }

    override fun chats(): Flow<List<RichChat>> = reloading(null, emptyList()) { it.chatsWithMeta() }

    override fun messages(jid: String): Flow<List<RichMessage>> =
        reloading(jid, emptyList()) { it.messagesWithMeta(jid) }

    override suspend fun relinkTo(link: StoredLink): Outcome<Unit> =
        lock.withLock {
            when (val saved = store.write(link)) {
                is Outcome.Err -> {
                    saved
                }

                is Outcome.Ok -> {
                    closeSession()
                    lastSeq = null
                    epoch = null
                    connect()
                    Outcome.Ok(Unit)
                }
            }
        }

    override suspend fun lock() {
        lock.withLock {
            locked = true
            closeSession()
            // The next socket is live: no `after=` replay of what was missed while locked.
            lastSeq = null
            epoch = null
        }
    }

    override suspend fun unlock() {
        lock.withLock { locked = false }
    }

    override suspend fun forget(): Outcome<Unit> =
        lock.withLock {
            closeSession()
            lastSeq = null
            epoch = null
            val removed = store.forget()
            if (removed is Outcome.Ok) state.value = WaLinkState.None
            removed
        }

    /** [open], then the link is settled whatever the outcome. Call under [lock]. */
    private suspend fun connect(): WaGateway? = open().also { settledState.value = true }

    /** Reads the link and opens everything. Call under [lock]. Returns the gateway, or null (and sets [state]). */
    private suspend fun open(): WaGateway? {
        session?.let { return it.gateway }
        if (locked) return null
        val link =
            when (val read = store.read()) {
                is Outcome.Err -> return failed(read.error)
                is Outcome.Ok -> read.value
            }
        if (link == null) {
            state.value = WaLinkState.None
            return null
        }
        val gateway =
            when (val opened = opener.open(link)) {
                is Outcome.Err -> return failed(opened.error)
                is Outcome.Ok -> opened.value
            }
        val open = Session(link, gateway)
        session = open
        // Subscribe before the stream starts, so the first events are not missed.
        open.feed = scope.launch { waEvents.feed(link.instanceId).collect(::onFeed) }
        when (val stream = gateway.startEvents(link.instanceId, lastSeq)) {
            is Outcome.Ok -> open.stream = stream.value
            is Outcome.Err -> state.value = WaLinkState.Error(WaLinkError.of(stream.error))
        }
        when (val status = gateway.status()) {
            is Outcome.Ok -> if (state.value !is WaLinkState.Error) state.value = status.value.toLinkState()
            is Outcome.Err -> state.value = WaLinkState.Error(WaLinkError.of(status.error))
        }
        reloads.tryEmit(null)
        return gateway
    }

    private fun failed(error: RustError): WaGateway? {
        state.value = WaLinkState.Error(WaLinkError.of(error))
        return null
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun onFeed(feed: WaFeed) {
        when (feed) {
            WaFeed.Resync -> reloads.tryEmit(null)
            is WaFeed.Undecodable -> Unit
            is WaFeed.Stream -> onStream(feed.event)
        }
    }

    private fun onStream(event: WaStreamEvent) {
        when (event) {
            WaStreamEvent.Connected, is WaStreamEvent.Disconnected -> Unit
            WaStreamEvent.Unauthorized -> state.value = WaLinkState.Error(WaLinkError.KeyRefused)
            is WaStreamEvent.Event -> onEvent(event)
        }
    }

    private fun onEvent(event: WaStreamEvent.Event) {
        val body = event.event
        if (body is GatewayEvent.Hello && restarted(body, event.seq)) return
        val previous = lastSeq
        val gap = previous != null && event.seq > previous + 1
        if (previous == null || event.seq > previous) lastSeq = event.seq
        when (body) {
            is GatewayEvent.State -> state.value = body.status().toLinkState()
            is GatewayEvent.Qr -> state.value = WaLinkState.Linking(qr = body.payload)
            is GatewayEvent.PairCode -> state.value = WaLinkState.Linking(code = body.code)
            GatewayEvent.KeyRevoked -> state.value = WaLinkState.Error(WaLinkError.KeyRefused)
            else -> Unit
        }
        // One reload per event, whatever mix of reasons it has.
        when {
            gap || body is GatewayEvent.Resync -> reloads.tryEmit(null)
            else -> body.chatKey()?.let { reloads.tryEmit(it) }
        }
        bus.tryEmit(body)
    }

    /**
     * The first frame of a socket. [lastSeq] only goes up, which is right inside one gateway process; another
     * process (another epoch, or a start point below what was seen) counts from its own start, so the `seq` is taken
     * from the frame and everything is loaded again. True when that happened.
     */
    private fun restarted(
        hello: GatewayEvent.Hello,
        seq: Long,
    ): Boolean {
        val known = epoch
        val previous = lastSeq
        epoch = hello.epoch
        val other = (known != null && known != hello.epoch) || (known == null && previous != null && seq < previous)
        if (!other) return false
        lastSeq = seq
        reloads.tryEmit(null)
        bus.tryEmit(hello)
        return true
    }

    /** A flow that fetches once on collect and again for each reload that concerns [jid] (all reloads for chats). */
    private fun <T> reloading(
        jid: String?,
        none: T,
        fetch: suspend (WaGateway) -> Outcome<T>,
    ): Flow<T> =
        reloads
            .filter { it == null || jid == null || it == jid }
            .onStart { emit(null) }
            .conflate()
            .map { gateway()?.let { gateway -> fetch(gateway).let { (it as? Outcome.Ok)?.value } } ?: none }

    private companion object {
        const val EVENT_BUFFER = 64
    }
}

private fun InstanceStatus.toLinkState(): WaLinkState =
    when (state) {
        InstanceState.Linking -> WaLinkState.Linking()
        InstanceState.Connected, InstanceState.Disconnected -> WaLinkState.Connected(this)
        InstanceState.LoggedOut -> WaLinkState.LoggedOut
        InstanceState.Banned -> WaLinkState.Banned
    }

/**
 * The chat an event changes: its JID, `""` for a change to the chat list only, or null when the event does not
 * change what the lists show (typing, presence, receipts, calls).
 */
private fun GatewayEvent.chatKey(): String? =
    when (this) {
        is GatewayEvent.Message -> chat
        is GatewayEvent.MessageUpdate -> chat
        is GatewayEvent.MessageMetaChanged -> chat
        is GatewayEvent.MessageDeleted -> chat
        is GatewayEvent.ChatMetaChanged -> jid
        is GatewayEvent.ChatDeleted -> jid
        is GatewayEvent.ChatCleared -> jid
        is GatewayEvent.ChatMerged -> into
        else -> null
    }
