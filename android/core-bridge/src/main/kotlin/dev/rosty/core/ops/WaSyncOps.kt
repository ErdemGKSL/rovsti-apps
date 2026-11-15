package dev.rosty.core.ops

import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.call
import dev.rosty.core.model.wa.HandleAnswer
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncCancelled
import dev.rosty.core.model.wa.WaSyncChatsAnswer
import dev.rosty.core.model.wa.WaSyncChatsRequest
import dev.rosty.core.model.wa.WaSyncClientIdRequest
import dev.rosty.core.model.wa.WaSyncClosed
import dev.rosty.core.model.wa.WaSyncDue
import dev.rosty.core.model.wa.WaSyncDueRequest
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncEvictRequest
import dev.rosty.core.model.wa.WaSyncEviction
import dev.rosty.core.model.wa.WaSyncHandleRequest
import dev.rosty.core.model.wa.WaSyncItemAnswer
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMessageAnswer
import dev.rosty.core.model.wa.WaSyncMessageRequest
import dev.rosty.core.model.wa.WaSyncOlderAnswer
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncOpenRequest
import dev.rosty.core.model.wa.WaSyncOpened
import dev.rosty.core.model.wa.WaSyncOutboxAnswer
import dev.rosty.core.model.wa.WaSyncOutboxEditRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncPlanRequest
import dev.rosty.core.model.wa.WaSyncRetried
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest

/**
 * The WhatsApp offline store (E6.4s): a SQLite file per linked instance, kept by the Rust core. The inbox and the
 * thread read WhatsApp only from here; the network only fills it. No method talks to the gateway: the caller makes
 * the [WaOps] calls and hands the answers in. Use [WaSyncStore] instead of passing handles around.
 */
interface WaSyncOps {
    /** Opens (or creates) the store of an instance; the answer's handle goes into every other request. */
    suspend fun open(req: WaSyncOpenRequest): Outcome<WaSyncOpened>

    suspend fun close(req: WaSyncHandleRequest): Outcome<WaSyncClosed>

    /** What the store still owes: the `seq` to subscribe after, a chat list refresh, the pages of open gaps. */
    suspend fun plan(req: WaSyncPlanRequest): Outcome<WaSyncPlan>

    /** Applies gateway events in order (idempotent). */
    suspend fun applyEvents(req: WaSyncEventsRequest): Outcome<WaSyncApplied>

    /** Stores the refreshed chat list; answers the first page of every chat that missed messages. */
    suspend fun storeChats(req: WaSyncStoreChatsRequest): Outcome<WaSyncStoredChats>

    /** Stores one page of messages; answers the next page of the same resync, if any. */
    suspend fun storePage(req: WaSyncStorePageRequest): Outcome<WaSyncStoredPage>

    /** The page before the oldest stored message (scrolling up past it). */
    suspend fun older(req: WaSyncOlderRequest): Outcome<WaSyncOlderAnswer>

    /** The chat list, newest first. */
    suspend fun chats(req: WaSyncChatsRequest): Outcome<WaSyncChatsAnswer>

    /** A page of a thread, newest first, with deleted, edited and waiting rows. */
    suspend fun thread(req: WaSyncThreadRequest): Outcome<WaSyncThreadPage>

    suspend fun message(req: WaSyncMessageRequest): Outcome<WaSyncMessageAnswer>

    /** Queues a send, edit, delete, reaction or read mark; it shows in the thread at once. */
    suspend fun outboxEnqueue(req: WaSyncEnqueueRequest): Outcome<WaSyncItemAnswer>

    /** The rows to send now (one per chat, in the order written); they are `sending` until settled. */
    suspend fun outboxDue(req: WaSyncDueRequest): Outcome<WaSyncDue>

    /** Reports the gateway's answer to an attempt. */
    suspend fun outboxSettle(req: WaSyncSettleRequest): Outcome<WaSyncSettled>

    suspend fun outboxCancel(req: WaSyncClientIdRequest): Outcome<WaSyncCancelled>

    /** Changes the text of a send that has not gone out. */
    suspend fun outboxEdit(req: WaSyncOutboxEditRequest): Outcome<WaSyncItemAnswer>

    /** Lets a failed row go out again. */
    suspend fun outboxRetry(req: WaSyncClientIdRequest): Outcome<WaSyncRetried>

    suspend fun outbox(req: WaSyncHandleRequest): Outcome<WaSyncOutboxAnswer>

    /** Stores a thumbnail and/or the downloaded full file of a message. */
    suspend fun mediaSet(req: WaSyncMediaSetRequest): Outcome<HandleAnswer>

    suspend fun media(req: WaSyncMediaRequest): Outcome<WaSyncMedia>

