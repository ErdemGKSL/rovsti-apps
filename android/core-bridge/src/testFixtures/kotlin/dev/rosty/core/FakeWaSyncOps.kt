package dev.rosty.core

import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.HandleAnswer
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIdParsed
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncCancelled
import dev.rosty.core.model.wa.WaSyncCatchUpRequest
import dev.rosty.core.model.wa.WaSyncCaughtUp
import dev.rosty.core.model.wa.WaSyncChat
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
import dev.rosty.core.model.wa.WaSyncEviction
import dev.rosty.core.model.wa.WaSyncHandleRequest
import dev.rosty.core.model.wa.WaSyncIdentityApplied
import dev.rosty.core.model.wa.WaSyncItemAnswer
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncMessageAnswer
import dev.rosty.core.model.wa.WaSyncMessageRequest
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
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest
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

    private val media = mutableMapOf<Pair<String, String>, WaSyncMedia>()
    private var seq: Long? = null

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
            for (file in media.keys.filter { it.first == old }) {
                media.remove(file)?.let { media[key to file.second] = it }
            }
            val step = WaSyncRekeyed(rekeyed.size + 1L, old, key)
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

    private fun waiting(item: WaSyncOutboxItem): WaSyncMessage? {
        val text =
            when (val action = item.action) {
                is WaSyncAction.SendText -> action.text
                is WaSyncAction.SendMedia -> action.caption
                else -> return null
            }
        return WaSyncMessage(
            id = item.clientId,
            chat = item.chat,
            fromMe = true,
            timestampMs = item.createdMs,
            kind = if (item.action is WaSyncAction.SendText) WaSyncKind.Text else WaSyncKind.Image,
            text = text,
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

    override suspend fun open(req: WaSyncOpenRequest) = ok(WaSyncOpened(HANDLE, instanceId, 2, seq, owed.refreshChats))

    override suspend fun close(req: WaSyncHandleRequest) = ok(WaSyncClosed(true))

    override suspend fun plan(req: WaSyncPlanRequest) = ok(owed)

    override suspend fun applyEvents(req: WaSyncEventsRequest): Outcome<WaSyncApplied> =
        synchronized(lock) {
            writes += req
            val changed = linkedSetOf<String>()
            for (envelope in req.events) {
                seq = envelope.seq
                val mapped = envelope.event as? GatewayEvent.IdentityMapped
                if (mapped != null) merge(mapped.lid, mapped.pn)?.let { changed += it.intoKey }
                val event = envelope.event as? GatewayEvent.Message ?: continue
                val chat = keyOf(event.chat)
                store(
                    stored(
                        RichMessage(
                            event.id,
                            chat,
                            event.sender?.let(::keyOf),
                            event.fromMe,
                            event.timestampMs,
                            event.body,
                            event.reactions,
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
                    resync = steps.isNotEmpty(),
                    chats = changed.toList(),
                    reconciled = emptyList(),
                    rekeyed = steps,
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

    override suspend fun older(req: WaSyncOlderRequest) = ok(WaSyncOlderAnswer(null))

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
            ok(WaSyncThreadPage(pending + page, next, reachedStart = true, syncing = false))
        }

    override suspend fun message(req: WaSyncMessageRequest): Outcome<WaSyncMessageAnswer> =
        synchronized(lock) {
            ok(WaSyncMessageAnswer(messages[keyOf(req.chat)].orEmpty().firstOrNull { it.id == req.id }))
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
            ok(WaSyncStarredPage(page, next))
        }

    override suspend fun outboxEnqueue(req: WaSyncEnqueueRequest): Outcome<WaSyncItemAnswer> =
        synchronized(lock) {
            writes += req
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
                        req.action,
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
                        store(
                            sent.copy(
                                id = id,
                                timestampMs = outcome.timestampMs ?: req.nowMs,
                                state = WaSyncRowState.Sent,
                            ),
                        )
                    }
                    ok(WaSyncSettled(HANDLE, WaSyncSettled.DONE, messageId = id))
                }

                is WaSyncOutcome.Error -> {
                    val failed = outcome.error == dev.rosty.core.model.wa.WaSyncErrorKind.Rejected
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
            val key = req.chat to req.id
            val had = media[key]
            media[key] =
                WaSyncMedia(req.thumbnailB64 ?: had?.thumbnailB64, req.path ?: had?.path, req.bytes ?: had?.bytes)
            ok(HandleAnswer(HANDLE))
        }

    override suspend fun media(req: WaSyncMediaRequest): Outcome<WaSyncMedia> =
        synchronized(lock) { ok(media[req.chat to req.id] ?: WaSyncMedia(null, null, null)) }

    override suspend fun mediaEvict(req: WaSyncEvictRequest): Outcome<WaSyncEviction> =
        synchronized(lock) { ok(WaSyncEviction(emptyList(), media.values.sumOf { it.bytes ?: 0 })) }

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
            val pairs =
                req.groups.flatMap { it.participants }.mapNotNull { member ->
                    val lid = member.lid ?: return@mapNotNull null
                    lid to (member.pn ?: return@mapNotNull null)
                } + req.profiles.mapNotNull { profile -> profile.lid?.let { it to profile.jid } }
            val steps = pairs.mapNotNull { (lid, pn) -> merge(lid, pn) }
            unreported.removeAll(steps)
            ok(WaSyncIdentityApplied(HANDLE, steps, emptyList(), steps.isNotEmpty(), accountChanged = false))
        }

    override suspend fun parseId(req: WaIdParseRequest): Outcome<WaIdParsed?> = ok(parsed(req.id))

    companion object {
        /** The handle every answer carries. */
        const val HANDLE = 1L
    }
}
