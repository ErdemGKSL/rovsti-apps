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

/** `wa_sync.identity_numbers`: the phone numbers of the person behind an id. */
@Serializable
data class WaSyncNumbersAnswer(
    /** `+<digits>`: the current number first, then the former ones, newest first. */
    val numbers: List<String>,
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
    /**
     * `wa.status`, the whole answer: the account's own number and LID, the gateway's epoch, the account epoch and
     * the history report, taken in as `wa_sync.note_status` takes them.
     */
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
    /**
     * Refresh the chat list: a merge moved rows, the status named another gateway epoch than the stored one (the
     * stored `seq` is forgotten too) or another account.
     */
    val resync: Boolean,
    /**
     * Another account was linked (another own number, or a raised account epoch) and the store was emptied
     * ([WaSyncStatusNoted.accountChanged]).
     */
    @SerialName("account_changed") val accountChanged: Boolean,
    /** Media files of the old account to delete. */
    @SerialName("delete_files") @EncodeDefault(NEVER) val deleteFiles: List<String> = emptyList(),
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
    /**
     * Refresh the chat list: a `resync` came, a `hello`, `resync` or `state` named another gateway epoch than the
     * stored one (a gap: the stored `seq` was forgotten), history arrived, or the account changed.
     */
    val resync: Boolean,
    /** Chats whose rows changed. */
    val chats: List<String>,
    /** Client ids of outbox sends an echo confirmed. */
    val reconciled: List<String>,
    /** Chats whose key changed while these events were applied, oldest first: follow them. */
    @EncodeDefault(NEVER) val rekeyed: List<WaSyncRekeyed> = emptyList(),
    /** Pairs the events named that changed nothing, for the log. */
    @EncodeDefault(NEVER) val rejected: List<WaSyncRejected> = emptyList(),
    /**
     * An `account_changed` event, or a `state` event that named another own number or a raised account epoch:
     * another account was linked and the store was emptied ([WaSyncStatusNoted.accountChanged]).
     */
    @SerialName("account_changed") @EncodeDefault(NEVER) val accountChanged: Boolean = false,
    /** Media files of the old account to delete. */
    @SerialName("delete_files") @EncodeDefault(NEVER) val deleteFiles: List<String> = emptyList(),
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
    /**
     * Where the full file is in its download (`WaSyncStore.enqueueMedia`); null while nothing was ever asked for,
     * and for an outbox row. [mediaPath] is set while it is [WaSyncMediaState.Ready].
     */
    @SerialName("media_state") @EncodeDefault(NEVER) val mediaState: WaSyncMediaState? = null,
    /**
     * A view-once message ([MediaMeta.viewOnce]) that was opened on this device (`WaSyncStore.markViewed`): its
     * file is gone and is never fetched again. Show "Opened".
     */
    @EncodeDefault(NEVER) val viewed: Boolean = false,
    /** A received voice note whose played receipt was queued (`WaSyncStore.markPlayed`). */
    @EncodeDefault(NEVER) val played: Boolean = false,
    /**
     * The message reached this account's linked devices as a placeholder only ([kind] stays
     * [WaSyncKind.Unsupported]): a view-once message someone sent, a bot's or a hosted business's message. Show
     * "open it on your phone"; there is nothing to download.
     */
    @EncodeDefault(NEVER) val unavailable: Unavailable? = null,
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

// ---- the media of a chat, view once, played

/** `wa_sync.chat_media`: a page of the media of [chat], newest first. */
@Serializable
data class WaSyncChatMediaRequest(
    val handle: Long,
    val chat: String,
    /** 1 to 200. */
    val limit: Int,
    /** Only media older than this row. */
    @EncodeDefault(NEVER) val before: WaSyncCursor? = null,
    /** Only media newer than this row; alone, the page right above it. */
    @EncodeDefault(NEVER) val after: WaSyncCursor? = null,
    /** Image, Video, Document, Audio; empty: images and videos. */
    @EncodeDefault(NEVER) val kinds: List<WaSyncKind> = emptyList(),
)

/** A page of the media of one chat, newest first. */
@Serializable
data class WaSyncGalleryPage(
    val items: List<WaSyncMessage>,
    /** Older media exists: pass it as `before`. */
    @EncodeDefault(NEVER) val older: WaSyncCursor? = null,
    /** Newer media exists: pass it as `after`. */
    @EncodeDefault(NEVER) val newer: WaSyncCursor? = null,
)

@Serializable
data class WaSyncMediaViewedRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val chat: String,
    val id: String,
)

