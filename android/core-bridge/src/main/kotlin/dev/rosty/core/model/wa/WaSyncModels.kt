@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.wa

import dev.rosty.core.RustError
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// Models of `wa_sync.*` (E6.4s): the WhatsApp offline store of `rosty_app_core::wa_sync`, mirroring
// `crates/rosty_ffi/fixtures/wa_sync.*`. Every request but `wa_sync.open` carries the store `handle` flat next to its
// fields; the ops that write answer the handle again. Nothing here talks to the gateway: the answers of `wa.*` calls
// ([RichChat], [RichMessage], [GatewayEvent]) are handed in.

// ---- open, close, plan

/** `wa_sync.open`: the store of one linked instance; another [instanceId] is another file. */
@Serializable
data class WaSyncOpenRequest(
    @SerialName("instance_id") val instanceId: String,
    /** The directory of the store files; the core's data directory when left out. */
    @EncodeDefault(NEVER) val dir: String? = null,
    /** A store in memory (tests, previews). */
    @EncodeDefault(NEVER) val memory: Boolean = false,
)

@Serializable
data class WaSyncOpened(
    val handle: Long,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("schema_version") val schemaVersion: Int,
    /** The last gateway event applied; null before the first one. */
    @SerialName("last_seq") val lastSeq: Long?,
    /** The chat list must be refreshed (first run, or events were missed). */
    @SerialName("needs_resync") val needsResync: Boolean,
)

/** A request that names nothing but the store. */
@Serializable
data class WaSyncHandleRequest(
    val handle: Long,
)

@Serializable
data class WaSyncClosed(
    val closed: Boolean,
)

@Serializable
data class WaSyncPlanRequest(
    val handle: Long,
    /** The page size of the fetches (1 to 200; 50 when left out). */
    @EncodeDefault(NEVER) val limit: Int? = null,
)

/** One `wa.messages_with_meta` call to make: [before] null asks for the newest page. */
@Serializable
data class WaSyncFetch(
    val chat: String,
    val before: Long?,
    val limit: Int,
)

/** The work the store still owes. */
@Serializable
data class WaSyncPlan(
    /** `after` of `wa.events_start`; null on the first run. */
    @SerialName("after_seq") val afterSeq: Long?,
    /** Fetch `wa.chats_with_meta` and hand it to `store_chats`. */
    @SerialName("refresh_chats") val refreshChats: Boolean,
    /** The next page of every chat whose resync is not finished. */
    val fetch: List<WaSyncFetch>,
    /** `since` of the next `wa.identity_mappings` call; null before the first page: ask for every pair. */
    @SerialName("identity_since") @EncodeDefault(NEVER) val identitySince: String? = null,
)

// ---- identity: one person, one chat

/**
 * Rows stored under [fromKey] now live under [intoKey] (a LID chat merged into its number's chat, or a contact
 * changed number): move what is kept per chat outside the store (the draft, the prefs, the notification tag, the
 * open thread). [seq] is the step's place in the store's log, for a consumer's own cursor
 * (`WaSyncStore.rekeyedSince`).
 */
@Serializable
data class WaSyncRekeyed(
    val seq: Long,
    @SerialName("from_key") val fromKey: String,
    @SerialName("into_key") val intoKey: String,
)

/** A LID and number pair that changed nothing, for the log (developer mode). */
@Serializable
data class WaSyncRejected(
    /** The LID as given; empty when a number change named none. */
    val lid: String,
    val pn: String,
    /** Where the pair came from (`peer_message`, `usync`, ...). */
    val source: String,
    /** One of [INVALID], [OUTRANKED], [OWN_ID]. */
    val reason: String,
) {
    companion object {
        /** An id did not parse, or was not a LID next to a number. */
        const val INVALID = "invalid"

        /** The LID already belongs to another number by a stronger or newer source. */
        const val OUTRANKED = "outranked"

        /** The pair would join the account's own number or LID to someone else. */
        const val OWN_ID = "own_id"
    }
}

