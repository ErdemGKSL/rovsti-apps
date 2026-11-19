package dev.rosty.core

import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.HandleAnswer
import dev.rosty.core.model.wa.HistoryStatus
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MediaOptions
import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIdParsed
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaSyncAccountChangedRequest
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncCacheSaved
import dev.rosty.core.model.wa.WaSyncCancelled
import dev.rosty.core.model.wa.WaSyncCatchUpRequest
import dev.rosty.core.model.wa.WaSyncCaughtUp
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.core.model.wa.WaSyncChatMediaRequest
import dev.rosty.core.model.wa.WaSyncChatsAnswer
import dev.rosty.core.model.wa.WaSyncChatsRequest
import dev.rosty.core.model.wa.WaSyncClientIdRequest
import dev.rosty.core.model.wa.WaSyncClosed
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncDue
import dev.rosty.core.model.wa.WaSyncDueRequest
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncEvictRequest
import dev.rosty.core.model.wa.WaSyncEvicted
import dev.rosty.core.model.wa.WaSyncEviction
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncFocusRequest
import dev.rosty.core.model.wa.WaSyncGalleryPage
import dev.rosty.core.model.wa.WaSyncHandleRequest
import dev.rosty.core.model.wa.WaSyncHistory
import dev.rosty.core.model.wa.WaSyncHistoryAnswer
import dev.rosty.core.model.wa.WaSyncHistoryReport
import dev.rosty.core.model.wa.WaSyncHistorySaved
import dev.rosty.core.model.wa.WaSyncHistoryState
import dev.rosty.core.model.wa.WaSyncIdentityApplied
import dev.rosty.core.model.wa.WaSyncItemAnswer
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncKindUsage
import dev.rosty.core.model.wa.WaSyncMarkPlayedRequest
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaCap
import dev.rosty.core.model.wa.WaSyncMediaCapRequest
import dev.rosty.core.model.wa.WaSyncMediaDecision
import dev.rosty.core.model.wa.WaSyncMediaDue
import dev.rosty.core.model.wa.WaSyncMediaDueRequest
import dev.rosty.core.model.wa.WaSyncMediaEnqueueRequest
import dev.rosty.core.model.wa.WaSyncMediaEntry
import dev.rosty.core.model.wa.WaSyncMediaKeepRequest
import dev.rosty.core.model.wa.WaSyncMediaKept
import dev.rosty.core.model.wa.WaSyncMediaPolicy
import dev.rosty.core.model.wa.WaSyncMediaPolicyRequest
import dev.rosty.core.model.wa.WaSyncMediaQueued
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMediaSettleRequest
import dev.rosty.core.model.wa.WaSyncMediaSettled
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncMediaStats
import dev.rosty.core.model.wa.WaSyncMediaStatsRequest
import dev.rosty.core.model.wa.WaSyncMediaViewedRequest
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncMessageAnswer
import dev.rosty.core.model.wa.WaSyncMessageRequest
import dev.rosty.core.model.wa.WaSyncMissing
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.model.wa.WaSyncNoteStatusRequest
import dev.rosty.core.model.wa.WaSyncNumbersAnswer
import dev.rosty.core.model.wa.WaSyncObserveRequest
import dev.rosty.core.model.wa.WaSyncOlderAnswer
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncOpenRequest
import dev.rosty.core.model.wa.WaSyncOpened
import dev.rosty.core.model.wa.WaSyncOutboxAnswer
import dev.rosty.core.model.wa.WaSyncOutboxEditRequest
import dev.rosty.core.model.wa.WaSyncOutboxItem
import dev.rosty.core.model.wa.WaSyncOutboxState
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncPlanRequest
import dev.rosty.core.model.wa.WaSyncPlayed
import dev.rosty.core.model.wa.WaSyncReconcileRequest
import dev.rosty.core.model.wa.WaSyncReconciled
import dev.rosty.core.model.wa.WaSyncRekeyed
import dev.rosty.core.model.wa.WaSyncRekeyedAnswer
import dev.rosty.core.model.wa.WaSyncRekeyedRequest
import dev.rosty.core.model.wa.WaSyncResolveRequest
import dev.rosty.core.model.wa.WaSyncResolved
import dev.rosty.core.model.wa.WaSyncRetried
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.model.wa.WaSyncStarredCursor
import dev.rosty.core.model.wa.WaSyncStarredPage
import dev.rosty.core.model.wa.WaSyncStarredRequest
import dev.rosty.core.model.wa.WaSyncStatusNoted
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest
import dev.rosty.core.model.wa.WaSyncUsage
import dev.rosty.core.model.wa.WaSyncViewed
import dev.rosty.core.model.wa.WaSyncWipeRequest
import dev.rosty.core.model.wa.WaSyncWiped
import dev.rosty.core.ops.WaSyncOps

/**
 * An in-memory [WaSyncOps] for view-model and screen tests (no native library): seed [chats] and [messages], read
 * them back through the facade, queue and settle sends. It keeps rows the way the real store shows them (newest
 * first, waiting sends on top; a star sets the flag at once, replaces a waiting star of the same message and is
 * undone when refused) but none of the sync rules (gaps, echo matching, edit history, backoff): those are tested in
 * Rust and against the real library (`WaSyncStoreNativeTest`).
 *
 * Identity is a scripted alias map: [merge] (or an `identity_mapped` event, a catch-up page, an observed group member
 * or profile) makes two ids one chat, reads under the old id follow it, and the step is reported as `rekeyed`. None
 * of the real rules (source ranks, number changes, own ids) are modelled.
 *
 * Media follows the store's rules: the auto-download table ([mediaPolicy]), the queue with its back-off and five
 * attempts, the cap with its exemptions (starred, pinned, saved, a file an unsent row sends, used in the last 24
 * hours) and the reconcile. A thread row carries the cached file and the state of its download, as in Rust. The
 * gateway epoch and the account wipe ([noteStatus], [accountChanged], a whole status handed to [identityObserve],
 * and in [applyEvents] a `hello`, a `resync`, a `state` and an `account_changed` event) follow the same conditions;
 * what is wiped is everything seeded here. A `history_synced` event and a status report keep [history].
 *
 * The media round follows the store too: a media send is refused with options the gateway would refuse, shows them
 * on its waiting row and keeps them on the sent message; view once downloads by a tap only, opens once
 * ([mediaViewed]) and is gone after; a played receipt is queued once per received voice note ([markPlayed]);
 * [chatMedia] pages the photos, videos, audio and documents of a chat both ways. Seed media rows with [putMedia].
 */