/** `wa_sync.media_viewed`: the view-once message is opened for good. */
@Serializable
data class WaSyncViewed(
    val handle: Long,
    /** The media row as it is now: [WaSyncMediaState.Gone]. */
    val media: WaSyncMediaEntry,
    /** The cached file the message had: delete it. */
    @EncodeDefault(NEVER) val delete: String? = null,
    /** This call marked the message; false when it was viewed before. */
    val first: Boolean,
)

@Serializable
data class WaSyncMarkPlayedRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    /** A fresh id per receipt; the same id twice is one row. */
    @SerialName("client_id") val clientId: String,
    val chat: String,
    /** The received voice note that was played. */
    val id: String,
)

/** `wa_sync.mark_played`. */
@Serializable
data class WaSyncPlayed(
    val handle: Long,
    /** The receipt waits in the outbox. False: the message was marked played before, nothing is sent again. */
    val queued: Boolean,
    @EncodeDefault(NEVER) val item: WaSyncOutboxItem? = null,
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
        /** A small JPEG preview, base64 (photo, video, document); it becomes the thumbnail of the sent message. */
        @SerialName("thumbnail_b64") @EncodeDefault(NEVER) val thumbnailB64: String? = null,
        @EncodeDefault(NEVER) val width: Int? = null,
        @EncodeDefault(NEVER) val height: Int? = null,
        /** [MediaWaveform.encode] of a voice note. */
        @SerialName("waveform_b64") @EncodeDefault(NEVER) val waveformB64: String? = null,
        /** A video sent as a GIF, or an animated sticker. */
        @EncodeDefault(NEVER) val animated: Boolean = false,
        /** Photo, video or audio only; the sent file is not kept as cached media. */
        @SerialName("view_once") @EncodeDefault(NEVER) val viewOnce: Boolean = false,
    ) : WaSyncAction {
        /** The options of the `wa.send_media_with` call that sends this row. */
        fun options(): MediaOptions =
            MediaOptions(
                quotedId = quotedId,
                fileName = fileName,
                seconds = seconds,
                voiceNote = voiceNote,
                thumbnailB64 = thumbnailB64,
                width = width,
                height = height,
                waveformB64 = waveformB64,
                animated = animated,
                viewOnce = viewOnce,
            )
    }

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

    /**
     * The played receipt of a received voice note. Queued by `WaSyncStore.markPlayed` only (the generic enqueue
     * refuses it); send it with `wa.mark_played {chat, sender, ids: [messageId]}`. [sender] is the author in a
     * group.
     */
    @Serializable
    @SerialName("mark_played")
    data class MarkPlayed(
        @SerialName("message_id") val messageId: String,
        @EncodeDefault(NEVER) val sender: String? = null,
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

/** Where the full file of a message is. */
@Serializable
enum class WaSyncMediaState {
    /** Nothing was asked for. */
    @SerialName("none")
    None,

    @SerialName("queued")
    Queued,

    @SerialName("downloading")
    Downloading,

    /** The file is cached. */
    @SerialName("ready")
    Ready,

    /** The cap took the file; the thumbnail stays and the file can be asked for again. */
    @SerialName("evicted")
    Evicted,

    /** Attempts ran out; a tap starts over. */
    @SerialName("failed")
    Failed,

    /** The gateway no longer has the file. */
    @SerialName("gone")
    Gone,
}

/** `wa_sync.media`: the thumbnail and the full file of a message. */
@Serializable
data class WaSyncMedia(
    @SerialName("thumbnail_b64") val thumbnailB64: String?,
    /** The cached full file; null unless [state] is [WaSyncMediaState.Ready]. */
    val path: String?,
    val bytes: Long?,
    val state: WaSyncMediaState = WaSyncMediaState.None,
    /** The size the gateway named. */
    @SerialName("size_expected") @EncodeDefault(NEVER) val sizeExpected: Long? = null,
    /** Downloads that failed since the file was last asked for. */
    val attempts: Int = 0,
    val pinned: Boolean = false,
    /** Where the user saved a copy. */
    @SerialName("saved_uri") @EncodeDefault(NEVER) val savedUri: String? = null,
    @SerialName("last_error") @EncodeDefault(NEVER) val lastError: String? = null,
)

/**
 * `wa_sync.media_evict`. [maxBytes] null: the stored cap ([WaSyncMediaCap]). [nowMs]: files used in the last 24
 * hours are spared; without it none is spared for that reason.
 */
@Serializable
data class WaSyncEvictRequest(
    val handle: Long,
    @SerialName("max_bytes") @EncodeDefault(NEVER) val maxBytes: Long? = null,
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
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
    /**
     * The part of [bytesLeft] that is never evicted (starred, pinned, saved, being sent, used in the last 24 hours);
     * more than the cap when those files alone are over it.
     */
    @SerialName("exempt_bytes") val exemptBytes: Long = 0,
)

/** The connection the device is on. Name the most restrictive that holds: Data Saver before roaming before mobile. */
@Serializable
enum class WaSyncNetwork {
    @SerialName("wifi")
    Wifi,

    @SerialName("mobile")
    Mobile,

    @SerialName("roaming")
    Roaming,

    /** Data Saver is on, or the network is metered and background data is restricted. */
    @SerialName("data_saver")
    DataSaver,

    @SerialName("none")
    None,
}

/** What the auto-download rules say about one file. */
@Serializable
enum class WaSyncMediaDecision {
    /** Download it without being asked. */
    @SerialName("auto")
    Auto,

    /** Download it when the user taps it. */
    @SerialName("on_tap")
    OnTap,

    /** Never download it into the cache (view-once media, a message without a file). */
    @SerialName("never")
    Never,
}

/** The media row of one message: where its full file is. Flags and counters that are false or 0 are left out. */
@Serializable
data class WaSyncMediaEntry(
    val chat: String,
    val id: String,
    val kind: WaSyncKind,
    @EncodeDefault(NEVER) val mime: String? = null,
    /** The size the gateway named. */
    @SerialName("size_expected") @EncodeDefault(NEVER) val sizeExpected: Long? = null,
    val state: WaSyncMediaState,
    /** The cached file; only while [state] is [WaSyncMediaState.Ready]. */
    @EncodeDefault(NEVER) val path: String? = null,
    @EncodeDefault(NEVER) val bytes: Long? = null,
    /** Hex, when the caller hashed the file. */
    @EncodeDefault(NEVER) val sha256: String? = null,
    @SerialName("used_ms") @EncodeDefault(NEVER) val usedMs: Long? = null,
    @EncodeDefault(NEVER) val pinned: Boolean = false,
    /** Where the user saved a copy. */
    @SerialName("saved_uri") @EncodeDefault(NEVER) val savedUri: String? = null,
    /** Asked for by a tap, not by the auto-download rules. */
    @SerialName("by_tap") @EncodeDefault(NEVER) val byTap: Boolean = false,
    /** Downloads that failed since the file was last asked for. */
    @EncodeDefault(NEVER) val attempts: Int = 0,
    /** When a queued download may be tried again; null: now. */
    @SerialName("next_attempt_ms") @EncodeDefault(NEVER) val nextAttemptMs: Long? = null,
    @SerialName("last_error") @EncodeDefault(NEVER) val lastError: String? = null,
)

/** `wa_sync.media_policy`: [freeBytes] null does not apply the 500 MiB floor. */
@Serializable
data class WaSyncMediaPolicyRequest(
    val handle: Long,
    val chat: String,
    val id: String,
    val network: WaSyncNetwork,
    @SerialName("free_bytes") @EncodeDefault(NEVER) val freeBytes: Long? = null,
)

@Serializable
data class WaSyncMediaPolicy(
    val decision: WaSyncMediaDecision,
)

/** `wa_sync.media_enqueue`: a [tap], or the auto-download rules under [network] (required without a tap). */
@Serializable
data class WaSyncMediaEnqueueRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val chat: String,
    val id: String,
    @EncodeDefault(NEVER) val tap: Boolean = false,
    @EncodeDefault(NEVER) val network: WaSyncNetwork? = null,
    @SerialName("free_bytes") @EncodeDefault(NEVER) val freeBytes: Long? = null,
)