/** What a WhatsApp id names (`rosty_core::wa_identity::WaIdKind`). */
@Serializable
enum class WaIdKind {
    /** A phone number. */
    @SerialName("pn")
    Pn,

    @SerialName("lid")
    Lid,

    @SerialName("group")
    Group,

    @SerialName("broadcast")
    Broadcast,

    /** The status feed. */
    @SerialName("status")
    Status,

    /** A channel. */
    @SerialName("newsletter")
    Newsletter,

    @SerialName("bot")
    Bot,

    /** Any other server, and the announcements account. */
    @SerialName("other")
    Other,
}

/** `wa_identity.parse`: a JID as the gateway writes it, device allowed. */
@Serializable
data class WaIdParseRequest(
    val id: String,
)

/** A JID taken apart. Who it is (the chat it belongs to) is `WaSyncStore.resolve`. */
@Serializable
data class WaIdParsed(
    val kind: WaIdKind,
    /** The digits of a phone number, the LID user, the group id. */
    val user: String,
    /** `user@server` without device: the id to compare and to store. */
    val bare: String,
    /** `+<digits>` of a phone number; a LID has none, whatever its digits. */
    @EncodeDefault(NEVER) val number: String? = null,
    /** The device the id was written with. */
    @EncodeDefault(NEVER) val device: Int? = null,
)

@Serializable
data class WaSyncResolveRequest(
    val handle: Long,
    /** A JID as it arrived or as a screen holds it, device allowed. */
    val id: String,
)

/** An id resolved against what the store learned. */
@Serializable
data class WaSyncResolved(
    /** The key the chat and the messages of this id are stored under: the number when known, else the LID. */
    val key: String,
    /** What the id names, as it was written. */
    val kind: WaIdKind,
    /** The id as it was written, without device: what goes back on the wire. */
    val wire: String,
    /** The person behind a number or LID; null for a group and every other kind. */
    @SerialName("person_id") @EncodeDefault(NEVER) val personId: Long? = null,
)

@Serializable
data class WaSyncRekeyedRequest(
    val handle: Long,
    /** The `seq` of the last step the caller followed; left out for all of them. */
    @EncodeDefault(NEVER) val seq: Long? = null,
)

@Serializable
data class WaSyncRekeyedAnswer(
    /** Oldest first. */
    val rekeyed: List<WaSyncRekeyed>,
)

/** `wa_sync.identity_catch_up`: one page of `wa.identity_mappings`. */
@Serializable
data class WaSyncCatchUpRequest(
    val handle: Long,
    val items: List<IdentityMapping>,
    val next: String?,
)

@Serializable
data class WaSyncCaughtUp(
    val handle: Long,
    /** Pairs in the page. */
    val pairs: Int,
    /** The cursor the store now holds: `since` of the next call. */
    val since: String?,
    /** The gateway has more: ask again with [since]. */
    val more: Boolean,
    @EncodeDefault(NEVER) val rekeyed: List<WaSyncRekeyed> = emptyList(),
    @EncodeDefault(NEVER) val rejected: List<WaSyncRejected> = emptyList(),
    /** A merge moved rows: refresh the chat list. */
    val resync: Boolean,
)

/**
 * `wa_sync.identity_observe`: what the gateway answered outside the event stream that names a person by both ids.
 * Hand the answers in as they came.
 */
@Serializable
data class WaSyncObserveRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    /** `wa.status`: the account's own number and LID. */
    @EncodeDefault(NEVER) val status: InstanceStatus? = null,
    /** `wa.group` / `wa.groups`: members named by LID and number. */
    @EncodeDefault(NEVER) val groups: List<GroupInfo> = emptyList(),
    /** `wa.profiles` / `wa.refresh_profiles`: the LID of a number. */
    @EncodeDefault(NEVER) val profiles: List<WaProfile> = emptyList(),
)

@Serializable
data class WaSyncIdentityApplied(
    val handle: Long,
    val rekeyed: List<WaSyncRekeyed>,
    val rejected: List<WaSyncRejected>,
    /** A merge moved rows: refresh the chat list. */
    val resync: Boolean,
    /** The own number is another one than before: another account was linked, the identity tables started over. */
    @SerialName("account_changed") val accountChanged: Boolean,
)

