package dev.rosty.core.ops

import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.call
import dev.rosty.core.model.wa.HandleAnswer
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIdParsed
import dev.rosty.core.model.wa.WaSyncAccountChangedRequest
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncCancelled
import dev.rosty.core.model.wa.WaSyncCatchUpRequest
import dev.rosty.core.model.wa.WaSyncCaughtUp
import dev.rosty.core.model.wa.WaSyncChatMediaRequest
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
import dev.rosty.core.model.wa.WaSyncGalleryPage
import dev.rosty.core.model.wa.WaSyncHandleRequest
import dev.rosty.core.model.wa.WaSyncHistoryAnswer
import dev.rosty.core.model.wa.WaSyncIdentityApplied
import dev.rosty.core.model.wa.WaSyncItemAnswer
import dev.rosty.core.model.wa.WaSyncMarkPlayedRequest
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaCap
import dev.rosty.core.model.wa.WaSyncMediaCapRequest
import dev.rosty.core.model.wa.WaSyncMediaDue
import dev.rosty.core.model.wa.WaSyncMediaDueRequest
import dev.rosty.core.model.wa.WaSyncMediaEnqueueRequest
import dev.rosty.core.model.wa.WaSyncMediaKeepRequest
import dev.rosty.core.model.wa.WaSyncMediaKept
import dev.rosty.core.model.wa.WaSyncMediaPolicy
import dev.rosty.core.model.wa.WaSyncMediaPolicyRequest
import dev.rosty.core.model.wa.WaSyncMediaQueued
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMediaSettleRequest
import dev.rosty.core.model.wa.WaSyncMediaSettled
import dev.rosty.core.model.wa.WaSyncMediaStats
import dev.rosty.core.model.wa.WaSyncMediaStatsRequest
import dev.rosty.core.model.wa.WaSyncMediaViewedRequest
import dev.rosty.core.model.wa.WaSyncMessageAnswer
import dev.rosty.core.model.wa.WaSyncMessageRequest
import dev.rosty.core.model.wa.WaSyncNoteStatusRequest
import dev.rosty.core.model.wa.WaSyncNumbersAnswer
import dev.rosty.core.model.wa.WaSyncObserveRequest
import dev.rosty.core.model.wa.WaSyncOlderAnswer
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncOpenRequest
import dev.rosty.core.model.wa.WaSyncOpened
import dev.rosty.core.model.wa.WaSyncOutboxAnswer
import dev.rosty.core.model.wa.WaSyncOutboxEditRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncPlanRequest
import dev.rosty.core.model.wa.WaSyncPlayed
import dev.rosty.core.model.wa.WaSyncReconcileRequest
import dev.rosty.core.model.wa.WaSyncReconciled
import dev.rosty.core.model.wa.WaSyncRekeyedAnswer
import dev.rosty.core.model.wa.WaSyncRekeyedRequest
import dev.rosty.core.model.wa.WaSyncResolveRequest
import dev.rosty.core.model.wa.WaSyncResolved
import dev.rosty.core.model.wa.WaSyncRetried
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.model.wa.WaSyncStarredPage
import dev.rosty.core.model.wa.WaSyncStarredRequest
import dev.rosty.core.model.wa.WaSyncStatusNoted
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest
import dev.rosty.core.model.wa.WaSyncViewed
import dev.rosty.core.model.wa.WaSyncWipeRequest
import dev.rosty.core.model.wa.WaSyncWiped
import kotlinx.serialization.builtins.nullable

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

    /** A page of the starred messages of every chat, newest first. */
    suspend fun starred(req: WaSyncStarredRequest): Outcome<WaSyncStarredPage>

    /** Queues a send, edit, delete, reaction, read mark or star; it shows in the thread at once. */
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

    /** Brings the media cache under a cap (the stored one when none is named); answers the files to delete. */
    suspend fun mediaEvict(req: WaSyncEvictRequest): Outcome<WaSyncEviction>

    /** What the auto-download rules say about the file of a message on a network. */
    suspend fun mediaPolicy(req: WaSyncMediaPolicyRequest): Outcome<WaSyncMediaPolicy>

    /** Asks for the full file of a message: a tap, or the rules under the network named. */
    suspend fun mediaEnqueue(req: WaSyncMediaEnqueueRequest): Outcome<WaSyncMediaQueued>

    /** The downloads to make now; each is `downloading` until settled. */
    suspend fun mediaDue(req: WaSyncMediaDueRequest): Outcome<WaSyncMediaDue>

    /** Reports how a download ended. */
    suspend fun mediaSettle(req: WaSyncMediaSettleRequest): Outcome<WaSyncMediaSettled>

    /** Pins a file or records where the user saved a copy: such a file is never evicted. */
    suspend fun mediaKeep(req: WaSyncMediaKeepRequest): Outcome<WaSyncMediaKept>

    /** Puts rows and files back in step when the store opens; answers the files to delete. */
    suspend fun mediaReconcile(req: WaSyncReconcileRequest): Outcome<WaSyncReconciled>

    /** The media cache in numbers. */
    suspend fun mediaStats(req: WaSyncMediaStatsRequest): Outcome<WaSyncMediaStats>

    /** Reads the media cap, or sets it first. */
    suspend fun mediaCap(req: WaSyncMediaCapRequest): Outcome<WaSyncMediaCap>

    /** A page of the photos, videos, audio and documents of a chat, newest first, by cursor both ways. */
    suspend fun chatMedia(req: WaSyncChatMediaRequest): Outcome<WaSyncGalleryPage>

    /** The owner opened a view-once message: its file is handed back for deletion and never fetched again. */
    suspend fun mediaViewed(req: WaSyncMediaViewedRequest): Outcome<WaSyncViewed>

    /** Queues the played receipt of a received voice note, once per message. */
    suspend fun markPlayed(req: WaSyncMarkPlayedRequest): Outcome<WaSyncPlayed>

    /** Takes in the gateway's epoch, account epoch, own ids and history progress. */
    suspend fun noteStatus(req: WaSyncNoteStatusRequest): Outcome<WaSyncStatusNoted>

    /** Takes in the gateway's announcement that another account was linked. */
    suspend fun accountChanged(req: WaSyncAccountChangedRequest): Outcome<WaSyncIdentityApplied>

    /** The history progress the gateway last reported. */
    suspend fun history(req: WaSyncHandleRequest): Outcome<WaSyncHistoryAnswer>

    /** Closes a store and deletes its files (unlink). */
    suspend fun wipe(req: WaSyncWipeRequest): Outcome<WaSyncWiped>

    /** The key an id's chat is stored under, its kind and its wire form; null when the text is not a JID. */
    suspend fun identityResolve(req: WaSyncResolveRequest): Outcome<WaSyncResolved?>

    /** The phone numbers of the person behind an id, the current one first, then the former ones. */
    suspend fun identityNumbers(req: WaSyncResolveRequest): Outcome<WaSyncNumbersAnswer>

    /** The key changes logged after a `seq`, oldest first. */
    suspend fun rekeyedSince(req: WaSyncRekeyedRequest): Outcome<WaSyncRekeyedAnswer>

    /** Takes in one page of the gateway's LID and number pairs (`wa.identity_mappings`). */
    suspend fun identityCatchUp(req: WaSyncCatchUpRequest): Outcome<WaSyncCaughtUp>

    /** Learns from the answers of `wa.status`, `wa.group(s)` and `wa.profiles`. */
    suspend fun identityObserve(req: WaSyncObserveRequest): Outcome<WaSyncIdentityApplied>

    /** Takes a JID apart (`wa_identity.parse`, no store needed); null when the text is not a JID. */
    suspend fun parseId(req: WaIdParseRequest): Outcome<WaIdParsed?>

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
                "wa_sync.starred",
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
                "wa_sync.media_policy",
                "wa_sync.media_enqueue",
                "wa_sync.media_due",
                "wa_sync.media_settle",
                "wa_sync.media_keep",
                "wa_sync.media_reconcile",
                "wa_sync.media_stats",
                "wa_sync.media_cap",
                "wa_sync.chat_media",
                "wa_sync.media_viewed",
                "wa_sync.mark_played",
                "wa_sync.note_status",
                "wa_sync.account_changed",
                "wa_sync.history",
                "wa_sync.wipe",
                "wa_sync.identity_resolve",
                "wa_sync.rekeyed_since",
                "wa_sync.identity_numbers",
                "wa_sync.identity_catch_up",
                "wa_sync.identity_observe",
                "wa_identity.parse",
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

    override suspend fun starred(req: WaSyncStarredRequest) =
        core.call("wa_sync.starred", req, WaSyncStarredRequest.serializer(), WaSyncStarredPage.serializer())

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

    override suspend fun mediaPolicy(req: WaSyncMediaPolicyRequest) =
        core.call(
            "wa_sync.media_policy",
            req,
            WaSyncMediaPolicyRequest.serializer(),
            WaSyncMediaPolicy.serializer(),
        )

    override suspend fun mediaEnqueue(req: WaSyncMediaEnqueueRequest) =
        core.call(
            "wa_sync.media_enqueue",
            req,
            WaSyncMediaEnqueueRequest.serializer(),
            WaSyncMediaQueued.serializer(),
        )

    override suspend fun mediaDue(req: WaSyncMediaDueRequest) =
        core.call("wa_sync.media_due", req, WaSyncMediaDueRequest.serializer(), WaSyncMediaDue.serializer())

    override suspend fun mediaSettle(req: WaSyncMediaSettleRequest) =
        core.call(
            "wa_sync.media_settle",
            req,
            WaSyncMediaSettleRequest.serializer(),
            WaSyncMediaSettled.serializer(),
        )

    override suspend fun mediaKeep(req: WaSyncMediaKeepRequest) =
        core.call("wa_sync.media_keep", req, WaSyncMediaKeepRequest.serializer(), WaSyncMediaKept.serializer())

    override suspend fun mediaReconcile(req: WaSyncReconcileRequest) =
        core.call("wa_sync.media_reconcile", req, WaSyncReconcileRequest.serializer(), WaSyncReconciled.serializer())

    override suspend fun mediaStats(req: WaSyncMediaStatsRequest) =
        core.call("wa_sync.media_stats", req, WaSyncMediaStatsRequest.serializer(), WaSyncMediaStats.serializer())

    override suspend fun mediaCap(req: WaSyncMediaCapRequest) =
        core.call("wa_sync.media_cap", req, WaSyncMediaCapRequest.serializer(), WaSyncMediaCap.serializer())

    override suspend fun chatMedia(req: WaSyncChatMediaRequest) =
        core.call("wa_sync.chat_media", req, WaSyncChatMediaRequest.serializer(), WaSyncGalleryPage.serializer())

    override suspend fun mediaViewed(req: WaSyncMediaViewedRequest) =
        core.call("wa_sync.media_viewed", req, WaSyncMediaViewedRequest.serializer(), WaSyncViewed.serializer())

    override suspend fun markPlayed(req: WaSyncMarkPlayedRequest) =
        core.call("wa_sync.mark_played", req, WaSyncMarkPlayedRequest.serializer(), WaSyncPlayed.serializer())

    override suspend fun noteStatus(req: WaSyncNoteStatusRequest) =
        core.call("wa_sync.note_status", req, WaSyncNoteStatusRequest.serializer(), WaSyncStatusNoted.serializer())

    override suspend fun accountChanged(req: WaSyncAccountChangedRequest) =
        core.call(
            "wa_sync.account_changed",
            req,
            WaSyncAccountChangedRequest.serializer(),
            WaSyncIdentityApplied.serializer(),
        )

    override suspend fun history(req: WaSyncHandleRequest) =
        core.call("wa_sync.history", req, WaSyncHandleRequest.serializer(), WaSyncHistoryAnswer.serializer())

    override suspend fun wipe(req: WaSyncWipeRequest) =
        core.call("wa_sync.wipe", req, WaSyncWipeRequest.serializer(), WaSyncWiped.serializer())

    override suspend fun identityResolve(req: WaSyncResolveRequest) =
        core.call(
            "wa_sync.identity_resolve",
            req,
            WaSyncResolveRequest.serializer(),
            WaSyncResolved.serializer().nullable,
        )

    override suspend fun identityNumbers(req: WaSyncResolveRequest) =
        core.call(
            "wa_sync.identity_numbers",
            req,
            WaSyncResolveRequest.serializer(),
            WaSyncNumbersAnswer.serializer(),
        )

    override suspend fun rekeyedSince(req: WaSyncRekeyedRequest) =
        core.call("wa_sync.rekeyed_since", req, WaSyncRekeyedRequest.serializer(), WaSyncRekeyedAnswer.serializer())

    override suspend fun identityCatchUp(req: WaSyncCatchUpRequest) =
        core.call("wa_sync.identity_catch_up", req, WaSyncCatchUpRequest.serializer(), WaSyncCaughtUp.serializer())

    override suspend fun identityObserve(req: WaSyncObserveRequest) =
        core.call(
            "wa_sync.identity_observe",
            req,
            WaSyncObserveRequest.serializer(),
            WaSyncIdentityApplied.serializer(),
        )

    override suspend fun parseId(req: WaIdParseRequest) =
        core.call("wa_identity.parse", req, WaIdParseRequest.serializer(), WaIdParsed.serializer().nullable)
}