@Serializable
data class WaSyncMediaQueued(
    val handle: Long,
    /** The file waits in the queue or is being downloaded. */
    val queued: Boolean,
    /** What the rules say about the file ([WaSyncMediaDecision.OnTap] for a tap). */
    val decision: WaSyncMediaDecision,
    val media: WaSyncMediaEntry,
)

/** `wa_sync.media_due`: with [network], automatic downloads the rules no longer allow stay queued. */
@Serializable
data class WaSyncMediaDueRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    /** The most downloads to hand out (4 when left out, at most 32). */
    @EncodeDefault(NEVER) val limit: Int? = null,
    @EncodeDefault(NEVER) val network: WaSyncNetwork? = null,
    @SerialName("free_bytes") @EncodeDefault(NEVER) val freeBytes: Long? = null,
)

@Serializable
data class WaSyncMediaDue(
    val handle: Long,
    /** Download these now, in this order (taps first); each must be answered with `settleMedia`. */
    val items: List<WaSyncMediaEntry>,
    /** Automatic downloads that wait for a better network or more free space. */
    val held: Int,
    /** When a download that waits for its next attempt (or an unanswered one) comes due. */
    @SerialName("next_wake_ms") @EncodeDefault(NEVER) val nextWakeMs: Long? = null,
)