/** What a whole catch-up on the gateway's pairs came to (`WaSyncStore.identityCatchUp`). */
data class WaSyncIdentityCatchUp(
    /** False: the gateway does not list pairs (an old one); nothing was asked again in this session. */
    val supported: Boolean,
    /** False: a page could not be fetched or stored ([error]); the next catch-up goes on from the stored cursor. */
    val complete: Boolean,
    /** Pairs taken in. */
    val pairs: Int = 0,
    /** Chats whose key changed, oldest first: follow them. */
    val rekeyed: List<WaSyncRekeyed> = emptyList(),
    val rejected: List<WaSyncRejected> = emptyList(),
    /** A merge moved rows: refresh the chat list. */
    val resync: Boolean = false,
    /** Why the run stopped early. */
    val error: RustError? = null,
)

// ---- sync writes

/** A gateway event as the socket sent it ([WaStreamEvent.Event] without the stream wrapper). */
@Serializable
data class WaSyncEvent(
    val seq: Long,
    @SerialName("at_ms") val atMs: Long,
    val event: GatewayEvent,
) {
    companion object {
        fun of(event: WaStreamEvent.Event) = WaSyncEvent(event.seq, event.atMs, event.event)
    }
}

@Serializable
data class WaSyncEventsRequest(
    val handle: Long,
    /** Oldest first. */
    val events: List<WaSyncEvent>,
)

@Serializable
data class WaSyncApplied(
    val handle: Long,
    @SerialName("last_seq") val lastSeq: Long?,
    /** Events applied; ones at or before the stored `seq` are skipped. */
    val applied: Int,
    /** A `resync` came: refresh the chat list. */
    val resync: Boolean,
    /** Chats whose rows changed. */
    val chats: List<String>,
    /** Client ids of outbox sends an echo confirmed. */
    val reconciled: List<String>,
    /** Chats whose key changed while these events were applied, oldest first: follow them. */
    @EncodeDefault(NEVER) val rekeyed: List<WaSyncRekeyed> = emptyList(),
    /** Pairs the events named that changed nothing, for the log. */
    @EncodeDefault(NEVER) val rejected: List<WaSyncRejected> = emptyList(),
)

@Serializable
data class WaSyncStoreChatsRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val chats: List<RichChat>,
    @EncodeDefault(NEVER) val limit: Int? = null,
    /** Chats active within this long get one page even when nothing seems missed (edits, deletes, ticks). */
    @SerialName("refresh_recent_ms") @EncodeDefault(NEVER) val refreshRecentMs: Long? = null,
)

@Serializable
data class WaSyncStoredChats(
    val handle: Long,
    val chats: Int,
    /** The first page of every chat that missed messages. */
    val fetch: List<WaSyncFetch>,
    /** Chats whose key changed: the list named a pair the store did not know. */
    @EncodeDefault(NEVER) val rekeyed: List<WaSyncRekeyed> = emptyList(),
    @EncodeDefault(NEVER) val rejected: List<WaSyncRejected> = emptyList(),
)

@Serializable
data class WaSyncStorePageRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val chat: String,
    /** The `before` that was asked. */
    val before: Long?,
    /** The `limit` that was asked. */
    val limit: Int,
    /** The page, newest first. */
    val messages: List<RichMessage>,
)

@Serializable
data class WaSyncStoredPage(
    val handle: Long,
    val stored: Int,
    /** Messages the store did not have. */
    val new: Int,
    /** The next page of the resync this page belongs to; null when it is done (or the page was older history). */
    val next: WaSyncFetch?,
    /** The gateway has nothing older than what is stored. */
    @SerialName("reached_start") val reachedStart: Boolean,
    val reconciled: List<String>,
    /** Chats whose key changed: the page named a pair the store did not know. */
    @EncodeDefault(NEVER) val rekeyed: List<WaSyncRekeyed> = emptyList(),
    @EncodeDefault(NEVER) val rejected: List<WaSyncRejected> = emptyList(),
)

