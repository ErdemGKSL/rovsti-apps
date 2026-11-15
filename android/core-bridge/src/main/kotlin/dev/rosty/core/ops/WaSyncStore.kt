package dev.rosty.core.ops

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.RustHandle
import dev.rosty.core.RustNative
import dev.rosty.core.map
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.core.model.wa.WaSyncChatsRequest
import dev.rosty.core.model.wa.WaSyncClientIdRequest
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncDue
import dev.rosty.core.model.wa.WaSyncDueRequest
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncEvictRequest
import dev.rosty.core.model.wa.WaSyncEviction
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncHandleRequest
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncMessageRequest
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncOpenRequest
import dev.rosty.core.model.wa.WaSyncOpened
import dev.rosty.core.model.wa.WaSyncOutboxEditRequest
import dev.rosty.core.model.wa.WaSyncOutboxItem
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncPlanRequest
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest

/**
 * The offline store of one linked WhatsApp instance: [open] it once per instance (app container scope), [close] it on
 * unlink or when the scope ends. Two stores opened on the same instance in one process share the data. A closed
 * store answers [RustError.NotFound] without a native call.
 *
 * Times are the caller's (`System.currentTimeMillis()`): the Rust side has no clock.
 */
class WaSyncStore internal constructor(
    /** What the store looked like when it was opened. */
    val opened: WaSyncOpened,
    private val ops: WaSyncOps,
    release: (Long) -> Unit,
) : RustHandle(opened.handle, release) {
    val instanceId: String get() = opened.instanceId

    private inline fun <T> live(call: () -> Outcome<T>): Outcome<T> =
        if (released) Outcome.Err(RustError.NotFound("wa_sync store $id is closed")) else call()

    /** What is owed: the `seq` for `wa.events_start`, a chat list refresh, the pages of unfinished resyncs. */
    suspend fun plan(limit: Int? = null): Outcome<WaSyncPlan> = live { ops.plan(WaSyncPlanRequest(id, limit)) }

    /** Applies gateway events, oldest first; `resync` in the answer asks for a chat list refresh. */
    suspend fun applyEvents(events: List<WaSyncEvent>): Outcome<WaSyncApplied> =
        live { ops.applyEvents(WaSyncEventsRequest(id, events)) }

    /** Stores `wa.chats_with_meta`; the answer names the pages to fetch for chats that missed messages. */
    suspend fun storeChats(
        chats: List<RichChat>,
        nowMs: Long,
        limit: Int? = null,
        refreshRecentMs: Long? = null,
    ): Outcome<WaSyncStoredChats> =
        live {
            ops.storeChats(WaSyncStoreChatsRequest(id, nowMs, chats, limit, refreshRecentMs))
        }

    /** Stores the answer of the `wa.messages_with_meta` call that [fetch] asked for. */
    suspend fun storePage(
        fetch: WaSyncFetch,
        messages: List<RichMessage>,
        nowMs: Long,
    ): Outcome<WaSyncStoredPage> =
        live { ops.storePage(WaSyncStorePageRequest(id, nowMs, fetch.chat, fetch.before, fetch.limit, messages)) }

    /** The page before the oldest stored message of [chat]; null: there is nothing older. */
    suspend fun older(
        chat: String,
        limit: Int? = null,
    ): Outcome<WaSyncFetch?> = live { ops.older(WaSyncOlderRequest(id, chat, limit)).map { it.fetch } }

    /** The chat list, newest first. */
    suspend fun chats(includeArchived: Boolean = false): Outcome<List<WaSyncChat>> =
        live { ops.chats(WaSyncChatsRequest(id, includeArchived)).map { it.chats } }

    /** A page of [chat], newest first; [before] is the `next` of the page before. */
    suspend fun thread(
        chat: String,
        limit: Int,
        before: WaSyncCursor? = null,
    ): Outcome<WaSyncThreadPage> = live { ops.thread(WaSyncThreadRequest(id, chat, limit, before)) }

    suspend fun message(
        chat: String,
        messageId: String,
    ): Outcome<WaSyncMessage?> = live { ops.message(WaSyncMessageRequest(id, chat, messageId)).map { it.message } }

    /** Queues [action] under [clientId] (a fresh UUID per action); it shows in the thread at once. */
    suspend fun enqueue(
        clientId: String,
        chat: String,
        action: WaSyncAction,
        nowMs: Long,
    ): Outcome<WaSyncOutboxItem> =
        live { ops.outboxEnqueue(WaSyncEnqueueRequest(id, nowMs, clientId, chat, action)).map { it.item } }

    /** The rows to send now; each must be answered with [settle]. */
    suspend fun due(
        nowMs: Long,
        networkBack: Boolean = false,
    ): Outcome<WaSyncDue> = live { ops.outboxDue(WaSyncDueRequest(id, nowMs, networkBack)) }

    suspend fun settle(
        clientId: String,
        outcome: WaSyncOutcome,
        nowMs: Long,
    ): Outcome<WaSyncSettled> = live { ops.outboxSettle(WaSyncSettleRequest(id, nowMs, clientId, outcome)) }

    /** False: already gone, or going out right now. */
    suspend fun cancel(clientId: String): Outcome<Boolean> =
        live { ops.outboxCancel(WaSyncClientIdRequest(id, clientId)).map { it.cancelled } }

    /** Changes the text (or caption) of a send that has not gone out. */
    suspend fun editQueued(
        clientId: String,
        text: String,
    ): Outcome<WaSyncOutboxItem> = live { ops.outboxEdit(WaSyncOutboxEditRequest(id, clientId, text)).map { it.item } }

    suspend fun retry(clientId: String): Outcome<Boolean> =
        live { ops.outboxRetry(WaSyncClientIdRequest(id, clientId)).map { it.retried } }

    /** Every outbox row, in the order written. */
    suspend fun outbox(): Outcome<List<WaSyncOutboxItem>> =
        live { ops.outbox(WaSyncHandleRequest(id)).map { it.items } }

    /** Stores the thumbnail (base64) and/or the downloaded full file ([path] with its size [bytes]) of a message. */
    suspend fun setMedia(
        chat: String,
        messageId: String,
        nowMs: Long,
        thumbnailB64: String? = null,
        path: String? = null,
        bytes: Long? = null,
    ): Outcome<Unit> =
        live { ops.mediaSet(WaSyncMediaSetRequest(id, nowMs, chat, messageId, thumbnailB64, path, bytes)).map { Unit } }

    /** The thumbnail and the cached file of a message; [usedMs] (it is being opened) keeps the file fresh in the LRU. */
    suspend fun media(
        chat: String,
        messageId: String,
        usedMs: Long? = null,
    ): Outcome<WaSyncMedia> = live { ops.media(WaSyncMediaRequest(id, chat, messageId, usedMs)) }

    /** Brings the cached media under [maxBytes]; delete the files the answer names. */
    suspend fun evictMedia(maxBytes: Long): Outcome<WaSyncEviction> =
        live { ops.mediaEvict(WaSyncEvictRequest(id, maxBytes)) }

    companion object {
        /** Opens the store of [instanceId]; [dir] defaults to the core's data directory. The store is the caller's to close. */
        suspend fun open(
            instanceId: String,
            dir: String? = null,
            memory: Boolean = false,
            ops: WaSyncOps = NativeWaSyncOps(),
            release: (Long) -> Unit = RustNative::release,
        ): Outcome<WaSyncStore> =
            when (val opened = ops.open(WaSyncOpenRequest(instanceId, dir, memory))) {
                is Outcome.Err -> opened
                is Outcome.Ok -> Outcome.Ok(WaSyncStore(opened.value, ops, release))
            }
    }
}