/** How a download ended (`rosty_app_core::wa_sync::Fetched`). */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface WaSyncFetched {
    /** The file is in place: written to a `.part` file, synced and renamed. */
    @Serializable
    @SerialName("ready")
    data class Ready(
        val path: String,
        val bytes: Long,
        /** Hex, when the file was hashed. */
        @EncodeDefault(NEVER) val sha256: String? = null,
    ) : WaSyncFetched

    /** The attempt failed and may work later (no network, a timeout, a gateway error). */
    @Serializable
    @SerialName("failed")
    data class Failed(
        @EncodeDefault(NEVER) val error: String? = null,
    ) : WaSyncFetched

    /** The gateway no longer has the file. */
    @Serializable
    @SerialName("gone")
    data class Gone(
        @EncodeDefault(NEVER) val error: String? = null,
    ) : WaSyncFetched

    /** The user stopped the download. */
    @Serializable
    @SerialName("cancelled")
    data object Cancelled : WaSyncFetched
}

@Serializable
data class WaSyncMediaSettleRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val chat: String,
    val id: String,
    val outcome: WaSyncFetched,
)

@Serializable
data class WaSyncMediaSettled(
    val handle: Long,
    val media: WaSyncMediaEntry,
    /** A file the row named before and no longer does: delete it. */
    @EncodeDefault(NEVER) val replaced: String? = null,
)