@Serializable
data class WaSyncOlderRequest(
    val handle: Long,
    val chat: String,
    @EncodeDefault(NEVER) val limit: Int? = null,
)

@Serializable
data class WaSyncOlderAnswer(
    /** The page before the oldest stored message; null: there is nothing older. */
    val fetch: WaSyncFetch?,
)

// ---- reads

@Serializable
enum class WaSyncKind {
    @SerialName("text")
    Text,

    @SerialName("image")
    Image,

    @SerialName("video")
    Video,

    @SerialName("audio")
    Audio,

    @SerialName("document")
    Document,

    @SerialName("sticker")
    Sticker,

    @SerialName("location")
    Location,

    @SerialName("contact")
    Contact,

    /** Nothing the gateway could describe; also a deleted message whose content was never seen. */
    @SerialName("unsupported")
    Unsupported,
}

/** The ticks of a stored message, or the outbox state of one not sent yet. */
@Serializable
enum class WaSyncRowState {
    @SerialName("received")
    Received,

    @SerialName("sent")
    Sent,

    @SerialName("delivered")
    Delivered,

    @SerialName("read")
    Read,

    @SerialName("played")
    Played,

    /** In the outbox, waiting for the connection (the clock icon). */
    @SerialName("waiting")
    Waiting,

    /** In the outbox, going out now. */
    @SerialName("sending")
    Sending,

    /** In the outbox, refused: retry, edit or cancel it. */
    @SerialName("failed")
    Failed,
}

@Serializable
enum class WaSyncOutboxState {
    @SerialName("waiting")
    Waiting,

    @SerialName("sending")
    Sending,

    @SerialName("failed")
    Failed,
}

/** An edit, delete or reaction of a stored message that still waits in the outbox. */
@Serializable
data class WaSyncPendingOp(
    @SerialName("client_id") val clientId: String,
    /** `edit`, `revoke` or `react`. */
    val kind: String,
    val state: WaSyncOutboxState,
    /** The new text of an edit, the emoji of a reaction. */
    @EncodeDefault(NEVER) val text: String? = null,
)

/**
 * One row of a thread: a stored message, or a send still in the outbox ([state] `waiting`, `sending` or `failed`;
 * [id] is then the client id). A deleted message has [deleted] set and still its [text], [quoted] and media.
 */
@Serializable
data class WaSyncMessage(
    val id: String,
    val chat: String,
    @EncodeDefault(NEVER) val sender: String? = null,
    @SerialName("sender_name") @EncodeDefault(NEVER) val senderName: String? = null,
    @SerialName("from_me") val fromMe: Boolean,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val kind: WaSyncKind,
    /** The text, or the caption of media. */
    @EncodeDefault(NEVER) val text: String? = null,
    @EncodeDefault(NEVER) val mime: String? = null,
    @EncodeDefault(NEVER) val size: Long? = null,
    @EncodeDefault(NEVER) val quoted: Quoted? = null,
    val reactions: List<Reaction>,
    val state: WaSyncRowState,
    @SerialName("edited_ms") @EncodeDefault(NEVER) val editedMs: Long? = null,
    /** Earlier texts, oldest first. */
    @SerialName("edit_history") @EncodeDefault(NEVER) val editHistory: List<EditRecord> = emptyList(),
    @EncodeDefault(NEVER) val deleted: Boolean = false,
    @SerialName("deleted_at") @EncodeDefault(NEVER) val deletedAt: Long? = null,
    /** [DELETED_BY_ME] or the JID of who deleted it for everyone. */
    @SerialName("deleted_by") @EncodeDefault(NEVER) val deletedBy: String? = null,
    @EncodeDefault(NEVER) val forwarded: Boolean = false,
    @EncodeDefault(NEVER) val starred: Boolean = false,
    @EncodeDefault(NEVER) val media: MediaMeta? = null,
    @EncodeDefault(NEVER) val location: Location? = null,
    @EncodeDefault(NEVER) val contact: SharedContact? = null,
    @EncodeDefault(NEVER) val mentions: List<String> = emptyList(),
    /** A thumbnail is stored (`wa_sync.media`). */
    @SerialName("has_thumbnail") @EncodeDefault(NEVER) val hasThumbnail: Boolean = false,
    /** The cached full media; null when not downloaded or evicted. For an outbox media send: the file to send. */
    @SerialName("media_path") @EncodeDefault(NEVER) val mediaPath: String? = null,
    /** The outbox client id this message was sent with, or still waits under. */
    @SerialName("client_id") @EncodeDefault(NEVER) val clientId: String? = null,
    /** Why the last attempt of an outbox row failed. */
    @EncodeDefault(NEVER) val error: String? = null,
    @EncodeDefault(NEVER) val pending: List<WaSyncPendingOp> = emptyList(),
    /**
     * The id the sender arrived under, when it is not [sender] (a LID whose number is known): what an outbound
     * message key names (a reaction, a delete, a read mark of this message).
     */
    @SerialName("wire_sender") @EncodeDefault(NEVER) val wireSender: String? = null,
)

