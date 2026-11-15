package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.Quoted
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.StatusList
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncEvictRequest
import dev.rosty.core.model.wa.WaSyncEvicted
import dev.rosty.core.model.wa.WaSyncEviction
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest
import dev.rosty.core.model.wa.WaSyncWipeRequest
import dev.rosty.core.ops.WaSyncOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.platform.system.FakeDeviceData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.File

/** A scripted gateway for the sync, outbox and view-model tests: no network, every call logged. */
class FakeWaRemote : WaRemote {
    private val feed = MutableSharedFlow<WaRemoteEvent>(extraBufferCapacity = 256)

    /** The `after` of every socket opened. */
    val socketsAfter = mutableListOf<Long?>()

    /** The gateway is down: every call fails like a lost connection. */
    var down = false
    var chatList: List<RichChat> = emptyList()
    var chatCalls = 0
    val pages = mutableMapOf<Pair<String, Long?>, List<RichMessage>>()
    val pageCalls = mutableListOf<WaSyncFetch>()
    val performed = mutableListOf<Pair<String, WaSyncAction>>()
    val chatActions = mutableListOf<Pair<String, ChatActionKind>>()
    val typed = mutableListOf<Pair<String, TypingState>>()
    val viewed = mutableListOf<String>()
    var statusList = StatusList()
    var statusCalls = 0

    /**
     * The LID and number pairs the gateway lists, by the cursor asked for (null: the first page); null: a gateway
     * from before the list, which answers "not supported".
     */
    var mappings: Map<String?, IdentityMappingsAnswer>? = null

    /** The `since` of every pairs call. */
    val mappingCalls = mutableListOf<String?>()

    /** What `status` answers: the account's own ids, the gateway's epoch, the history; null: it does not say. */
    var ownStatus: InstanceStatus? = null

    /** Every call that fills the store, in the order made: `identity`, `status`, `chats`, `page`. */
    val order = mutableListOf<String>()

    /** What a send, edit, delete, reaction or read mark answers; the default accepts everything. */
    var answer: (String, WaSyncAction) -> Outcome<WaSyncOutcome.Sent> =
        { _, _ -> Outcome.Ok(WaSyncOutcome.Sent("sent-${performed.size}", SENT_AT + performed.size)) }

    private fun <T> gate(value: () -> T): Outcome<T> = if (down) Outcome.Err(LOST) else Outcome.Ok(value())

    fun emit(event: WaRemoteEvent) {
        check(feed.tryEmit(event))
    }

    fun emit(
        seq: Long,
        event: GatewayEvent,
    ) = emit(WaRemoteEvent.Event(WaSyncEvent(seq, SENT_AT + seq, event)))

    override fun events(
        instance: String,
        after: Long?,
    ): Flow<WaRemoteEvent> =
        flow {
            socketsAfter += after
            emitAll(feed)
        }

    override suspend fun chats(): Outcome<List<RichChat>> =
        gate {
            chatCalls++
            order += "chats"
            chatList
        }

    override suspend fun page(fetch: WaSyncFetch): Outcome<List<RichMessage>> =
        gate {
            pageCalls += fetch
            order += "page"
            pages[fetch.chat to fetch.before].orEmpty()
        }

    override suspend fun identityMappings(since: String?): Outcome<IdentityMappingsAnswer> =
        gate {
            mappingCalls += since
            order += "identity"
            mappings?.let { it[since] ?: IdentityMappingsAnswer(supported = true) }
                ?: IdentityMappingsAnswer(supported = false)
        }

    override suspend fun status(): Outcome<InstanceStatus> {
        val status = ownStatus?.takeIf { !down } ?: return Outcome.Err(LOST)
        order += "status"
        return Outcome.Ok(status)
    }

    override suspend fun perform(
        chat: String,
        action: WaSyncAction,
    ): Outcome<WaSyncOutcome.Sent> {
        if (down) return Outcome.Err(LOST)
        performed += chat to action
        return answer(chat, action)
    }

    override suspend fun chatAction(
        jid: String,
        kind: ChatActionKind,
    ): Outcome<Unit> = gate { chatActions += jid to kind }

    override suspend fun typing(
        chat: String,
        state: TypingState,
    ): Outcome<Unit> = gate { typed += chat to state }

    /** The thumbnails and files the gateway has, by message id; anything else is a lost connection. */
    val thumbnails = mutableMapOf<String, BytesAnswer>()
    val files = mutableMapOf<String, BytesAnswer>()
    val thumbnailCalls = mutableListOf<String>()
    val mediaCalls = mutableListOf<String>()

    override suspend fun thumbnail(messageId: String): Outcome<BytesAnswer> {
        thumbnailCalls += messageId
        return thumbnails[messageId]?.takeIf { !down }?.let { Outcome.Ok(it) } ?: Outcome.Err(LOST)
    }