/** `wa_sync.media_keep`: null leaves a value as it is; [savedUri] `""` forgets the saved copy. */
@Serializable
data class WaSyncMediaKeepRequest(
    val handle: Long,
    val chat: String,
    val id: String,
    @EncodeDefault(NEVER) val pinned: Boolean? = null,
    @SerialName("saved_uri") @EncodeDefault(NEVER) val savedUri: String? = null,
)

@Serializable
data class WaSyncMediaKept(
    val handle: Long,
    val media: WaSyncMediaEntry,
)

/**
 * `wa_sync.media_reconcile`. [files]: every file under the directories of full media and of outbox files (absolute
 * paths, not the thumbnails); null when the listing failed.
 */
@Serializable
data class WaSyncReconcileRequest(
    val handle: Long,
    @EncodeDefault(NEVER) val files: List<String>? = null,
)

/** A row that was `ready` without its file; it is `evicted` now. */
@Serializable
data class WaSyncMissing(
    val chat: String,
    val id: String,
    val path: String,
)

@Serializable
data class WaSyncReconciled(
    val handle: Long,
    /** Downloads that were cut off and are queued again. */
    val requeued: Int,
    val missing: List<WaSyncMissing>,
    /** Files to delete: unfinished downloads (`*.part`) and files nothing names. */
    val delete: List<String>,
)

@Serializable
data class WaSyncMediaStatsRequest(
    val handle: Long,
    /** Now: files used in the last 24 hours count as exempt. */
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
)

@Serializable
data class WaSyncUsage(
    val files: Int,
    val bytes: Long,
)

@Serializable
data class WaSyncKindUsage(
    val kind: WaSyncKind,
    val files: Int,
    val bytes: Long,
)

/** The media cache in numbers, for the settings screen. */
@Serializable
data class WaSyncMediaStats(
    /** Every cached file. */
    val total: WaSyncUsage,
    /** The cached files the cap never takes. */
    val exempt: WaSyncUsage,
    /** Largest first; kinds without a cached file are left out. */
    @SerialName("by_kind") val byKind: List<WaSyncKindUsage>,
    /** Files of unsent sends that are not cached files. */
    val outbox: WaSyncUsage,
    val queued: Int,
    val downloading: Int,
    val failed: Int,
    /** The cap; null: no limit. */
    @SerialName("cap_bytes") @EncodeDefault(NEVER) val capBytes: Long? = null,
    /** The exempt files alone are over the cap: eviction cannot bring the cache under it. */
    @SerialName("exempt_over_cap") val exemptOverCap: Boolean,
)

/** `wa_sync.media_cap`: sets [capBytes] or [noLimit] first when one is given, then answers the cap. */
@Serializable
data class WaSyncMediaCapRequest(
    val handle: Long,
    @SerialName("cap_bytes") @EncodeDefault(NEVER) val capBytes: Long? = null,
    @SerialName("no_limit") @EncodeDefault(NEVER) val noLimit: Boolean = false,
)

@Serializable
data class WaSyncMediaCap(
    val handle: Long,
    /** The cap in bytes; null: no limit. 512 MiB until it is set. */
    @SerialName("cap_bytes") val capBytes: Long?,
)

// ---- the gateway process, the linked account, history progress, unlink

/** How far the phone's history has arrived at the gateway. */
@Serializable
enum class WaSyncHistoryState {
    @SerialName("idle")
    Idle,

    @SerialName("syncing")
    Syncing,

    @SerialName("done")
    Done,
}

/** `InstanceStatus.history` as the gateway reports it. */
@Serializable
data class WaSyncHistoryReport(
    val state: WaSyncHistoryState,
    /** 0 to 100; null while the phone has not said. */
    @EncodeDefault(NEVER) val progress: Int? = null,
    @EncodeDefault(NEVER) val chats: Int = 0,
    @EncodeDefault(NEVER) val messages: Long = 0,
    @SerialName("oldest_ms") @EncodeDefault(NEVER) val oldestMs: Long? = null,
)