@Serializable
data class WaSyncChat(
    val jid: String,
    @EncodeDefault(NEVER) val name: String? = null,
    @SerialName("push_name") @EncodeDefault(NEVER) val pushName: String? = null,
    @SerialName("is_group") val isGroup: Boolean,
    val unread: Int,
    @EncodeDefault(NEVER) val pinned: Boolean = false,
    @EncodeDefault(NEVER) val archived: Boolean = false,
    /** `-1` is muted forever. */
    @SerialName("muted_until_ms") @EncodeDefault(NEVER) val mutedUntilMs: Long? = null,
    @SerialName("marked_unread") @EncodeDefault(NEVER) val markedUnread: Boolean = false,
    @SerialName("picture_id") @EncodeDefault(NEVER) val pictureId: String? = null,
    /** The time of [last] (0 for an empty chat); the list is sorted by it, newest first. */
    @SerialName("last_ms") val lastMs: Long,
    /** The newest row: a stored message or a send waiting in the outbox. */
    @EncodeDefault(NEVER) val last: WaSyncMessage? = null,
    /** Sends of this chat waiting in the outbox. */
    val waiting: Int,
    /** The id the gateway last used for this chat, when it is not [jid] (the key): the LID of a number chat. */
    @SerialName("wire_jid") @EncodeDefault(NEVER) val wireJid: String? = null,
)

@Serializable
data class WaSyncChatsRequest(
    val handle: Long,
    @SerialName("include_archived") @EncodeDefault(NEVER) val includeArchived: Boolean = false,
)

@Serializable
data class WaSyncChatsAnswer(
    val chats: List<WaSyncChat>,
)

/** Where a thread page ended; pass it back as `before` for the next (older) page. */
@Serializable
data class WaSyncCursor(
    @SerialName("timestamp_ms") val timestampMs: Long,
    val id: String,
)

@Serializable
data class WaSyncThreadRequest(
    val handle: Long,
    val chat: String,
    /** Stored messages per page (the sends in the outbox come on top of the first page). */
    val limit: Int,
    @EncodeDefault(NEVER) val before: WaSyncCursor? = null,
)

/** A page of a thread, newest first. */
@Serializable
data class WaSyncThreadPage(
    val items: List<WaSyncMessage>,
    /** The cursor of the next stored page; null: ask `wa_sync.older` for a gateway page. */
    val next: WaSyncCursor?,
    @SerialName("reached_start") val reachedStart: Boolean,
    /** A resync of this chat is still filling a hole. */
    val syncing: Boolean,
)

/** Where a page of starred messages ended; pass it back as `before` for the next (older) page. */
@Serializable
data class WaSyncStarredCursor(
    @SerialName("timestamp_ms") val timestampMs: Long,
    val chat: String,
    val id: String,
)

@Serializable
data class WaSyncStarredRequest(
    val handle: Long,
    val limit: Int,
    @EncodeDefault(NEVER) val before: WaSyncStarredCursor? = null,
)

