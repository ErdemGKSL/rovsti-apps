package dev.rosty.core.ops

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.RustHandle
import dev.rosty.core.RustNative
import dev.rosty.core.map
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIdParsed
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaSyncAccountChangedRequest
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncCatchUpRequest
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.core.model.wa.WaSyncChatMediaRequest
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
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncGalleryPage
import dev.rosty.core.model.wa.WaSyncHandleRequest
import dev.rosty.core.model.wa.WaSyncHistory
import dev.rosty.core.model.wa.WaSyncHistoryReport
import dev.rosty.core.model.wa.WaSyncIdentityApplied
import dev.rosty.core.model.wa.WaSyncIdentityCatchUp
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMarkPlayedRequest
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaCapRequest
import dev.rosty.core.model.wa.WaSyncMediaDecision
import dev.rosty.core.model.wa.WaSyncMediaDue
import dev.rosty.core.model.wa.WaSyncMediaDueRequest
import dev.rosty.core.model.wa.WaSyncMediaEnqueueRequest
import dev.rosty.core.model.wa.WaSyncMediaEntry
import dev.rosty.core.model.wa.WaSyncMediaKeepRequest
import dev.rosty.core.model.wa.WaSyncMediaPolicyRequest
import dev.rosty.core.model.wa.WaSyncMediaQueued
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMediaSettleRequest
import dev.rosty.core.model.wa.WaSyncMediaSettled
import dev.rosty.core.model.wa.WaSyncMediaStats
import dev.rosty.core.model.wa.WaSyncMediaStatsRequest
import dev.rosty.core.model.wa.WaSyncMediaViewedRequest
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncMessageRequest
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.model.wa.WaSyncNoteStatusRequest
import dev.rosty.core.model.wa.WaSyncObserveRequest
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncOpenRequest
import dev.rosty.core.model.wa.WaSyncOpened
import dev.rosty.core.model.wa.WaSyncOutboxEditRequest
import dev.rosty.core.model.wa.WaSyncOutboxItem
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncPlanRequest
import dev.rosty.core.model.wa.WaSyncPlayed
import dev.rosty.core.model.wa.WaSyncReconcileRequest
import dev.rosty.core.model.wa.WaSyncReconciled
import dev.rosty.core.model.wa.WaSyncRekeyed
import dev.rosty.core.model.wa.WaSyncRekeyedRequest
import dev.rosty.core.model.wa.WaSyncResolveRequest
import dev.rosty.core.model.wa.WaSyncResolved
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
import dev.rosty.core.model.wa.WaSyncViewed
import dev.rosty.core.model.wa.WaSyncWipeRequest
import dev.rosty.core.model.wa.WaSyncWiped

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

    /** The gateway answered that it does not list identity pairs: not asked again while this store lives. */
    @Volatile
    private var identityUnsupported = false

    private inline fun <T> live(call: () -> Outcome<T>): Outcome<T> =
        if (released) Outcome.Err(RustError.NotFound("wa_sync store $id is closed")) else call()

    /** What is owed: the `seq` for `wa.events_start`, a chat list refresh, the pages of unfinished resyncs. */
    suspend fun plan(limit: Int? = null): Outcome<WaSyncPlan> = live { ops.plan(WaSyncPlanRequest(id, limit)) }

    /**
     * Applies gateway events, oldest first; `resync` in the answer asks for a chat list refresh. Hand in every event
     * of the socket as it came, `hello` and `account_changed` included: the store takes the gateway's epoch from
     * `hello`, `resync` and `state` (another one than the stored is a gap, as in [noteStatus]), empties itself at an
     * `account_changed` or a `state` of another account (`accountChanged`, `deleteFiles` in the answer, to be treated
     * as in [noteStatus]) and keeps the history progress `state` and `history_synced` report ([history]).
     */
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

    /** A page of the starred messages of every chat, newest first; [before] is the `next` of the page before. */
    suspend fun starred(
        limit: Int,
        before: WaSyncStarredCursor? = null,
    ): Outcome<WaSyncStarredPage> = live { ops.starred(WaSyncStarredRequest(id, limit, before)) }

    /**
     * Queues [action] under [clientId] (a fresh UUID per action); it shows in the thread at once. A
     * [WaSyncAction.SendMedia] carries the options of the gateway call (preview, pixel size, waveform, GIF flag,
     * view once): they are checked here ([RustError.Invalid] for a preview that is not a JPEG of at most 64 KiB, a
     * waveform that is not 64 samples of 0 to 100, view once on a document or sticker), come back on the due row
     * ([WaSyncAction.SendMedia.options]) and show on the waiting row and the sent message as `media`. A played
     * receipt is queued with [markPlayed], not here.
     */
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

    // ---- media: the store decides, the caller does the file I/O

    /**
     * What the auto-download rules say about the file of a message on [network]: on Wi-Fi images, voice notes and
     * stickers download by themselves, on mobile data images and voice notes; video and documents wait for a tap;
     * view-once media never downloads; nothing downloads by itself on roaming, with Data Saver, offline, or when
     * [freeBytes] minus the file would be under 500 MiB. [enqueueMedia] applies the same rules; ask here only to
     * show them.
     */
    suspend fun mediaPolicy(
        chat: String,
        messageId: String,
        network: WaSyncNetwork,
        freeBytes: Long? = null,
    ): Outcome<WaSyncMediaDecision> =
        live { ops.mediaPolicy(WaSyncMediaPolicyRequest(id, chat, messageId, network, freeBytes)).map { it.decision } }

    /**
     * Asks for the full file of a message. With [tap] it is queued whatever the network. View-once media is never
     * queued by the rules; a tap queues a received one that was not opened yet (the tap is what opens it) and
     * answers [WaSyncMediaDecision.Never] once it was opened ([markViewed]) and for one the owner sent.
     * Without it the rules decide under [network], which must then be given: call it for every media row that
     * arrives or comes into view. A cached file stays as it is; a file that failed for good or is gone from the
     * gateway is only tried again on a tap.
     */
    suspend fun enqueueMedia(
        chat: String,
        messageId: String,
        nowMs: Long,
        tap: Boolean = false,
        network: WaSyncNetwork? = null,
        freeBytes: Long? = null,
    ): Outcome<WaSyncMediaQueued> =
        live { ops.mediaEnqueue(WaSyncMediaEnqueueRequest(id, nowMs, chat, messageId, tap, network, freeBytes)) }

    /**
     * The downloads to make now, taps first. Download each to `<file>.part`, sync, rename, then [settleMedia]. With
     * [network], automatic downloads the rules no longer allow stay queued (`held`). A download nobody settled is
     * handed out again after ten minutes.
     */
    suspend fun dueMedia(
        nowMs: Long,
        limit: Int? = null,
        network: WaSyncNetwork? = null,
        freeBytes: Long? = null,
    ): Outcome<WaSyncMediaDue> = live { ops.mediaDue(WaSyncMediaDueRequest(id, nowMs, limit, network, freeBytes)) }

    /**
     * Reports how a download ended: [WaSyncFetched.Ready] with the file, [WaSyncFetched.Failed] (it is queued again
     * after a back-off, five times), [WaSyncFetched.Gone] (the gateway no longer has it) or
     * [WaSyncFetched.Cancelled]. Delete the answer's `replaced` file when it names one, then [evictMedia].
     */
    suspend fun settleMedia(
        chat: String,
        messageId: String,
        outcome: WaSyncFetched,
        nowMs: Long,
    ): Outcome<WaSyncMediaSettled> =
        live { ops.mediaSettle(WaSyncMediaSettleRequest(id, nowMs, chat, messageId, outcome)) }

    /**
     * Keeps the file of a message out of eviction: [pinned], or [savedUri] once the user saved a copy to the gallery
     * (`""` forgets the copy). Null leaves a value as it is.
     */
    suspend fun keepMedia(
        chat: String,
        messageId: String,
        pinned: Boolean? = null,
        savedUri: String? = null,
    ): Outcome<WaSyncMediaEntry> =
        live { ops.mediaKeep(WaSyncMediaKeepRequest(id, chat, messageId, pinned, savedUri)).map { it.media } }

    /**
     * Brings the cached media under [maxBytes], or under the stored cap ([mediaCap]) when it is null; delete the
     * files the answer names. Files of starred messages, pinned and saved files, files an unsent message sends and,
     * with [nowMs], files used in the last 24 hours are never evicted, also when they alone are over the cap.
     */
    suspend fun evictMedia(
        maxBytes: Long? = null,
        nowMs: Long? = null,
    ): Outcome<WaSyncEviction> = live { ops.mediaEvict(WaSyncEvictRequest(id, maxBytes, nowMs)) }

    /**
     * Run once after the store opens. Downloads that were cut off are queued again. With [files] (every file under
     * the directories of full media and of outbox files, as absolute paths; not the thumbnails), a row whose file is
     * missing becomes `evicted` and the answer's `delete` names the files to remove: `*.part` files and files nothing
     * names. Pass null when the directory could not be listed: an empty list would evict every row.
     */
    suspend fun reconcileMedia(files: List<String>?): Outcome<WaSyncReconciled> =
        live { ops.mediaReconcile(WaSyncReconcileRequest(id, files)) }

    /** The cache in numbers for the settings screen: total, exempt, by kind, the queue and the cap. */
    suspend fun mediaStats(nowMs: Long? = null): Outcome<WaSyncMediaStats> =
        live { ops.mediaStats(WaSyncMediaStatsRequest(id, nowMs)) }

    /** The cap of the media cache in bytes; null: no limit. 512 MiB until it is set. */
    suspend fun mediaCap(): Outcome<Long?> = live { ops.mediaCap(WaSyncMediaCapRequest(id)).map { it.capBytes } }

    /** Sets the cap ([capBytes] null: no limit) and answers it. Nothing is evicted here: call [evictMedia]. */
    suspend fun setMediaCap(capBytes: Long?): Outcome<Long?> =
        live {
            ops.mediaCap(WaSyncMediaCapRequest(id, capBytes, noLimit = capBytes == null)).map { it.capBytes }
        }

    // ---- the media of a chat, view once, played

    /**
     * A page of the media of [chat] for a viewer or a gallery, newest first. [kinds] is any of Image, Video,
     * Document, Audio (empty: images and videos). [before] and [after] are cursors of any row
     * (`WaSyncCursor(row.timestampMs, row.id)`) or the `older` / `newer` of a page: [before] alone is the page
     * below it, [after] alone the page right above it, both a window. A viewer opened on a message asks for both
     * sides of it and keeps paging with the answer's `older` and `newer`, which are null at the ends. Deleted
     * messages, view-once media, sends still in the outbox and messages cleared on another device are left out.
     * [chat] may be any id of the chat.
     */
    suspend fun chatMedia(
        chat: String,
        limit: Int,
        before: WaSyncCursor? = null,
        after: WaSyncCursor? = null,
        kinds: List<WaSyncKind> = emptyList(),
    ): Outcome<WaSyncGalleryPage> =
        live { ops.chatMedia(WaSyncChatMediaRequest(id, chat, limit, before, after, kinds)) }

    /**
     * The owner opened a view-once message: call it when the viewer shows it. The row reads `viewed` from now on,
     * its media is [dev.rosty.core.model.wa.WaSyncMediaState.Gone] for good and its thumbnail is dropped. Delete
     * the answer's `delete` file once the viewer closed. Calling it again changes nothing (`first` false). A
     * message that is not view once is [RustError.Invalid].
     */
    suspend fun markViewed(
        chat: String,
        messageId: String,
        nowMs: Long,
    ): Outcome<WaSyncViewed> = live { ops.mediaViewed(WaSyncMediaViewedRequest(id, nowMs, chat, messageId)) }

    /**
     * A received voice note was played: queues its played receipt under [clientId] (a fresh UUID), once per
     * message. `queued` false: it was marked before and nothing is sent. The row reads `played` at once; the
     * receipt goes out with [due] as a [WaSyncAction.MarkPlayed] row. Anything but a received voice note is
     * [RustError.Invalid].
     */
    suspend fun markPlayed(
        clientId: String,
        chat: String,
        messageId: String,
        nowMs: Long,
    ): Outcome<WaSyncPlayed> = live { ops.markPlayed(WaSyncMarkPlayedRequest(id, nowMs, clientId, chat, messageId)) }

    // ---- the gateway process, the linked account, history progress, unlink

    /**
     * Takes in what the gateway says about itself and the linked account, field by field. [observe] does the same
     * with a whole `wa.status` answer and [applyEvents] with the events of the socket (`hello`, `resync`, `state`,
     * `account_changed`), so this is for a caller that holds the values alone.
     *
     * - Another [epoch] than the stored one: `gap`. The stored `seq` is forgotten, so restart the event stream from
     *   [plan]'s `afterSeq` (null now) and refresh the chat list.
     * - An [accountEpoch] above the stored one, or an [ownPn] other than the stored one: `accountChanged`. The store
     *   was emptied; delete the answer's `deleteFiles`, the rest of the instance's media directory and everything
     *   kept per chat outside the store. An unknown value on either side changes nothing.
     * - [history] is stored and answered back; [history] reads it after a restart.
     */
    suspend fun noteStatus(
        nowMs: Long,
        epoch: String? = null,
        accountEpoch: Int? = null,
        ownPn: String? = null,
        ownLid: String? = null,
        history: WaSyncHistoryReport? = null,
    ): Outcome<WaSyncStatusNoted> =
        live { ops.noteStatus(WaSyncNoteStatusRequest(id, nowMs, epoch, accountEpoch, ownPn, ownLid, history)) }

    /**
     * The gateway's `account_changed` event, handed in at its place in the event stream (before the events after
     * it are applied), for a caller that does not hand the event itself to [applyEvents]; never both. A store of another account is emptied; one already emptied by [noteStatus] is swept of what
     * arrived in between. Treat `accountChanged` in the answer as in [noteStatus].
     */
    suspend fun accountChanged(
        oldPn: String?,
        newPn: String,
        nowMs: Long,
    ): Outcome<WaSyncIdentityApplied> =
        live { ops.accountChanged(WaSyncAccountChangedRequest(id, nowMs, oldPn, newPn)) }

    /** The history progress the gateway last reported; null before the first report. */
    suspend fun history(): Outcome<WaSyncHistory?> = live { ops.history(WaSyncHandleRequest(id)).map { it.history } }

    /**
     * Unlink: closes this store and deletes its files. The answer names the media files the store knew; delete them
     * and the instance's media directory. Every other [WaSyncStore] of the same instance answers
     * [RustError.NotFound] from now on.
     */
    suspend fun wipe(): Outcome<WaSyncWiped> {
        if (released) return Outcome.Err(RustError.NotFound("wa_sync store $id is closed"))
        val wiped = ops.wipe(WaSyncWipeRequest(handle = id))
        if (wiped is Outcome.Ok) close()
        return wiped
    }

    // ---- identity: one person is one chat, whichever id the gateway names them by

    /**
     * Who [jid] is to the store: the key its chat is stored under (the number when it is known, else the LID), the
     * kind of the id and the id to put back on the wire. Use it for anything that arrives outside the store (a
     * typing or presence event, a notification, a route argument) before comparing it with a chat. Null: [jid] is
     * not a JID.
     */
    suspend fun resolve(jid: String): Outcome<WaSyncResolved?> =
        live { ops.identityResolve(WaSyncResolveRequest(id, jid)) }

    /**
     * The phone numbers of the person behind [jid] as `+<digits>`: the current one first, then the former ones (a
     * contact that changed number). Look a device contact up by each in turn, so a contact that only holds the
     * former number still names the chat. Empty for a LID whose number is not known, a group and text that is not
     * a JID. Nothing is learned by asking.
     */
    suspend fun numbers(jid: String): Outcome<List<String>> =
        live { ops.identityNumbers(WaSyncResolveRequest(id, jid)).map { it.numbers } }

    /**
     * The key changes after [seq] (0: all of them), oldest first. What is kept per chat outside the store (drafts,
     * prefs, notification tags, the open thread) keeps the `seq` of the last step it followed and replays the rest:
     * a merge that happened while that layer was not running is not lost.
     */
    suspend fun rekeyedSince(seq: Long = 0): Outcome<List<WaSyncRekeyed>> =
        live { ops.rekeyedSince(WaSyncRekeyedRequest(id, seq)).map { it.rekeyed } }

    /**
     * Learns from what the gateway answered outside the event stream: [status] (`wa.status`, the whole answer: the
     * account's own number and LID, and its epoch, account epoch and history report, taken in as [noteStatus] takes
     * them, so a gap reads as `resync` and another account as `accountChanged` with `deleteFiles`), [groups] (`wa.group` / `wa.groups`: members named by both ids) and [profiles]
     * (`wa.profiles`). Hand the answers in as they came; the answer lists the chats that merged.
     */
    suspend fun observe(
        nowMs: Long,
        status: InstanceStatus? = null,
        groups: List<GroupInfo> = emptyList(),
        profiles: List<WaProfile> = emptyList(),
    ): Outcome<WaSyncIdentityApplied> =
        live { ops.identityObserve(WaSyncObserveRequest(id, nowMs, status, groups, profiles)) }

    /**
     * Catches up on the LID and number pairs the gateway learned: asks [fetch] (`WaGateway.identityMappings`) page
     * after page from the stored cursor and hands each page in, until the gateway has no more. Run it when the
     * store opens, when the event socket reconnects and after a `resync`, before the chat list is refreshed, so
     * the list lands on merged chats.
     *
     * A gateway that does not list pairs is remembered for the life of this store and not asked again. A page that
     * cannot be fetched or stored stops the run ([WaSyncIdentityCatchUp.complete] false); the cursor stays where
     * the last stored page left it, so the next run goes on from there.
     */
    suspend fun identityCatchUp(
        fetch: suspend (since: String?) -> Outcome<IdentityMappingsAnswer>,
    ): Outcome<WaSyncIdentityCatchUp> {
        if (released) return Outcome.Err(RustError.NotFound("wa_sync store $id is closed"))
        if (identityUnsupported) return Outcome.Ok(WaSyncIdentityCatchUp(supported = false, complete = true))
        var since =
            when (val plan = ops.plan(WaSyncPlanRequest(id))) {
                is Outcome.Err -> return plan
                is Outcome.Ok -> plan.value.identitySince
            }
        var done = WaSyncIdentityCatchUp(supported = true, complete = false)
        repeat(MAX_IDENTITY_PAGES) {
            val page =
                when (val got = fetch(since)) {
                    is Outcome.Err -> return Outcome.Ok(done.copy(error = got.error))
                    is Outcome.Ok -> got.value
                }
            if (!page.supported) {
                identityUnsupported = true
                return Outcome.Ok(done.copy(supported = false, complete = true))
            }
            val took =
                when (val out = ops.identityCatchUp(WaSyncCatchUpRequest(id, page.items, page.next))) {
                    is Outcome.Err -> return Outcome.Ok(done.copy(error = out.error))
                    is Outcome.Ok -> out.value
                }
            done =
                done.copy(
                    pairs = done.pairs + took.pairs,
                    rekeyed = done.rekeyed + took.rekeyed,
                    rejected = done.rejected + took.rejected,
                    resync = done.resync || took.resync,
                )
            // A cursor that does not move would ask for the same page forever.
            if (!took.more || took.since == since) return Outcome.Ok(done.copy(complete = true))
            since = took.since
        }
        return Outcome.Ok(done)
    }

    /** Takes a JID apart (kind, user, bare id, number, device) without asking the store; null: not a JID. */
    suspend fun parseId(jid: String): Outcome<WaIdParsed?> = ops.parseId(WaIdParseRequest(jid))

    companion object {
        /** A stop for one catch-up on identity pairs; the cursor is stored, so the next run continues. */
        private const val MAX_IDENTITY_PAGES = 200

        /**
         * Unlink without a store at hand: deletes the store files of [instanceId] in [dir] (the core's data
         * directory when null), whether a store of it is open or not. An instance without a store answers two
         * empty lists.
         */
        suspend fun wipe(
            instanceId: String,
            dir: String? = null,
            ops: WaSyncOps = NativeWaSyncOps(),
        ): Outcome<WaSyncWiped> = ops.wipe(WaSyncWipeRequest(instanceId = instanceId, dir = dir))

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