    /** Brings the media cache under a cap; answers the files to delete. */
    suspend fun mediaEvict(req: WaSyncEvictRequest): Outcome<WaSyncEviction>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "wa_sync.open",
                "wa_sync.close",
                "wa_sync.plan",
                "wa_sync.apply_events",
                "wa_sync.store_chats",
                "wa_sync.store_page",
                "wa_sync.older",
                "wa_sync.chats",
                "wa_sync.thread",
                "wa_sync.message",
                "wa_sync.outbox_enqueue",
                "wa_sync.outbox_due",
                "wa_sync.outbox_settle",
                "wa_sync.outbox_cancel",
                "wa_sync.outbox_edit",
                "wa_sync.outbox_retry",
                "wa_sync.outbox",
                "wa_sync.media_set",
                "wa_sync.media",
                "wa_sync.media_evict",
            )
    }
}

/** [WaSyncOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeWaSyncOps(
    private val core: RustCore = NativeRustCore(),
) : WaSyncOps {
    override suspend fun open(req: WaSyncOpenRequest) =
        core.call("wa_sync.open", req, WaSyncOpenRequest.serializer(), WaSyncOpened.serializer())

    override suspend fun close(req: WaSyncHandleRequest) =
        core.call("wa_sync.close", req, WaSyncHandleRequest.serializer(), WaSyncClosed.serializer())

    override suspend fun plan(req: WaSyncPlanRequest) =
        core.call("wa_sync.plan", req, WaSyncPlanRequest.serializer(), WaSyncPlan.serializer())

    override suspend fun applyEvents(req: WaSyncEventsRequest) =
        core.call("wa_sync.apply_events", req, WaSyncEventsRequest.serializer(), WaSyncApplied.serializer())

    override suspend fun storeChats(req: WaSyncStoreChatsRequest) =
        core.call("wa_sync.store_chats", req, WaSyncStoreChatsRequest.serializer(), WaSyncStoredChats.serializer())

    override suspend fun storePage(req: WaSyncStorePageRequest) =
        core.call("wa_sync.store_page", req, WaSyncStorePageRequest.serializer(), WaSyncStoredPage.serializer())

    override suspend fun older(req: WaSyncOlderRequest) =
        core.call("wa_sync.older", req, WaSyncOlderRequest.serializer(), WaSyncOlderAnswer.serializer())

    override suspend fun chats(req: WaSyncChatsRequest) =
        core.call("wa_sync.chats", req, WaSyncChatsRequest.serializer(), WaSyncChatsAnswer.serializer())

    override suspend fun thread(req: WaSyncThreadRequest) =
        core.call("wa_sync.thread", req, WaSyncThreadRequest.serializer(), WaSyncThreadPage.serializer())

    override suspend fun message(req: WaSyncMessageRequest) =
        core.call("wa_sync.message", req, WaSyncMessageRequest.serializer(), WaSyncMessageAnswer.serializer())

    override suspend fun outboxEnqueue(req: WaSyncEnqueueRequest) =
        core.call("wa_sync.outbox_enqueue", req, WaSyncEnqueueRequest.serializer(), WaSyncItemAnswer.serializer())

    override suspend fun outboxDue(req: WaSyncDueRequest) =
        core.call("wa_sync.outbox_due", req, WaSyncDueRequest.serializer(), WaSyncDue.serializer())

    override suspend fun outboxSettle(req: WaSyncSettleRequest) =
        core.call("wa_sync.outbox_settle", req, WaSyncSettleRequest.serializer(), WaSyncSettled.serializer())

    override suspend fun outboxCancel(req: WaSyncClientIdRequest) =
        core.call("wa_sync.outbox_cancel", req, WaSyncClientIdRequest.serializer(), WaSyncCancelled.serializer())

    override suspend fun outboxEdit(req: WaSyncOutboxEditRequest) =
        core.call("wa_sync.outbox_edit", req, WaSyncOutboxEditRequest.serializer(), WaSyncItemAnswer.serializer())

    override suspend fun outboxRetry(req: WaSyncClientIdRequest) =
        core.call("wa_sync.outbox_retry", req, WaSyncClientIdRequest.serializer(), WaSyncRetried.serializer())

    override suspend fun outbox(req: WaSyncHandleRequest) =
        core.call("wa_sync.outbox", req, WaSyncHandleRequest.serializer(), WaSyncOutboxAnswer.serializer())

    override suspend fun mediaSet(req: WaSyncMediaSetRequest) =
        core.call("wa_sync.media_set", req, WaSyncMediaSetRequest.serializer(), HandleAnswer.serializer())

    override suspend fun media(req: WaSyncMediaRequest) =
        core.call("wa_sync.media", req, WaSyncMediaRequest.serializer(), WaSyncMedia.serializer())

    override suspend fun mediaEvict(req: WaSyncEvictRequest) =
        core.call("wa_sync.media_evict", req, WaSyncEvictRequest.serializer(), WaSyncEviction.serializer())
}