/** The history progress the store keeps (`wa_sync.history`). */
@Serializable
data class WaSyncHistory(
    val state: WaSyncHistoryState,
    /** 0 to 100. */
    @EncodeDefault(NEVER) val progress: Int? = null,
    /** Chats and messages the gateway holds so far. */
    val chats: Int,
    val messages: Long,
    /** The oldest message that arrived. */
    @SerialName("oldest_ms") @EncodeDefault(NEVER) val oldestMs: Long? = null,
    /** The kind of the last batch: `initial`, `recent`, `full`, `on_demand` or `other`. */
    @SerialName("sync_type") @EncodeDefault(NEVER) val syncType: String? = null,
    /** When this was last reported. */
    @SerialName("updated_ms") val updatedMs: Long,
)

@Serializable
data class WaSyncHistoryAnswer(
    /** Null before the gateway reported any. */
    val history: WaSyncHistory?,
)

/**
 * `wa_sync.note_status`: what the gateway says about itself and the linked account. Every part is optional: an old
 * gateway names none of it, and the socket's first frame names the [epoch] alone.
 */
@Serializable
data class WaSyncNoteStatusRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    /** One id per gateway process (`InstanceStatus.epoch`, the socket's first frame, a `resync`). */
    @EncodeDefault(NEVER) val epoch: String? = null,
    /** `InstanceStatus.account_epoch`: raised when another account is linked. */
    @SerialName("account_epoch") @EncodeDefault(NEVER) val accountEpoch: Int? = null,
    /** `InstanceStatus.own_jid`. */
    @SerialName("own_pn") @EncodeDefault(NEVER) val ownPn: String? = null,
    /** `InstanceStatus.own_lid`. */
    @SerialName("own_lid") @EncodeDefault(NEVER) val ownLid: String? = null,
    @EncodeDefault(NEVER) val history: WaSyncHistoryReport? = null,
)

@Serializable
data class WaSyncStatusNoted(
    val handle: Long,
    /**
     * The gateway is another process than the one the stored `seq` came from: subscribe from the start (`plan` now
     * answers `afterSeq` null) and refresh the chat list.
     */
    val gap: Boolean,
    /**
     * Another account is linked to the instance: the store was emptied. What is kept per chat outside the store
     * (drafts, mutes, the rekey cursor, names) belongs to the old account too; delete [deleteFiles] and the rest of
     * the instance's media directory.
     */
    @SerialName("account_changed") val accountChanged: Boolean,
    /** Media files of the old account: cached files and files unsent outbox rows named. */
    @SerialName("delete_files") @EncodeDefault(NEVER) val deleteFiles: List<String> = emptyList(),
    @EncodeDefault(NEVER) val rekeyed: List<WaSyncRekeyed> = emptyList(),
    @EncodeDefault(NEVER) val rejected: List<WaSyncRejected> = emptyList(),
    /** Refresh the chat list. */
    val resync: Boolean,
    /** The history progress as stored now. */
    @EncodeDefault(NEVER) val history: WaSyncHistory? = null,
)

/** `wa_sync.account_changed`: the gateway's `account_changed` event, at its place in the stream. */
@Serializable
data class WaSyncAccountChangedRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("old_pn") @EncodeDefault(NEVER) val oldPn: String? = null,
    @SerialName("new_pn") val newPn: String,
)

/** `wa_sync.wipe`: the open store behind [handle], or the store files of [instanceId] in [dir]. */
@Serializable
data class WaSyncWipeRequest(
    @EncodeDefault(NEVER) val handle: Long? = null,
    @SerialName("instance_id") @EncodeDefault(NEVER) val instanceId: String? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class WaSyncWiped(
    /** The store files that were deleted (the database, its `-wal` and `-shm`). */
    val deleted: List<String>,
    /** Media files the store named: delete them, and the rest of the instance's media directory. */
    @SerialName("media_files") val mediaFiles: List<String>,
)