/** A page of the starred messages of every chat, newest first. A star still in the outbox counts. */
@Serializable
data class WaSyncStarredPage(
    val items: List<WaSyncMessage>,
    /** The cursor of the next page; null: nothing older is starred. */
    val next: WaSyncStarredCursor?,
)

@Serializable
data class WaSyncMessageRequest(
    val handle: Long,
    val chat: String,
    val id: String,
)

@Serializable
data class WaSyncMessageAnswer(
    val message: WaSyncMessage?,
)

// ---- outbox

/** What a queued row does (`rosty_app_core::wa_sync::Action`). */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface WaSyncAction {
    @Serializable
    @SerialName("send_text")
    data class SendText(
        val text: String,
        @SerialName("quoted_id") @EncodeDefault(NEVER) val quotedId: String? = null,
    ) : WaSyncAction

    /** [file] is already copied into app storage. */
    @Serializable
    @SerialName("send_media")
    data class SendMedia(
        val file: String,
        val media: MediaKind,
        val mime: String,
        val size: Long,
        @EncodeDefault(NEVER) val caption: String? = null,
        @SerialName("quoted_id") @EncodeDefault(NEVER) val quotedId: String? = null,
        @SerialName("file_name") @EncodeDefault(NEVER) val fileName: String? = null,
        @EncodeDefault(NEVER) val seconds: Int? = null,
        @SerialName("voice_note") @EncodeDefault(NEVER) val voiceNote: Boolean = false,
    ) : WaSyncAction

    @Serializable
    @SerialName("edit")
    data class Edit(
        @SerialName("message_id") val messageId: String,
        val text: String,
    ) : WaSyncAction

    /** Delete for everyone. */
    @Serializable
    @SerialName("revoke")
    data class Revoke(
        @SerialName("message_id") val messageId: String,
        @EncodeDefault(NEVER) val sender: String? = null,
    ) : WaSyncAction

    /** An empty [emoji] removes the reaction. */
    @Serializable
    @SerialName("react")
    data class React(
        @SerialName("message_id") val messageId: String,
        @SerialName("from_me") val fromMe: Boolean,
        @EncodeDefault(NEVER) val sender: String? = null,
        val emoji: String,
    ) : WaSyncAction

    @Serializable
    @SerialName("mark_read")
    data class MarkRead(
        @EncodeDefault(NEVER) val sender: String? = null,
        val ids: List<String>,
    ) : WaSyncAction

    /**
     * Stars or unstars a stored message on the account. The stored flag changes at once; a later star of the same
     * message replaces one that has not gone out, and a refused one is undone.
     */
    @Serializable
    @SerialName("star")
    data class Star(
        @SerialName("message_id") val messageId: String,
        val starred: Boolean,
    ) : WaSyncAction
}

@Serializable
data class WaSyncOutboxItem(
    @SerialName("client_id") val clientId: String,
    val chat: String,
    val action: WaSyncAction,
    @SerialName("created_ms") val createdMs: Long,
    val attempts: Int,
    @SerialName("next_attempt_ms") val nextAttemptMs: Long,
    val state: WaSyncOutboxState,
    @SerialName("last_error") @EncodeDefault(NEVER) val lastError: String? = null,
)

@Serializable
data class WaSyncEnqueueRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    /** A random id of this action (a UUID); queuing it twice is one row. */
    @SerialName("client_id") val clientId: String,
    val chat: String,
    val action: WaSyncAction,
)

@Serializable
data class WaSyncItemAnswer(
    val handle: Long,
    val item: WaSyncOutboxItem,
)

@Serializable
data class WaSyncDueRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    /** The connection just came back: every backoff ends. */
    @SerialName("network_back") @EncodeDefault(NEVER) val networkBack: Boolean = false,
)

@Serializable
data class WaSyncDue(
    val handle: Long,
    /** Rows to send now, at most one per chat; they are `sending` until `outbox_settle`. */
    val items: List<WaSyncOutboxItem>,
    /** Sends kept back until the sync is done (their earlier attempt may have gone out). */
    val held: Int,
    /** When to ask again without a new trigger; null: nothing is scheduled. */
    @SerialName("next_wake_ms") @EncodeDefault(NEVER) val nextWakeMs: Long? = null,
)

