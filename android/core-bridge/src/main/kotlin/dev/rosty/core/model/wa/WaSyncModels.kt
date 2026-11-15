@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.wa

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