    /** What a download of a message answers instead of its file (`NotFound`: the gateway no longer has it). */
    val mediaErrors = mutableMapOf<String, RustError>()

    override suspend fun media(messageId: String): Outcome<BytesAnswer> {
        mediaCalls += messageId
        mediaErrors[messageId]?.let { return Outcome.Err(it) }
        return files[messageId]?.takeIf { !down }?.let { Outcome.Ok(it) } ?: Outcome.Err(LOST)
    }

    override suspend fun statuses(): Outcome<StatusList> =
        gate {
            statusCalls++
            statusList
        }

    override suspend fun statusMedia(statusId: String): Outcome<BytesAnswer> = Outcome.Err(LOST)

    override suspend fun statusThumbnail(statusId: String): Outcome<BytesAnswer> = Outcome.Err(LOST)

    override suspend fun markStatusViewed(ids: List<String>): Outcome<Unit> = gate { viewed += ids }

    companion object {
        const val SENT_AT = 1_790_000_100_000L
        val LOST = RustError.Network("connection lost")
    }
}

/**
 * What the Rust store does with media and the fake does not model: a thread row says whether its thumbnail is stored and
 * where its downloaded file is, a send of a file in the outbox is a row of that file (kind, type, size, path), a queued
 * reply names what it answers, and [mediaEvict] lets go of the files in [evictNext].
 */
class MediaStoreOps(
    private val inner: FakeWaSyncOps,
) : WaSyncOps by inner {
    /** What the next `media_evict` answers (and forgets the path of). */
    val evictNext = mutableListOf<WaSyncEvicted>()
    private val evicted = mutableSetOf<Pair<String, String>>()

    override suspend fun thread(req: WaSyncThreadRequest): Outcome<WaSyncThreadPage> =
        when (val page = inner.thread(req)) {
            is Outcome.Err -> page
            is Outcome.Ok -> Outcome.Ok(page.value.copy(items = page.value.items.map { decorate(it) }))
        }

    private suspend fun decorate(row: WaSyncMessage): WaSyncMessage {
        val queued = inner.queue.firstOrNull { it.clientId == row.id }
        return when (val action = queued?.action) {
            is WaSyncAction.SendMedia -> {
                row.copy(
                    kind =
                        when (action.media) {
                            MediaKind.Image -> WaSyncKind.Image
                            MediaKind.Video -> WaSyncKind.Video
                            MediaKind.Audio -> WaSyncKind.Audio
                            MediaKind.Document -> WaSyncKind.Document
                            MediaKind.Sticker -> WaSyncKind.Sticker
                        },
                    mime = action.mime,
                    size = action.size,
                    mediaPath = action.file,
                    quoted = action.quotedId?.let { Quoted(it, null, null) },
                )
            }

            is WaSyncAction.SendText -> {
                row.copy(quoted = action.quotedId?.let { Quoted(it, null, null) })
            }

            else -> {
                val media =
                    (inner.media(WaSyncMediaRequest(FakeWaSyncOps.HANDLE, row.chat, row.id)) as? Outcome.Ok)?.value
                val path = media?.path?.takeIf { (row.chat to row.id) !in evicted }
                row.copy(
                    hasThumbnail = row.hasThumbnail || media?.thumbnailB64 != null,
                    mediaPath =
                        path ?: row.mediaPath,
                )
            }
        }
    }

    override suspend fun mediaEvict(req: WaSyncEvictRequest): Outcome<WaSyncEviction> {
        val gone = evictNext.toList()
        evictNext.clear()
        evicted += gone.map { it.chat to it.id }
        return Outcome.Ok(WaSyncEviction(gone, 0))
    }
}