@Serializable
enum class WaSyncErrorKind {
    /** No answer (timeout, connection lost): the gateway may have sent it. */
    @SerialName("network")
    Network,

    /** The gateway said it did not send (not connected, rate limited, internal error). */
    @SerialName("gateway")
    Gateway,

    /** The gateway refused the request itself; not retried. */
    @SerialName("rejected")
    Rejected,
}

/** The answer to one attempt. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface WaSyncOutcome {
    /** It went out; a send names the gateway's message ([Sent]). */
    @Serializable
    @SerialName("sent")
    data class Sent(
        @SerialName("message_id") @EncodeDefault(NEVER) val messageId: String? = null,
        @SerialName("timestamp_ms") @EncodeDefault(NEVER) val timestampMs: Long? = null,
    ) : WaSyncOutcome

    @Serializable
    @SerialName("error")
    data class Error(
        val error: WaSyncErrorKind,
        val message: String,
        @SerialName("retry_after_ms") @EncodeDefault(NEVER) val retryAfterMs: Long? = null,
    ) : WaSyncOutcome
}

@Serializable
data class WaSyncSettleRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("client_id") val clientId: String,
    val outcome: WaSyncOutcome,
)

/** What became of the row: [state] is one of [DONE], [WAITING], [FAILED]. */
@Serializable
data class WaSyncSettled(
    val handle: Long,
    val state: String,
    /** With [DONE]: the stored message of a send. */
    @SerialName("message_id") @EncodeDefault(NEVER) val messageId: String? = null,
    /** With [WAITING]: when it is tried again. */
    @SerialName("next_attempt_ms") @EncodeDefault(NEVER) val nextAttemptMs: Long? = null,
) {
    companion object {
        const val DONE = "done"
        const val WAITING = "waiting"
        const val FAILED = "failed"
    }
}

@Serializable
data class WaSyncClientIdRequest(
    val handle: Long,
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class WaSyncCancelled(
    /** False: already gone, or going out right now. */
    val cancelled: Boolean,
)

@Serializable
data class WaSyncRetried(
    val retried: Boolean,
)

@Serializable
data class WaSyncOutboxEditRequest(
    val handle: Long,
    @SerialName("client_id") val clientId: String,
    /** The new text (or caption). */
    val text: String,
)

@Serializable
data class WaSyncOutboxAnswer(
    val items: List<WaSyncOutboxItem>,
)

// ---- media

@Serializable
data class WaSyncMediaSetRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val chat: String,
    val id: String,
    /** The thumbnail bytes (standard base64); kept with the message for good. */
    @SerialName("thumbnail_b64") @EncodeDefault(NEVER) val thumbnailB64: String? = null,
    /** The downloaded full file, with its size in [bytes]. */
    @EncodeDefault(NEVER) val path: String? = null,
    @EncodeDefault(NEVER) val bytes: Long? = null,
)

@Serializable
data class WaSyncMediaRequest(
    val handle: Long,
    val chat: String,
    val id: String,
    /** The media is being opened now: its file becomes the most recently used. */
    @SerialName("used_ms") @EncodeDefault(NEVER) val usedMs: Long? = null,
)

@Serializable
data class WaSyncMedia(
    @SerialName("thumbnail_b64") val thumbnailB64: String?,
    /** The cached full file; null when not downloaded or evicted. */
    val path: String?,
    val bytes: Long?,
)

@Serializable
data class WaSyncEvictRequest(
    val handle: Long,
    /** The cap from Settings. */
    @SerialName("max_bytes") val maxBytes: Long,
)

/** A file the cache no longer counts: delete it. */
@Serializable
data class WaSyncEvicted(
    val chat: String,
    val id: String,
    val path: String,
    val bytes: Long,
)

@Serializable
data class WaSyncEviction(
    /** Least recently used first. */
    val evicted: List<WaSyncEvicted>,
    @SerialName("bytes_left") val bytesLeft: Long,
)