class FakeWaSyncOps(
    private val instanceId: String = "fake-instance",
) : WaSyncOps {
    private val lock = Any()

    /** The chat list as seeded, in the order to answer it. */
    val chats = mutableListOf<WaSyncChat>()

    /** Stored messages by chat, in any order; a thread page sorts them newest first. */
    val messages = mutableMapOf<String, MutableList<WaSyncMessage>>()

    /** The outbox, in the order written. */
    val queue = mutableListOf<WaSyncOutboxItem>()

    /** What [plan] answers. */
    var owed = WaSyncPlan(afterSeq = null, refreshChats = false, fetch = emptyList())

    /** The requests of every write, for assertions. */
    val writes = mutableListOf<Any>()

    /**
     * Ids that are another chat's by now: an old key (a LID) to the key its rows live under. Script a merge with
     * [merge]; an `identity_mapped` event and a catch-up page do the same.
     */
    val aliases = mutableMapOf<String, String>()

    /** Every key change so far, oldest first (`rekeyedSince`). */
    val rekeyed = mutableListOf<WaSyncRekeyed>()

    /** Stored thumbnails (base64) by chat and message id. */
    private val thumbs = mutableMapOf<Pair<String, String>, String>()

    /** Messages deleted for me (E6-WA R4): left out of the thread until a refusal brings them back. */
    private val hidden = mutableMapOf<Pair<String, String>, WaSyncMessage>()

    /** Media rows by chat and message id; a message without one reads as [WaSyncMediaState.None]. */
    private val files = mutableMapOf<Pair<String, String>, WaSyncMediaEntry>()

    /** When each media row was queued, and when it was handed out. */
    private val queuedMs = mutableMapOf<Pair<String, String>, Long>()
    private val attemptedMs = mutableMapOf<Pair<String, String>, Long>()

    /** The media cap in bytes; null: no limit. */
    var capBytes: Long? = DEFAULT_CAP_BYTES

    /** The gateway epoch, the account and the history progress as last noted. */
    var epoch: String? = null
    var accountEpoch: Int? = null
    var ownPn: String? = null
    var history: WaSyncHistory? = null

    /** The store was emptied on the word of a status; the announcement may still sweep it. */
    private var sweepPending = false
    private var rekeySeq = 0L
    private var seq: Long? = null

    /** The lazy sync, as the last `open` asked for it; [paged] are the chats paged this session. */
    var lazy = false
        private set
    val paged = mutableSetOf<String>()

    /** The chat on screen (`focus`). */
    var focused: String? = null
        private set

    /** What `wa_sync.open` was asked, in order. */
    val opens = mutableListOf<WaSyncOpenRequest>()

    /** Identity map saves into the vault cache (`cacheSave`). */
    var cacheSaves = 0
        private set

    /** The key the rows of [id] live under: the id without device, followed through [aliases]. */
    fun keyOf(id: String): String {
        var key = bare(id)
        repeat(aliases.size) { key = aliases[key] ?: return key }
        return key
    }

    /**
     * Makes [from] and [into] one chat under [into], the way a learned LID and number pair does: messages, the
     * chat row, queued sends and cached media move, and the step is reported by the next `applyEvents` (and by
     * `rekeyedSince`). Nothing happens when they are one chat already.
     */
    fun merge(
        from: String,
        into: String,
    ): WaSyncRekeyed? =
        synchronized(lock) {
            val (old, key) = keyOf(from) to keyOf(into)
            if (old == key) return@synchronized null
            aliases[old] = key
            messages.remove(old)?.forEach { store(it.copy(chat = key)) }
            // The author is rewritten in every chat (a group message of theirs too); the id it arrived under stays.
            for (rows in messages.values) {
                rows.replaceAll {
                    if (it.sender ==
                        old
                    ) {
                        it.copy(sender = key, wireSender = it.wireSender ?: old)
                    } else {
                        it
                    }
                }
            }
            val moved = chats.firstOrNull { it.jid == old }
            chats.removeAll { it.jid == old }
            if (moved != null && chats.none { it.jid == key }) chats += moved.copy(jid = key, wireJid = old)
            queue.replaceAll { if (it.chat == old) it.copy(chat = key) else it }
            for (file in thumbs.keys.filter { it.first == old }) {
                thumbs.remove(file)?.let { thumbs.putIfAbsent(key to file.second, it) }
            }
            // A message both chats hold keeps the row that has the file.
            for (file in files.keys.filter { it.first == old }) {
                val moved = files.remove(file)?.copy(chat = key) ?: continue
                val kept = files[key to file.second]
                if (kept == null || (kept.state != WaSyncMediaState.Ready && moved.state == WaSyncMediaState.Ready)) {
                    files[key to file.second] = moved
                }
            }
            rekeySeq += 1
            val step = WaSyncRekeyed(rekeySeq, old, key)
            rekeyed += step
            unreported += step
            step
        }

    private val unreported = mutableListOf<WaSyncRekeyed>()

    /** The id without device; text that is not a JID stays as it is. */
    private fun bare(id: String): String = WaIds.bare(id) ?: id

    private fun parsed(id: String): WaIdParsed? = WaIds.parse(id)

    /** Seeds one stored text message. */
    fun put(
        chat: String,
        id: String,
        timestampMs: Long,
        text: String,
        fromMe: Boolean = false,
        deleted: Boolean = false,
    ): WaSyncMessage {
        val row =
            WaSyncMessage(
                id = id,
                chat = chat,
                sender = if (fromMe) null else chat,
                fromMe = fromMe,
                timestampMs = timestampMs,
                kind = WaSyncKind.Text,
                text = text,
                reactions = emptyList(),
                state = if (fromMe) WaSyncRowState.Sent else WaSyncRowState.Received,
                deleted = deleted,
                deletedAt = if (deleted) timestampMs else null,
            )
        synchronized(lock) { store(row) }
        return row
    }

    /** Seeds one stored media message; [media] carries what the gateway said about it (view once, voice note). */
    fun putMedia(
        chat: String,
        id: String,
        timestampMs: Long,
        kind: WaSyncKind,
        media: MediaMeta? = null,
        fromMe: Boolean = false,
        size: Long = 1_000,
        sender: String? = if (fromMe) null else chat,
    ): WaSyncMessage {
        val row =
            WaSyncMessage(
                id = id,
                chat = chat,
                sender = sender,
                fromMe = fromMe,
                timestampMs = timestampMs,
                kind = kind,
                size = size,
                reactions = emptyList(),
                state = if (fromMe) WaSyncRowState.Sent else WaSyncRowState.Received,
                media = media,
            )
        synchronized(lock) { store(row) }
        return row
    }

    private fun store(row: WaSyncMessage) {
        val rows = messages.getOrPut(row.chat) { mutableListOf() }
        rows.removeAll { it.id == row.id }
        rows += row
    }

    private fun stored(message: RichMessage): WaSyncMessage {
        val body = message.body
        return WaSyncMessage(
            id = message.id,
            chat = message.chat,
            sender = message.sender,
            fromMe = message.fromMe,
            timestampMs = message.timestampMs,
            kind = if (body is MessageBody.Text) WaSyncKind.Text else WaSyncKind.Unsupported,
            text = (body as? MessageBody.Text)?.text,
            reactions = message.reactions,
            state = if (message.fromMe) WaSyncRowState.Sent else WaSyncRowState.Received,
            deleted = message.meta.deleted,
            deletedAt = message.meta.deletedAt,
            deletedBy = message.meta.deletedBy,
        )
    }

    /** What the bubble of a media send knows before the gateway describes the message; null: nothing to tell. */
    private fun mediaOf(action: WaSyncAction.SendMedia): MediaMeta? {
        val sized = action.media in setOf(MediaKind.Image, MediaKind.Video, MediaKind.Sticker)
        val audio = action.media == MediaKind.Audio
        val meta =
            MediaMeta(
                fileName = action.fileName.takeIf { action.media == MediaKind.Document },
                seconds = action.seconds.takeIf { audio || action.media == MediaKind.Video },
                width = action.width.takeIf { sized },
                height = action.height.takeIf { sized },
                voiceNote = action.voiceNote && audio,
                animated = action.animated && action.media in setOf(MediaKind.Video, MediaKind.Sticker),
                viewOnce = action.viewOnce,
                waveformB64 = action.waveformB64.takeIf { audio },
            )
        return meta.takeIf { it != MediaMeta() }
    }

    private fun kindOf(media: MediaKind): WaSyncKind =
        when (media) {
            MediaKind.Image -> WaSyncKind.Image
            MediaKind.Video -> WaSyncKind.Video
            MediaKind.Audio -> WaSyncKind.Audio
            MediaKind.Document -> WaSyncKind.Document
            MediaKind.Sticker -> WaSyncKind.Sticker
        }

    /** Why the gateway would refuse the options of a media send; null: it would not. */
    private fun refusal(action: WaSyncAction.SendMedia): String? {
        val decoder = java.util.Base64.getDecoder()
        action.thumbnailB64?.takeIf { it.isNotEmpty() }?.let { text ->
            val bytes =
                runCatching { decoder.decode(text) }.getOrNull() ?: return "thumbnail_b64 is not valid base64"
            if (bytes.size > MediaOptions.MAX_THUMBNAIL_BYTES) {
                return "the thumbnail is ${bytes.size} bytes, the limit is ${MediaOptions.MAX_THUMBNAIL_BYTES}"
            }
            val jpeg =
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            if (!jpeg) return "the thumbnail is not a JPEG image"
        }
        action.waveformB64?.takeIf { it.isNotEmpty() }?.let { text ->
            val bytes =
                runCatching { decoder.decode(text) }.getOrNull() ?: return "waveform_b64 is not valid base64"
            if (bytes.size != MediaWaveform.SAMPLES) {
                return "the waveform has ${bytes.size} samples, it must have exactly ${MediaWaveform.SAMPLES}"
            }
            if (!MediaWaveform.valid(bytes)) return "a waveform sample is above ${MediaWaveform.MAX}"
        }
        val once = action.media in setOf(MediaKind.Image, MediaKind.Video, MediaKind.Audio)
        if (action.viewOnce && !once) return "only a photo, a video or a voice message can be sent as view once"
        return null
    }

    private fun waiting(item: WaSyncOutboxItem): WaSyncMessage? {
        val action = item.action
        val text =
            when (action) {
                is WaSyncAction.SendText -> action.text
                is WaSyncAction.SendMedia -> action.caption
                is WaSyncAction.Forward -> action.snapshot?.text
                else -> return null
            }
        val media = action as? WaSyncAction.SendMedia
        val forward = (action as? WaSyncAction.Forward)?.snapshot
        val sendText = action as? WaSyncAction.SendText
        return WaSyncMessage(
            id = item.clientId,
            chat = item.chat,
            fromMe = true,
            timestampMs = item.createdMs,
            kind = media?.let { kindOf(it.media) } ?: forward?.kind ?: WaSyncKind.Text,
            text = text,
            mime = media?.mime ?: forward?.mime,
            size = media?.size ?: forward?.size,
            media = media?.let(::mediaOf) ?: forward?.media,
            mediaPath = media?.file,
            forwarded = forward != null,
            mentions = sendText?.mentions.orEmpty(),
            linkPreview =
                sendText?.linkPreview?.let {
                    dev.rosty.core.model.wa
                        .LinkPreview(it.url, it.title, it.description, it.thumbnailB64 != null)
                },
            reactions = emptyList(),
            state =
                when (item.state) {
                    WaSyncOutboxState.Waiting -> WaSyncRowState.Waiting
                    WaSyncOutboxState.Sending -> WaSyncRowState.Sending
                    WaSyncOutboxState.Failed -> WaSyncRowState.Failed
                },
            clientId = item.clientId,
            error = item.lastError,
        )
    }

    private fun setStarred(
        chat: String,
        id: String,
        starred: Boolean,
    ) {
        messages[chat]?.replaceAll { if (it.id == id) it.copy(starred = starred) else it }
    }

    private fun <T> ok(value: T): Outcome<T> = Outcome.Ok(value)

    /** A stored row with what the media rows say about it: the thumbnail, the cached file, the download state. */
    private fun withMedia(row: WaSyncMessage): WaSyncMessage {
        val key = row.chat to row.id
        val entry = files[key]
        if (entry == null && key !in thumbs) return row
        return row.copy(
            hasThumbnail = row.hasThumbnail || key in thumbs,
            mediaPath = entry?.path ?: row.mediaPath,
            mediaState = entry?.state?.takeIf { it != WaSyncMediaState.None } ?: row.mediaState,
        )
    }

    private fun row(
        chat: String,
        id: String,
    ): WaSyncMessage? = messages[chat].orEmpty().firstOrNull { it.id == id }

    private fun missing(
        chat: String,
        id: String,
    ) = Outcome.Err(RustError.NotFound("message $id of $chat"))

    /** The media row of a message; one that was never asked for reads as `none`. */
    private fun entryOf(row: WaSyncMessage): WaSyncMediaEntry =
        files[row.chat to row.id]
            ?: WaSyncMediaEntry(row.chat, row.id, row.kind, row.mime, row.size, WaSyncMediaState.None)

    /** The owner's auto-download rules, as `rosty_app_core::wa_sync::media::policy` has them. */
    private fun decide(
        row: WaSyncMessage,
        network: WaSyncNetwork,
        freeBytes: Long?,
    ): WaSyncMediaDecision {
        val meta: MediaMeta? = row.media
        val voice = row.kind == WaSyncKind.Audio && meta?.voiceNote == true
        val auto =
            when {
                row.kind !in MEDIA_KINDS || meta?.viewOnce == true -> return WaSyncMediaDecision.Never
                network == WaSyncNetwork.Wifi -> row.kind == WaSyncKind.Image || row.kind == WaSyncKind.Sticker || voice
                network == WaSyncNetwork.Mobile -> row.kind == WaSyncKind.Image || voice
                else -> false
            }
        // A message that was opened once has no file any more, whatever the rules say.
        if (row.viewed) return WaSyncMediaDecision.Never
        val room = freeBytes == null || freeBytes - (row.size ?: 0) >= FREE_SPACE_FLOOR
        return if (auto && room) WaSyncMediaDecision.Auto else WaSyncMediaDecision.OnTap
    }

    /** Files the cap never takes. */
    private fun exempt(
        entry: WaSyncMediaEntry,
        nowMs: Long?,
    ): Boolean =
        entry.pinned || entry.savedUri != null || row(entry.chat, entry.id)?.starred == true ||
            queue.any { (it.action as? WaSyncAction.SendMedia)?.file == entry.path } ||
            (nowMs != null && (entry.usedMs ?: 0) > nowMs - RECENT_USE_MS)

    private fun cached(): List<WaSyncMediaEntry> =
        files.values
            .filter { it.state == WaSyncMediaState.Ready && it.path != null }
            .sortedWith(compareBy<WaSyncMediaEntry> { it.usedMs ?: 0 }.thenBy { it.chat }.thenBy { it.id })

    /** Everything that belongs to the linked account goes; the answer names the media files. */
    private fun wipeAccount(keepOutbox: Boolean): List<String> {
        val sending = if (keepOutbox) emptyList() else queue.mapNotNull { (it.action as? WaSyncAction.SendMedia)?.file }
        val gone = (files.values.mapNotNull { it.path } + sending).distinct()
        chats.clear()
        messages.clear()
        if (!keepOutbox) queue.clear()
        thumbs.clear()
        files.clear()
        queuedMs.clear()
        attemptedMs.clear()
        aliases.clear()
        rekeyed.clear()
        unreported.clear()
        history = null
        ownPn = null
        owed = owed.copy(refreshChats = true, fetch = emptyList(), identitySince = null)
        return gone
    }

    override suspend fun open(req: WaSyncOpenRequest): Outcome<WaSyncOpened> =
        synchronized(lock) {
            opens += req
            lazy = req.lazy
            ok(WaSyncOpened(HANDLE, instanceId, SCHEMA_VERSION, seq, owed.refreshChats, lazy = req.lazy))
        }

    override suspend fun openPage(req: WaSyncOlderRequest): Outcome<WaSyncOlderAnswer> =
        synchronized(lock) {
            val chat = keyOf(req.chat)
            val fetch = if (lazy && chat !in paged) WaSyncFetch(chat, null, req.limit ?: PAGE) else null
            ok(WaSyncOlderAnswer(fetch))
        }

    override suspend fun focus(req: WaSyncFocusRequest): Outcome<HandleAnswer> =
        synchronized(lock) {
            focused = req.chat?.let(::keyOf)
            ok(HandleAnswer(HANDLE))
        }

    override suspend fun cacheSave(req: WaSyncHandleRequest): Outcome<WaSyncCacheSaved> =
        synchronized(lock) {
            cacheSaves++
            ok(WaSyncCacheSaved(HANDLE, 0))
        }

    /** History snapshots written into the vault's history file (`historySave`). */
    var historySaves = 0

    override suspend fun historySave(req: WaSyncHandleRequest): Outcome<WaSyncHistorySaved> =
        synchronized(lock) {
            historySaves++
            ok(WaSyncHistorySaved(HANDLE, 0, 0))
        }

    override suspend fun close(req: WaSyncHandleRequest) = ok(WaSyncClosed(true))

    override suspend fun plan(req: WaSyncPlanRequest) = ok(owed)

    override suspend fun applyEvents(req: WaSyncEventsRequest): Outcome<WaSyncApplied> =
        synchronized(lock) {
            writes += req
            val changed = linkedSetOf<String>()
            var accountChanged = false
            var resync = false
            val deleteFiles = mutableListOf<String>()
            for (envelope in req.events) {
                val event = envelope.event
                if (event is GatewayEvent.Hello) {
                    // The first frame of a socket: another epoch is a gap, and its `seq` is never taken.
                    resync = noteEpoch(event.epoch) || resync
                    continue
                }
                if (event is GatewayEvent.Resync) {
                    noteEpoch(event.epoch)
                    sweepPending = false
                    resync = true
                }
                val told =
                    when (event) {
                        is GatewayEvent.State -> {
                            val status = event.status()
                            noted(
                                StatusTold(status.epoch, status.accountEpoch, status.ownJid, envelope.atMs),
                                status.history?.let(::report),
                                fromEvent = true,
                            )
                        }

                        is GatewayEvent.AccountChanged -> {
                            announced(event.oldPn, event.newPn)
                        }

                        else -> {
                            null
                        }
                    }
                if (told != null) {
                    resync = resync || told.gap || told.changed
                    if (told.changed) {
                        accountChanged = true
                        deleteFiles += told.deleteFiles
                        changed.clear()
                    }
                }
                seq = envelope.seq
                if (event is GatewayEvent.HistorySynced) {
                    historySynced(event, envelope.atMs)
                    resync = true
                }
                val mapped = event as? GatewayEvent.IdentityMapped
                if (mapped != null) merge(mapped.lid, mapped.pn)?.let { changed += it.intoKey }
                val message = event as? GatewayEvent.Message ?: continue
                val chat = keyOf(message.chat)
                store(
                    stored(
                        RichMessage(
                            message.id,
                            chat,
                            message.sender?.let(::keyOf),
                            message.fromMe,
                            message.timestampMs,
                            message.body,
                            message.reactions,
                        ),
                    ),
                )
                changed += chat
            }
            val steps = unreported.toList()
            unreported.clear()
            ok(
                WaSyncApplied(
                    HANDLE,
                    seq,
                    req.events.size,
                    resync = steps.isNotEmpty() || resync,
                    chats = changed.toList(),
                    reconciled = emptyList(),
                    rekeyed = steps,
                    accountChanged = accountChanged,
                    deleteFiles = deleteFiles,
                ),
            )
        }

    override suspend fun storeChats(req: WaSyncStoreChatsRequest): Outcome<WaSyncStoredChats> =
        synchronized(lock) {
            writes += req
            owed = owed.copy(refreshChats = false)
            ok(WaSyncStoredChats(HANDLE, req.chats.size, emptyList()))
        }

    override suspend fun storePage(req: WaSyncStorePageRequest): Outcome<WaSyncStoredPage> =
        synchronized(lock) {
            writes += req
            req.messages.forEach { store(stored(it)) }
            val short = req.messages.size < req.limit
            if (lazy && req.before == null) paged += keyOf(req.chat)
            if (short) started += keyOf(req.chat)
            ok(
                WaSyncStoredPage(
                    HANDLE,
                    req.messages.size,
                    req.messages.size,
                    null,
                    req.messages.size < req.limit,
                    emptyList(),
                ),
            )
        }

    /** Chats whose history reached its start (a short page). */
    private val started = mutableSetOf<String>()

    /**
     * A lazy store answers the page before the oldest stored message of a paged chat; the eager fake has no older
     * history (tests script it).
     */
    override suspend fun older(req: WaSyncOlderRequest): Outcome<WaSyncOlderAnswer> =
        synchronized(lock) {
            val chat = keyOf(req.chat)
            if (!lazy || chat !in paged || chat in started) return@synchronized ok(WaSyncOlderAnswer(null))
            val oldest = messages[chat].orEmpty().minOfOrNull { it.timestampMs }
            ok(WaSyncOlderAnswer(WaSyncFetch(chat, oldest?.plus(1), req.limit ?: PAGE)))
        }

    override suspend fun chats(req: WaSyncChatsRequest): Outcome<WaSyncChatsAnswer> =
        synchronized(lock) { ok(WaSyncChatsAnswer(chats.filter { req.includeArchived || !it.archived })) }

    override suspend fun thread(req: WaSyncThreadRequest): Outcome<WaSyncThreadPage> =
        synchronized(lock) {
            val before = req.before
            // A reader that still holds an old key reads the chat its rows moved to.
            val chat = keyOf(req.chat)
            val all =
                messages[chat]
                    .orEmpty()
                    .sortedWith(compareByDescending<WaSyncMessage> { it.timestampMs }.thenByDescending { it.id })
                    .filter {
                        before == null || it.timestampMs < before.timestampMs ||
                            (it.timestampMs == before.timestampMs && it.id < before.id)
                    }
            val page = all.take(req.limit)
            val pending =
                if (before ==
                    null
                ) {
                    queue.filter { it.chat == chat }.mapNotNull(::waiting).reversed()
                } else {
                    emptyList()
                }
            val next = page.lastOrNull()?.takeIf { all.size > page.size }?.let { WaSyncCursor(it.timestampMs, it.id) }
            ok(WaSyncThreadPage(pending + page.map(::withMedia), next, reachedStart = true, syncing = false))
        }

    override suspend fun message(req: WaSyncMessageRequest): Outcome<WaSyncMessageAnswer> =
        synchronized(lock) {
            ok(WaSyncMessageAnswer(row(keyOf(req.chat), req.id)?.let(::withMedia)))
        }

    override suspend fun starred(req: WaSyncStarredRequest): Outcome<WaSyncStarredPage> =
        synchronized(lock) {
            val before = req.before
            val order =
                compareByDescending<WaSyncMessage> { it.timestampMs }
                    .thenByDescending { it.chat }
                    .thenByDescending { it.id }
            val all =
                messages.values
                    .flatten()
                    .filter { it.starred && !it.deleted }
                    .sortedWith(order)
                    .filter {
                        before == null || it.timestampMs < before.timestampMs ||
                            (
                                it.timestampMs == before.timestampMs &&
                                    (it.chat < before.chat || (it.chat == before.chat && it.id < before.id))
                            )
                    }
            val page = all.take(req.limit)
            val next =
                page
                    .lastOrNull()
                    ?.takeIf { all.size > page.size }
                    ?.let { WaSyncStarredCursor(it.timestampMs, it.chat, it.id) }
            ok(WaSyncStarredPage(page.map(::withMedia), next))
        }

    override suspend fun outboxEnqueue(req: WaSyncEnqueueRequest): Outcome<WaSyncItemAnswer> =
        synchronized(lock) {
            writes += req
            if (req.action is WaSyncAction.MarkPlayed) {
                return@synchronized Outcome.Err(RustError.Invalid("a played receipt is queued with mark_played"))
            }
            if (req.clientId.isEmpty() || req.chat.isEmpty()) {
                return@synchronized Outcome.Err(RustError.Invalid("client id and chat must not be empty"))
            }
            (req.action as? WaSyncAction.SendMedia)?.let(::refusal)?.let {
                return@synchronized Outcome.Err(RustError.Invalid(it))
            }
            val action =
                when (val asked = req.action) {
                    // The store copies what a forward sends; a deleted or unknown message is refused.
                    is WaSyncAction.Forward -> {
                        val source =
                            messages[keyOf(asked.sourceChat)]?.firstOrNull { it.id == asked.messageId }
                                ?: return@synchronized Outcome.Err(RustError.NotFound("message ${asked.messageId}"))
                        if (source.deleted || source.media?.viewOnce == true || source.system != null) {
                            return@synchronized Outcome.Err(RustError.Invalid("this message cannot be forwarded"))
                        }
                        asked.copy(
                            snapshot =
                                dev.rosty.core.model.wa.WaSyncForwardSnapshot(
                                    source.kind,
                                    source.text,
                                    source.mime,
                                    source.size,
                                    source.media,
                                ),
                        )
                    }

                    is WaSyncAction.DeleteForMe -> {
                        val rows = messages[req.chat]
                        rows?.firstOrNull { it.id == asked.messageId }?.let { row ->
                            hidden[req.chat to row.id] = row
                            rows.remove(row)
                        }
                        asked
                    }

                    else -> {
                        asked
                    }
                }
            val star = req.action as? WaSyncAction.Star
            if (star != null && queue.none { it.clientId == req.clientId }) {
                queue.removeAll {
                    it.chat == req.chat && it.state != WaSyncOutboxState.Sending &&
                        (it.action as? WaSyncAction.Star)?.messageId == star.messageId
                }
                setStarred(req.chat, star.messageId, star.starred)
            }
            val item =
                queue.firstOrNull { it.clientId == req.clientId }
                    ?: WaSyncOutboxItem(
                        req.clientId,
                        req.chat,
                        action,
                        req.nowMs,
                        0,
                        0,
                        WaSyncOutboxState.Waiting,
                    ).also {
                        queue +=
                            it
                    }
            ok(WaSyncItemAnswer(HANDLE, item))
        }

    override suspend fun outboxDue(req: WaSyncDueRequest): Outcome<WaSyncDue> =
        synchronized(lock) {
            val heads =
                queue
                    .filter { it.state != WaSyncOutboxState.Failed }
                    .groupBy { it.chat }
                    .values
                    .map { it.first() }
                    .filter { it.state == WaSyncOutboxState.Waiting }
            val sending = heads.map { it.copy(state = WaSyncOutboxState.Sending, attempts = it.attempts + 1) }
            sending.forEach { now -> queue.replaceAll { if (it.clientId == now.clientId) now else it } }
            ok(WaSyncDue(HANDLE, sending, held = 0))
        }

    override suspend fun outboxSettle(req: WaSyncSettleRequest): Outcome<WaSyncSettled> =
        synchronized(lock) {
            writes += req
            val item =
                queue.firstOrNull { it.clientId == req.clientId }
                    ?: return@synchronized ok(WaSyncSettled(HANDLE, WaSyncSettled.DONE))
            when (val outcome = req.outcome) {
                is WaSyncOutcome.Sent -> {
                    queue.remove(item)
                    val sent = waiting(item)
                    val id = outcome.messageId
                    if (sent != null && id != null) {
                        val media = item.action as? WaSyncAction.SendMedia
                        // The preview is the thumbnail of the sent message; a view-once file is not kept.
                        media?.thumbnailB64?.let { thumbs.putIfAbsent(item.chat to id, it) }
                        store(
                            sent.copy(
                                id = id,
                                timestampMs = outcome.timestampMs ?: req.nowMs,
                                state = WaSyncRowState.Sent,
                                mediaPath = sent.mediaPath.takeIf { media?.viewOnce != true },
                            ),
                        )
                    }
                    ok(WaSyncSettled(HANDLE, WaSyncSettled.DONE, messageId = id))
                }

                is WaSyncOutcome.Error -> {
                    val failed = outcome.error == dev.rosty.core.model.wa.WaSyncErrorKind.Rejected
                    if (failed && item.action is WaSyncAction.MarkPlayed) {
                        // Dropped: nothing to retry or edit, and the message stays played.
                        queue.remove(item)
                        return@synchronized ok(WaSyncSettled(HANDLE, WaSyncSettled.FAILED))
                    }
                    val deleteForMe = item.action as? WaSyncAction.DeleteForMe
                    if (failed && deleteForMe != null) {
                        // Refused: the message shows again.
                        queue.remove(item)
                        hidden.remove(item.chat to deleteForMe.messageId)?.let(::store)
                        return@synchronized ok(WaSyncSettled(HANDLE, WaSyncSettled.FAILED))
                    }
                    val star = item.action as? WaSyncAction.Star
                    if (failed && star != null) {
                        queue.remove(item)
                        val newer =
                            queue.any {
                                it.chat == item.chat && (it.action as? WaSyncAction.Star)?.messageId == star.messageId
                            }
                        if (!newer) setStarred(item.chat, star.messageId, !star.starred)
                        return@synchronized ok(WaSyncSettled(HANDLE, WaSyncSettled.FAILED))
                    }
                    val state = if (failed) WaSyncOutboxState.Failed else WaSyncOutboxState.Waiting
                    queue.replaceAll {
                        if (it.clientId ==
                            item.clientId
                        ) {
                            it.copy(state = state, lastError = outcome.message)
                        } else {
                            it
                        }
                    }
                    if (failed) {
                        ok(
                            WaSyncSettled(HANDLE, WaSyncSettled.FAILED),
                        )
                    } else {
                        ok(WaSyncSettled(HANDLE, WaSyncSettled.WAITING, nextAttemptMs = req.nowMs))
                    }
                }
            }
        }

    override suspend fun outboxCancel(req: WaSyncClientIdRequest): Outcome<WaSyncCancelled> =
        synchronized(lock) {
            writes += req
            ok(
                WaSyncCancelled(
                    queue.removeAll { it.clientId == req.clientId && it.state != WaSyncOutboxState.Sending },
                ),
            )
        }

    override suspend fun outboxEdit(req: WaSyncOutboxEditRequest): Outcome<WaSyncItemAnswer> =
        synchronized(lock) {
            writes += req
            val item =
                queue.firstOrNull { it.clientId == req.clientId }
                    ?: return@synchronized Outcome.Err(RustError.NotFound("outbox row ${req.clientId}"))
            val action =
                when (val action = item.action) {
                    is WaSyncAction.SendText -> action.copy(text = req.text)
                    is WaSyncAction.SendMedia -> action.copy(caption = req.text.ifEmpty { null })
                    else -> return@synchronized Outcome.Err(RustError.Invalid("only a message text can be edited"))
                }
            val edited = item.copy(action = action, state = WaSyncOutboxState.Waiting, lastError = null)
            queue.replaceAll { if (it.clientId == edited.clientId) edited else it }
            ok(WaSyncItemAnswer(HANDLE, edited))
        }

    override suspend fun outboxRetry(req: WaSyncClientIdRequest): Outcome<WaSyncRetried> =
        synchronized(lock) {
            writes += req
            val found = queue.any { it.clientId == req.clientId }
            queue.replaceAll { if (it.clientId == req.clientId) it.copy(state = WaSyncOutboxState.Waiting) else it }
            ok(WaSyncRetried(found))
        }

    override suspend fun outbox(req: WaSyncHandleRequest): Outcome<WaSyncOutboxAnswer> =
        synchronized(lock) {
            ok(WaSyncOutboxAnswer(queue.toList()))
        }

    override suspend fun mediaSet(req: WaSyncMediaSetRequest): Outcome<HandleAnswer> =
        synchronized(lock) {
            writes += req
            val key = keyOf(req.chat) to req.id
            req.thumbnailB64?.let { thumbs[key] = it }
            val path = req.path
            if (path != null) {
                val row = row(key.first, key.second)
                val had =
                    files[key]
                        ?: WaSyncMediaEntry(
                            key.first,
                            key.second,
                            row?.kind ?: WaSyncKind.Unsupported,
                            row?.mime,
                            row?.size,
                            WaSyncMediaState.None,
                        )
                files[key] =
                    had.copy(
                        state = WaSyncMediaState.Ready,
                        path = path,
                        bytes = req.bytes,
                        usedMs = req.nowMs,
                        attempts = 0,
                        nextAttemptMs = null,
                        lastError = null,
                    )
            }
            ok(HandleAnswer(HANDLE))
        }

    /** Unlike the real store, a message that was never seeded answers an empty row instead of "not found". */
    override suspend fun media(req: WaSyncMediaRequest): Outcome<WaSyncMedia> =
        synchronized(lock) {
            val key = keyOf(req.chat) to req.id
            val usedMs = req.usedMs
            val entry = files[key]
            if (usedMs != null && entry?.state == WaSyncMediaState.Ready) files[key] = entry.copy(usedMs = usedMs)
            val now = files[key]
            ok(
                WaSyncMedia(
                    thumbs[key],
                    now?.path,
                    now?.bytes,
                    now?.state ?: WaSyncMediaState.None,
                    now?.sizeExpected ?: row(key.first, key.second)?.size,
                    now?.attempts ?: 0,
                    now?.pinned ?: false,
                    now?.savedUri,
                    now?.lastError,
                ),
            )
        }

    override suspend fun mediaEvict(req: WaSyncEvictRequest): Outcome<WaSyncEviction> =
        synchronized(lock) {
            val limit = req.maxBytes ?: capBytes
            val ready = cached()
            var left = ready.sumOf { it.bytes ?: 0 }
            val spared = ready.filter { exempt(it, req.nowMs) }
            val evicted = mutableListOf<WaSyncEvicted>()
            for (file in ready - spared.toSet()) {
                if (limit == null || left <= limit) break
                files[file.chat to file.id] =
                    file.copy(state = WaSyncMediaState.Evicted, path = null, bytes = null, sha256 = null, usedMs = null)
                left -= file.bytes ?: 0
                evicted += WaSyncEvicted(file.chat, file.id, file.path.orEmpty(), file.bytes ?: 0)
            }
            ok(WaSyncEviction(evicted, left, spared.sumOf { it.bytes ?: 0 }))
        }

    override suspend fun mediaPolicy(req: WaSyncMediaPolicyRequest): Outcome<WaSyncMediaPolicy> =
        synchronized(lock) {
            val chat = keyOf(req.chat)
            val row = row(chat, req.id) ?: return@synchronized missing(chat, req.id)
            ok(WaSyncMediaPolicy(decide(row, req.network, req.freeBytes)))
        }

    override suspend fun mediaEnqueue(req: WaSyncMediaEnqueueRequest): Outcome<WaSyncMediaQueued> =
        synchronized(lock) {
            writes += req
            val chat = keyOf(req.chat)
            val row = row(chat, req.id) ?: return@synchronized missing(chat, req.id)
            val network = req.network
            if (!req.tap && network == null) {
                return@synchronized Outcome.Err(RustError.Invalid("an automatic request names the network"))
            }
            val once = row.media?.viewOnce == true
            val decision =
                when {
                    row.viewed -> WaSyncMediaDecision.Never

                    network != null && !req.tap -> decide(row, network, req.freeBytes)

                    row.kind !in MEDIA_KINDS -> WaSyncMediaDecision.Never

                    // The tap that opens a received view-once message fetches it; its sender cannot open it.
                    once && row.fromMe -> WaSyncMediaDecision.Never

                    else -> WaSyncMediaDecision.OnTap
                }
            val key = chat to req.id
            val entry = entryOf(row)
            val wanted =
                when (decision) {
                    WaSyncMediaDecision.Never -> false
                    WaSyncMediaDecision.OnTap -> req.tap
                    WaSyncMediaDecision.Auto -> !row.deleted
                }
            val fresh =
                when (entry.state) {
                    WaSyncMediaState.None, WaSyncMediaState.Evicted -> true
                    WaSyncMediaState.Failed, WaSyncMediaState.Gone -> req.tap
                    else -> false
                }
            if (wanted && fresh) {
                files[key] =
                    entry.copy(
                        state = WaSyncMediaState.Queued,
                        byTap = req.tap,
                        attempts = 0,
                        nextAttemptMs = null,
                        lastError = null,
                        path = null,
                        bytes = null,
                        sha256 = null,
                    )
                queuedMs[key] = req.nowMs
            } else if (wanted && req.tap && entry.state == WaSyncMediaState.Queued) {
                files[key] = entry.copy(byTap = true, nextAttemptMs = null)
            }
            val now = files[key] ?: entry
            val queued = now.state == WaSyncMediaState.Queued || now.state == WaSyncMediaState.Downloading
            ok(WaSyncMediaQueued(HANDLE, queued, decision, now))
        }

    override suspend fun mediaDue(req: WaSyncMediaDueRequest): Outcome<WaSyncMediaDue> =
        synchronized(lock) {
            val limit = (req.limit ?: DEFAULT_DUE).coerceIn(1, MAX_DUE)
            val network = req.network
            val waiting =
                files
                    .filter { (key, entry) ->
                        (entry.state == WaSyncMediaState.Queued && (entry.nextAttemptMs ?: 0) <= req.nowMs) ||
                            (
                                entry.state == WaSyncMediaState.Downloading &&
                                    (attemptedMs[key] ?: 0) <= req.nowMs - DOWNLOAD_LEASE_MS
                            )
                    }.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<Pair<String, String>, WaSyncMediaEntry>> { it.value.byTap }
                            .thenBy { queuedMs[it.key] ?: 0 }
                            .thenBy { it.key.first }
                            .thenBy { it.key.second },
                    )
            val items = mutableListOf<WaSyncMediaEntry>()
            var held = 0
            for ((key, entry) in waiting) {
                val row = row(key.first, key.second)
                val allowed =
                    entry.byTap || network == null ||
                        (row != null && decide(row, network, req.freeBytes) == WaSyncMediaDecision.Auto)
                if (!allowed) {
                    held += 1
                    continue
                }
                if (items.size == limit) continue
                val out = entry.copy(state = WaSyncMediaState.Downloading)
                files[key] = out
                attemptedMs[key] = req.nowMs
                items += out
            }
            val wakes =
                files.mapNotNull { (key, entry) ->
                    when (entry.state) {
                        WaSyncMediaState.Queued -> entry.nextAttemptMs?.takeIf { it > req.nowMs }
                        WaSyncMediaState.Downloading -> (attemptedMs[key] ?: 0) + DOWNLOAD_LEASE_MS
                        else -> null
                    }
                }
            ok(WaSyncMediaDue(HANDLE, items, held, wakes.minOrNull()))
        }

    override suspend fun mediaSettle(req: WaSyncMediaSettleRequest): Outcome<WaSyncMediaSettled> =
        synchronized(lock) {
            writes += req
            val chat = keyOf(req.chat)
            val row = row(chat, req.id) ?: return@synchronized missing(chat, req.id)
            val key = chat to req.id
            val entry = entryOf(row)
            val ready = entry.state == WaSyncMediaState.Ready
            var replaced: String? = null
            attemptedMs.remove(key)
            val outcome = req.outcome
            if (outcome is WaSyncFetched.Ready && row.viewed) {
                // Opened meanwhile: the file is not recorded, the caller deletes it.
                return@synchronized ok(WaSyncMediaSettled(HANDLE, files[key] ?: entry, outcome.path))
            }
            when (outcome) {
                is WaSyncFetched.Ready -> {
                    replaced = entry.path?.takeIf { it != outcome.path }
                    files[key] =
                        entry.copy(
                            state = WaSyncMediaState.Ready,
                            path = outcome.path,
                            bytes = outcome.bytes,
                            sha256 = outcome.sha256,
                            usedMs = req.nowMs,
                            attempts = 0,
                            nextAttemptMs = null,
                            lastError = null,
                        )
                }

                is WaSyncFetched.Failed -> {
                    if (!ready) {
                        val attempts = entry.attempts + 1
                        val failed = attempts >= MAX_ATTEMPTS
                        files[key] =
                            entry.copy(
                                state = if (failed) WaSyncMediaState.Failed else WaSyncMediaState.Queued,
                                attempts = attempts,
                                nextAttemptMs = if (failed) null else req.nowMs + backoffMs(attempts),
                                lastError = outcome.error,
                            )
                    }
                }

                is WaSyncFetched.Gone -> {
                    if (!ready) {
                        files[key] =
                            entry.copy(state = WaSyncMediaState.Gone, nextAttemptMs = null, lastError = outcome.error)
                    }
                }

                WaSyncFetched.Cancelled -> {
                    if (entry.state in CANCELLABLE) {
                        files[key] =
                            entry.copy(
                                state = WaSyncMediaState.None,
                                byTap = false,
                                attempts = 0,
                                nextAttemptMs = null,
                                lastError = null,
                            )
                    }
                }
            }
            ok(WaSyncMediaSettled(HANDLE, files[key] ?: entry, replaced))
        }

    override suspend fun mediaKeep(req: WaSyncMediaKeepRequest): Outcome<WaSyncMediaKept> =
        synchronized(lock) {
            writes += req
            val chat = keyOf(req.chat)
            val row = row(chat, req.id) ?: return@synchronized missing(chat, req.id)
            var entry = entryOf(row)
            req.pinned?.let { entry = entry.copy(pinned = it) }
            req.savedUri?.let { entry = entry.copy(savedUri = it.ifEmpty { null }) }
            files[chat to req.id] = entry
            ok(WaSyncMediaKept(HANDLE, entry))
        }

    override suspend fun mediaReconcile(req: WaSyncReconcileRequest): Outcome<WaSyncReconciled> =
        synchronized(lock) {
            writes += req
            val cut = files.filterValues { it.state == WaSyncMediaState.Downloading }
            for ((key, entry) in cut) files[key] = entry.copy(state = WaSyncMediaState.Queued)
            attemptedMs.clear()
            val listed =
                req.files ?: return@synchronized ok(WaSyncReconciled(HANDLE, cut.size, emptyList(), emptyList()))
            val named = queue.mapNotNull { (it.action as? WaSyncAction.SendMedia)?.file }.toMutableSet()
            val missing = mutableListOf<WaSyncMissing>()
            for (entry in cached().sortedWith(compareBy<WaSyncMediaEntry> { it.chat }.thenBy { it.id })) {
                val path = entry.path ?: continue
                if (path in listed) {
                    named += path
                } else {
                    files[entry.chat to entry.id] =
                        entry.copy(
                            state = WaSyncMediaState.Evicted,
                            path = null,
                            bytes = null,
                            sha256 = null,
                            usedMs = null,
                        )
                    missing += WaSyncMissing(entry.chat, entry.id, path)
                }
            }
            ok(WaSyncReconciled(HANDLE, cut.size, missing, listed.filter { it.endsWith(".part") || it !in named }))
        }

    override suspend fun mediaStats(req: WaSyncMediaStatsRequest): Outcome<WaSyncMediaStats> =
        synchronized(lock) {
            val ready = cached()

            fun usage(of: List<WaSyncMediaEntry>) = WaSyncUsage(of.size, of.sumOf { it.bytes ?: 0 })
            val spared = usage(ready.filter { exempt(it, req.nowMs) })
            val paths = ready.mapNotNull { it.path }.toSet()
            val sending = queue.mapNotNull { it.action as? WaSyncAction.SendMedia }.filter { it.file !in paths }
            val byKind =
                ready
                    .groupBy { it.kind }
                    .map { (kind, of) -> WaSyncKindUsage(kind, of.size, of.sumOf { it.bytes ?: 0 }) }
                    .sortedWith(compareByDescending<WaSyncKindUsage> { it.bytes }.thenBy { it.kind.name.lowercase() })
            val cap = capBytes
            ok(
                WaSyncMediaStats(
                    total = usage(ready),
                    exempt = spared,
                    byKind = byKind,
                    outbox = WaSyncUsage(sending.size, sending.sumOf { it.size }),
                    queued = files.values.count { it.state == WaSyncMediaState.Queued },
                    downloading = files.values.count { it.state == WaSyncMediaState.Downloading },
                    failed = files.values.count { it.state == WaSyncMediaState.Failed },
                    capBytes = cap,
                    exemptOverCap = cap != null && spared.bytes > cap,
                ),
            )
        }

    override suspend fun mediaCap(req: WaSyncMediaCapRequest): Outcome<WaSyncMediaCap> =
        synchronized(lock) {
            if (req.noLimit && req.capBytes != null) {
                return@synchronized Outcome.Err(RustError.Invalid("cap_bytes and no_limit exclude each other"))
            }
            if (req.noLimit) {
                capBytes = null
            } else if (req.capBytes != null) {
                capBytes = req.capBytes
            }
            ok(WaSyncMediaCap(HANDLE, capBytes))
        }

    override suspend fun chatMedia(req: WaSyncChatMediaRequest): Outcome<WaSyncGalleryPage> =
        synchronized(lock) {
            val kinds = req.kinds.ifEmpty { listOf(WaSyncKind.Image, WaSyncKind.Video) }
            kinds.firstOrNull { it !in GALLERY_KINDS }?.let {
                val name = it.name.lowercase()
                return@synchronized Outcome.Err(
                    RustError.Invalid("a gallery has images, videos, audio and documents, not $name"),
                )
            }
            val order = compareByDescending<WaSyncMessage> { it.timestampMs }.thenByDescending { it.id }

            fun below(
                row: WaSyncMessage,
                cursor: WaSyncCursor,
            ) = row.timestampMs < cursor.timestampMs || (row.timestampMs == cursor.timestampMs && row.id < cursor.id)

            fun above(
                row: WaSyncMessage,
                cursor: WaSyncCursor,
            ) = row.timestampMs > cursor.timestampMs || (row.timestampMs == cursor.timestampMs && row.id > cursor.id)
            val all =
                messages[keyOf(req.chat)]
                    .orEmpty()
                    .filter { it.kind in kinds && !it.deleted && it.media?.viewOnce != true }
                    .sortedWith(order)
            val (before, after) = req.before to req.after
            val window = all.filter { (before == null || below(it, before)) && (after == null || above(it, after)) }
            val limit = req.limit.coerceIn(1, MAX_GALLERY_PAGE)
            // With `after` alone the rows nearest above the cursor are wanted.
            val page = if (after != null && before == null) window.takeLast(limit) else window.take(limit)
            val older =
                page.lastOrNull()?.let { WaSyncCursor(it.timestampMs, it.id) }?.takeIf { edge ->
                    all.any { below(it, edge) }
                }
            val newer =
                page.firstOrNull()?.let { WaSyncCursor(it.timestampMs, it.id) }?.takeIf { edge ->
                    all.any { above(it, edge) }
                }
            ok(WaSyncGalleryPage(page.map(::withMedia), older, newer))
        }

    override suspend fun mediaViewed(req: WaSyncMediaViewedRequest): Outcome<WaSyncViewed> =
        synchronized(lock) {
            writes += req
            val chat = keyOf(req.chat)
            val row = row(chat, req.id) ?: return@synchronized missing(chat, req.id)
            if (row.kind !in MEDIA_KINDS || row.media?.viewOnce != true) {
                return@synchronized Outcome.Err(RustError.Invalid("message ${req.id} of $chat is not view once"))
            }
            val key = chat to req.id
            val entry = entryOf(row)
            val gone =
                WaSyncMediaEntry(
                    entry.chat,
                    entry.id,
                    entry.kind,
                    entry.mime,
                    entry.sizeExpected,
                    WaSyncMediaState.Gone,
                )
            files[key] = gone
            thumbs.remove(key)
            queuedMs.remove(key)
            attemptedMs.remove(key)
            store(row.copy(viewed = true, hasThumbnail = false, mediaPath = null, mediaState = null))
            ok(WaSyncViewed(HANDLE, gone, delete = entry.path, first = !row.viewed))
        }

    override suspend fun expire(req: dev.rosty.core.model.wa.WaSyncExpireRequest) =
        synchronized(lock) {
            writes += req
            var removed = 0
            val files = mutableListOf<String>()
            val chats = mutableListOf<String>()
            for ((chat, rows) in messages) {
                val gone = rows.filter { row -> row.expiresAt?.let { it <= req.nowMs } == true }
                if (gone.isEmpty()) continue
                rows.removeAll(gone.toSet())
                gone.forEach { row ->
                    this.files
                        .remove(chat to row.id)
                        ?.path
                        ?.let(files::add)
                }
                removed += gone.size
                chats += chat
            }
            val next =
                messages.values
                    .flatten()
                    .mapNotNull { it.expiresAt }
                    .minOrNull()
            ok(
                dev.rosty.core.model.wa
                    .WaSyncExpired(HANDLE, removed, files, chats, next),
            )
        }

    override suspend fun markPlayed(req: WaSyncMarkPlayedRequest): Outcome<WaSyncPlayed> =
        synchronized(lock) {
            writes += req
            queue.firstOrNull { it.clientId == req.clientId }?.let {
                return@synchronized ok(WaSyncPlayed(HANDLE, queued = true, item = it))
            }
            val chat = keyOf(req.chat)
            val row = row(chat, req.id) ?: return@synchronized missing(chat, req.id)
            if (row.fromMe || row.kind != WaSyncKind.Audio || row.media?.voiceNote != true) {
                return@synchronized Outcome.Err(RustError.Invalid("only a received voice note can be marked played"))
            }
            if (row.played) return@synchronized ok(WaSyncPlayed(HANDLE, queued = false))
            val sender = row.sender.takeIf { chat.endsWith("@g.us") }
            val item =
                WaSyncOutboxItem(
                    req.clientId,
                    chat,
                    WaSyncAction.MarkPlayed(req.id, sender),
                    req.nowMs,
                    0,
                    0,
                    WaSyncOutboxState.Waiting,
                )
            queue += item
            store(row.copy(played = true))
            ok(WaSyncPlayed(HANDLE, queued = true, item = item))
        }

    /** What a status or a `state` event says about the gateway and the account. */
    private class StatusTold(
        val epoch: String?,
        val accountEpoch: Int?,
        val ownPn: String?,
        val nowMs: Long,
    )

    /** What taking in a status, a `state` event or an announcement did. */
    private class Noted(
        val gap: Boolean,
        val changed: Boolean,
        val deleteFiles: List<String>,
    )

    /** Another epoch than the stored one is a gap; so is the first epoch a store with a `seq` hears. */
    private fun noteEpoch(told: String?): Boolean {
        val named = told?.takeIf { it.isNotEmpty() } ?: return false
        val gap = named != epoch && (epoch != null || seq != null)
        epoch = named
        if (gap) {
            seq = null
            sweepPending = false
            owed = owed.copy(afterSeq = null, refreshChats = true)
        }
        return gap
    }

    /** The wire's report as the store's; a state of a newer gateway keeps the stored one. */
    private fun report(status: HistoryStatus): WaSyncHistoryReport {
        val state =
            when (status.state) {
                HistoryStatus.IDLE -> WaSyncHistoryState.Idle
                HistoryStatus.SYNCING -> WaSyncHistoryState.Syncing
                HistoryStatus.DONE -> WaSyncHistoryState.Done
                else -> history?.state ?: WaSyncHistoryState.Idle
            }
        return WaSyncHistoryReport(state, status.progress, status.chats, status.messages, status.oldestMs)
    }

    /**
     * Takes in a status answer, or a `state` event at its place in the stream ([fromEvent]): nothing of an older
     * account can follow such an event, so its wipe waits for no announcement, and an event of an account before
     * the stored one only names the gateway process.
     */
    private fun noted(
        told: StatusTold,
        report: WaSyncHistoryReport?,
        fromEvent: Boolean,
    ): Noted {
        val gap = noteEpoch(told.epoch)
        val newEpoch = told.accountEpoch
        val was = accountEpoch
        if (fromEvent && was != null && newEpoch != null && newEpoch < was) return Noted(gap, false, emptyList())
        val own = told.ownPn?.let(::bare)?.takeIf { WaIds.number(it) != null }
        val known = ownPn
        val raised = was != null && newEpoch != null && newEpoch > was
        val otherNumber = own != null && known != null && own != known
        // While a status wipe waits for its announcement, an event that names another number is not believed.
        if (fromEvent && !raised && otherNumber && sweepPending) return Noted(gap, false, emptyList())
        val changed = raised || otherNumber
        val deleteFiles = if (changed) wipeAccount(keepOutbox = false) else emptyList()
        // The announcement is waited for only when one can come: an answer of the same process, a gateway with
        // account epochs.
        if (changed) sweepPending = !fromEvent && !gap && newEpoch != null
        if (newEpoch != null) {
            accountEpoch = newEpoch
        } else if (changed) {
            accountEpoch = null
        }
        if (own != null) ownPn = own
        report?.let {
            history =
                WaSyncHistory(
                    it.state,
                    it.progress?.coerceAtMost(100),
                    it.chats,
                    it.messages,
                    it.oldestMs,
                    history?.syncType,
                    told.nowMs,
                )
        }
        return Noted(gap, changed, deleteFiles)
    }

    /** The gateway's `account_changed`, at its place in the stream. */
    private fun announced(
        oldPn: String?,
        newPn: String,
    ): Noted {
        val new = bare(newPn).takeIf { WaIds.number(it) != null }
        val old = oldPn?.let(::bare)
        val known = ownPn
        val full = new != null && if (known != null) known != new else old != null && old != new
        val sweep = new != null && known == new && sweepPending
        val deleteFiles =
            when {
                full -> wipeAccount(keepOutbox = false).also { accountEpoch = null }
                sweep -> wipeAccount(keepOutbox = true)
                else -> emptyList()
            }
        if (full || sweep) sweepPending = false
        if (new != null) ownPn = new
        return Noted(gap = false, changed = full || sweep, deleteFiles = deleteFiles)
    }

    /** A `history_synced` event: its kind, the oldest message so far, the progress, and its messages on top. */
    private fun historySynced(
        event: GatewayEvent.HistorySynced,
        atMs: Long,
    ) {
        val before = history ?: WaSyncHistory(WaSyncHistoryState.Syncing, chats = 0, messages = 0, updatedMs = atMs)
        history =
            before.copy(
                progress = event.progress?.coerceAtMost(100) ?: before.progress,
                messages = before.messages + event.messages,
                oldestMs = listOfNotNull(before.oldestMs, event.oldestMs).minOrNull(),
                syncType = event.syncType ?: before.syncType,
                updatedMs = atMs,
            )
    }

    override suspend fun noteStatus(req: WaSyncNoteStatusRequest): Outcome<WaSyncStatusNoted> =
        synchronized(lock) {
            writes += req
            val told = StatusTold(req.epoch, req.accountEpoch, req.ownPn, req.nowMs)
            val noted = noted(told, req.history, fromEvent = false)
            ok(
                WaSyncStatusNoted(
                    HANDLE,
                    gap = noted.gap,
                    accountChanged = noted.changed,
                    deleteFiles = noted.deleteFiles,
                    resync = noted.gap || noted.changed,
                    history = history,
                ),
            )
        }

    override suspend fun accountChanged(req: WaSyncAccountChangedRequest): Outcome<WaSyncIdentityApplied> =
        synchronized(lock) {
            writes += req
            val noted = announced(req.oldPn, req.newPn)
            ok(
                WaSyncIdentityApplied(
                    HANDLE,
                    emptyList(),
                    emptyList(),
                    noted.changed,
                    noted.changed,
                    noted.deleteFiles,
                ),
            )
        }

    override suspend fun history(req: WaSyncHandleRequest): Outcome<WaSyncHistoryAnswer> =
        synchronized(lock) { ok(WaSyncHistoryAnswer(history)) }

    /** Empties the fake; it has no files of its own, so `deleted` is empty. */
    override suspend fun wipe(req: WaSyncWipeRequest): Outcome<WaSyncWiped> =
        synchronized(lock) {
            writes += req
            if (req.handle == null && req.instanceId == null) {
                return@synchronized Outcome.Err(RustError.Invalid("wipe names a handle or an instance_id"))
            }
            val gone = wipeAccount(keepOutbox = false)
            seq = null
            epoch = null
            accountEpoch = null
            sweepPending = false
            capBytes = DEFAULT_CAP_BYTES
            owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            ok(WaSyncWiped(emptyList(), gone))
        }

    override suspend fun identityResolve(req: WaSyncResolveRequest): Outcome<WaSyncResolved?> =
        synchronized(lock) {
            val id = parsed(req.id) ?: return@synchronized ok(null)
            ok(WaSyncResolved(key = keyOf(id.bare), kind = id.kind, wire = id.bare))
        }

    /** The number of the key first, then the numbers that were merged into it (a number change), newest first. */
    override suspend fun identityNumbers(req: WaSyncResolveRequest): Outcome<WaSyncNumbersAnswer> =
        synchronized(lock) {
            val id = parsed(req.id) ?: return@synchronized ok(WaSyncNumbersAnswer(emptyList()))
            val key = keyOf(id.bare)
            val former = rekeyed.filter { it.intoKey == key }.reversed().mapNotNull { WaIds.number(it.fromKey) }
            ok(WaSyncNumbersAnswer((listOfNotNull(WaIds.number(key)) + former).distinct()))
        }

    override suspend fun rekeyedSince(req: WaSyncRekeyedRequest): Outcome<WaSyncRekeyedAnswer> =
        synchronized(lock) { ok(WaSyncRekeyedAnswer(rekeyed.filter { it.seq > (req.seq ?: 0) })) }

    override suspend fun identityCatchUp(req: WaSyncCatchUpRequest): Outcome<WaSyncCaughtUp> =
        synchronized(lock) {
            writes += req
            val steps = req.items.mapNotNull { merge(it.lid, it.pn) }
            unreported.removeAll(steps)
            if (req.next != null) owed = owed.copy(identitySince = req.next)
            ok(
                WaSyncCaughtUp(
                    HANDLE,
                    pairs = req.items.size,
                    since = owed.identitySince,
                    more = req.next != null,
                    rekeyed = steps,
                    resync = steps.isNotEmpty(),
                ),
            )
        }

    override suspend fun identityObserve(req: WaSyncObserveRequest): Outcome<WaSyncIdentityApplied> =
        synchronized(lock) {
            writes += req
            // The whole status, as `noteStatus` takes it field by field.
            val noted =
                req.status?.let { status ->
                    val told = StatusTold(status.epoch, status.accountEpoch, status.ownJid, req.nowMs)
                    noted(told, status.history?.let(::report), fromEvent = false)
                }
            val pairs =
                req.groups.flatMap { it.participants }.mapNotNull { member ->
                    val lid = member.lid ?: return@mapNotNull null
                    lid to (member.pn ?: return@mapNotNull null)
                } + req.profiles.mapNotNull { profile -> profile.lid?.let { it to profile.jid } }
            val steps = pairs.mapNotNull { (lid, pn) -> merge(lid, pn) }
            unreported.removeAll(steps)
            ok(
                WaSyncIdentityApplied(
                    HANDLE,
                    steps,
                    emptyList(),
                    resync = steps.isNotEmpty() || noted?.gap == true || noted?.changed == true,
                    accountChanged = noted?.changed == true,
                    deleteFiles = noted?.deleteFiles.orEmpty(),
                ),
            )
        }

    override suspend fun parseId(req: WaIdParseRequest): Outcome<WaIdParsed?> = ok(parsed(req.id))

    companion object {
        /** The handle every answer carries. */
        const val HANDLE = 1L

        /** The gateway's default page. */
        const val PAGE = 50

        /** The schema version the real store writes. */
        const val SCHEMA_VERSION = 6

        // The constants of `rosty_app_core::wa_sync::media`.
        const val DEFAULT_CAP_BYTES = 512L * 1024 * 1024
        const val FREE_SPACE_FLOOR = 500L * 1024 * 1024
        const val RECENT_USE_MS = 24L * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 5
        const val DOWNLOAD_LEASE_MS = 10L * 60 * 1000
        private const val DEFAULT_DUE = 4
        private const val MAX_DUE = 32
        private const val BACKOFF_BASE_MS = 2_000L
        private const val BACKOFF_MAX_MS = 300_000L

        private val MEDIA_KINDS =
            setOf(WaSyncKind.Image, WaSyncKind.Video, WaSyncKind.Audio, WaSyncKind.Document, WaSyncKind.Sticker)
        private val GALLERY_KINDS =
            setOf(WaSyncKind.Image, WaSyncKind.Video, WaSyncKind.Audio, WaSyncKind.Document)
        private const val MAX_GALLERY_PAGE = 200
        private val CANCELLABLE =
            setOf(WaSyncMediaState.Queued, WaSyncMediaState.Downloading, WaSyncMediaState.Failed)

        /** The wait before attempt `attempts + 1`: 2 s doubling to 300 s, as the outbox. */
        fun backoffMs(attempts: Int): Long =
            (BACKOFF_BASE_MS shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(BACKOFF_MAX_MS)
    }
}