/** A [WaOffline] on fakes, with the knobs the tests turn. */
class WaTestKit(
    scope: CoroutineScope,
    val ops: FakeWaSyncOps = FakeWaSyncOps(INSTANCE),
    /** The ops the store is opened with; a test wraps [ops] to script what the fake does not model. */
    storeOps: WaSyncOps = ops,
    linked: Boolean = true,
    graceMs: Long = GRACE_MS,
    /** What follows a merge of two chats; the default moves nothing and only tells the open screens. */
    val rekeys: WaRekeys = WaRekeys(),
    /** Where media files go; null: the kit downloads nothing. */
    mediaDir: File? = null,
    io: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    val remote = FakeWaRemote()

    /** The network the phone is on and the room it has left, as the download rules hear them. */
    val device = FakeDeviceData()

    /** The instances whose kept state was dropped (another account, an unlink). */
    val forgotten = mutableListOf<String>()

    /** The instances whose store files were deleted. */
    val wiped = mutableListOf<String>()
    val link = MutableStateFlow(if (linked) WaStoreLink(INSTANCE) else null)
    val opened = mutableListOf<String>()
    val closed = mutableListOf<Long>()
    val wakes = mutableListOf<Long>()
    var now = NOW
    private var ids = 0

    /** The download worker of the kit, when it was given a directory. */
    val downloads: WaDownloads? =
        mediaDir?.let { dir -> WaDownloads(scope, remote, dir, { now }, device, wake = { wakes += it }, io = io) }

    val offline =
        WaOffline(
            scope = scope,
            linkChanges = link,
            link = { link.value },
            open = { instance ->
                opened += instance
                WaSyncStore.open(instance, ops = storeOps, release = { closed += it })
            },
            remote = remote,
            now = { now },
            wake = { wakes += it },
            newId = { "c${++ids}" },
            replayGraceMs = graceMs,
            rekeys = rekeys,
            downloads = downloads,
            cleanup = { forgotten += it },
            wipe = { instance ->
                wiped += instance
                storeOps.wipe(WaSyncWipeRequest(instanceId = instance))
            },
        )

    fun connect() = remote.emit(WaRemoteEvent.Connected)

    /** A one-to-one chat row of the store. */
    fun chat(
        jid: String,
        name: String? = null,
        unread: Int = 0,
        group: Boolean = false,
        pushName: String? = null,
    ) {
        ops.chats +=
            WaSyncChat(
                jid = jid,
                name = name,
                pushName = pushName,
                isGroup = group,
                unread = unread,
                lastMs = 0,
                waiting = 0,
            )
    }

    /** Seeds a stored message with every field a test may want. */
    fun message(row: WaSyncMessage) {
        ops.messages.getOrPut(row.chat) { mutableListOf() } += row
    }

    companion object {
        const val INSTANCE = "inst1"
        const val NOW = 1_790_000_000_000L
        const val GRACE_MS = 100L
        const val ADA = "905550000001@s.whatsapp.net"
        const val MERT = "905550000002@s.whatsapp.net"
        const val GROUP = "120363000000000001@g.us"

        /** The LID of [ADA], as a gateway names her before it knows her number. */
        const val ADA_LID = "777000000000001@lid"

        fun text(
            chat: String,
            id: String,
            atMs: Long,
            text: String,
            fromMe: Boolean = false,
            state: WaSyncRowState = if (fromMe) WaSyncRowState.Sent else WaSyncRowState.Received,
            sender: String? = if (fromMe) null else chat,
            senderName: String? = null,
        ) = WaSyncMessage(
            id = id,
            chat = chat,
            sender = sender,
            senderName = senderName,
            fromMe = fromMe,
            timestampMs = atMs,
            kind = WaSyncKind.Text,
            text = text,
            reactions = emptyList(),
            state = state,
        )

        /** A kit whose store also does what Rust does with media ([MediaStoreOps]). */
        fun withMedia(
            scope: CoroutineScope,
            graceMs: Long = GRACE_MS,
            mediaDir: File? = null,
            io: CoroutineDispatcher = Dispatchers.Unconfined,
        ): Pair<WaTestKit, MediaStoreOps> {
            val ops = FakeWaSyncOps(INSTANCE)
            val media = MediaStoreOps(ops)
            return WaTestKit(scope, ops, storeOps = media, graceMs = graceMs, mediaDir = mediaDir, io = io) to media
        }

        /** A received (or sent) attachment as the store returns it. */
        fun media(
            chat: String,
            id: String,
            atMs: Long,
            kind: WaSyncKind,
            mime: String,
            size: Long,
            meta: MediaMeta = MediaMeta(),
            caption: String? = null,
            fromMe: Boolean = false,
            sender: String? = if (fromMe) null else chat,
        ) = WaSyncMessage(
            id = id,
            chat = chat,
            sender = sender,
            fromMe = fromMe,
            timestampMs = atMs,
            kind = kind,
            text = caption,
            mime = mime,
            size = size,
            reactions = emptyList(),
            state = if (fromMe) WaSyncRowState.Read else WaSyncRowState.Received,
            media = meta,
        )

        fun rich(
            chat: String,
            id: String,
            atMs: Long,
            text: String,
            fromMe: Boolean = false,
        ) = RichMessage(id, chat, if (fromMe) null else chat, fromMe, atMs, MessageBody.Text(text), emptyList())

        fun incoming(
            chat: String,
            id: String,
            atMs: Long,
            text: String,
        ) = GatewayEvent.Message(id, chat, chat, false, atMs, MessageBody.Text(text), emptyList())
    }
}

/**
 * Lets the hub's coroutines run (they live in the background scope, which `advanceUntilIdle` does not wait for) and a
 * second of virtual time pass: enough for the drain after a reconnect, not for the typing line to clear.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun TestScope.settle() {
    runCurrent()
    advanceTimeBy(SETTLE_MS)
    runCurrent()
}

private const val SETTLE_MS = 1_000L
