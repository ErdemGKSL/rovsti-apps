@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.chat

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `thread_marks.edit`, `mute.*`, `starred.*`, `quick_replies.*` and `forward.*` (E2.11b), mirroring
// `crates/rovsti_ffi/fixtures/{thread_marks,mute,starred,quick_replies,forward}.*`. Stability: `compose_stability.conf`.

/** Local pinned / archived / unread marks of SMS threads, keyed by thread id. */
@Serializable
data class SmsMarks(
    val pinned: List<Long>,
    val archived: List<Long>,
    val unread: List<Long>,
) {
    companion object {
        val None = SmsMarks(emptyList(), emptyList(), emptyList())
    }
}

@Serializable
enum class MarksAction {
    @SerialName("pin")
    Pin,

    @SerialName("unpin")
    Unpin,

    @SerialName("archive")
    Archive,

    @SerialName("unarchive")
    Unarchive,

    @SerialName("mark_unread")
    MarkUnread,

    /** Clears the unread mark (on open, Mark read). */
    @SerialName("mark_read")
    MarkRead,

    /** Drops every mark of a deleted thread. */
    @SerialName("forget")
    Forget,

    /** Moves every mark from `thread` to `newThread`. */
    @SerialName("rekey")
    Rekey,
}

@Serializable
data class MarksRequest(
    @EncodeDefault(NEVER) val marks: SmsMarks = SmsMarks.None,
    val action: MarksAction,
    val thread: Long,
    @SerialName("new_thread") @EncodeDefault(NEVER) val newThread: Long? = null,
)

@Serializable
data class MarksAnswer(
    val marks: SmsMarks,
    val changed: Boolean,
    /** `too_many_pins` when a pin was refused (the cap is 5). */
    val refused: String?,
    /** The pill to show. */
    val notice: String?,
)

@Serializable
data class MuteChoicesRequest(
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class MuteChoice(
    /** `eight_hours`, `week` or `always`. */
    val key: String,
    val label: String,
    /** When the mute ends; `null` means never. */
    @SerialName("until_ms") val untilMs: Long?,
)

@Serializable
data class MuteChoicesAnswer(
    val choices: List<MuteChoice>,
)

@Serializable
data class MuteStatusRequest(
    /** When the mute ends; absent is "always". */
    @SerialName("until_ms") @EncodeDefault(NEVER) val untilMs: Long? = null,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class MuteStatusAnswer(
    /** The pill: "Muted", "Muted until 18:05", "Muted until Sat 14 Nov 09:00". */
    val notice: String,
    /** The line under the chat title; `null` for "always" or a mute that has ended. */
    val header: String?,
    val active: Boolean,
)

@Serializable
data class StarCandidate(
    val id: String,
    val author: String,
    val preview: String,
    @SerialName("at_ms") val atMs: Long,
    val starred: Boolean,
)

@Serializable
data class StarredRowsRequest(
    val items: List<StarCandidate>,
    /** Only part of the chat is loaded. */
    @EncodeDefault(NEVER) val partial: Boolean = false,
)

@Serializable
data class StarredRow(
    val id: String,
    val author: String,
    /** One line, cut at 120 characters. */
    val preview: String,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class StarredRowsAnswer(
    /** Starred messages only, newest first. */
    val rows: List<StarredRow>,
    @SerialName("empty_text") val emptyText: String,
)

/** A message starred on this device (SMS/MMS have no provider star). */
@Serializable
data class LocalStar(
    @SerialName("thread_id") val threadId: String,
    @SerialName("at_ms") val atMs: Long,
    val author: String,
    val preview: String,
)

@Serializable
enum class LocalStarAction {
    /** Just read: the rows and counts of `threadId`. */
    @SerialName("show")
    Show,

    /** Stars `id` with `star`. */
    @SerialName("set")
    Set,

    /** Unstars `id`. */
    @SerialName("unset")
    Unset,

    /** Drops every star of a deleted thread. */
    @SerialName("forget_thread")
    ForgetThread,
}

@Serializable
data class LocalStarsRequest(
    /** Local stars by provider id (`sms:12`). */
    @EncodeDefault(NEVER) val stars: Map<String, LocalStar> = emptyMap(),
    val action: LocalStarAction,
    @EncodeDefault(NEVER) val id: String? = null,
    @EncodeDefault(NEVER) val star: LocalStar? = null,
    @SerialName("thread_id") @EncodeDefault(NEVER) val threadId: String? = null,
    @EncodeDefault(NEVER) val partial: Boolean = false,
)

@Serializable
data class LocalStarsAnswer(
    val stars: Map<String, LocalStar>,
    val changed: Boolean,
    /** Whether `id` is starred afterwards. */
    val starred: Boolean,
    /** The rows of `threadId`, newest first. */
    val rows: List<StarredRow>,
    @SerialName("thread_count") val threadCount: Int,
    val total: Int,
    @SerialName("empty_text") val emptyText: String,
)

@Serializable
enum class RepliesAction {
    /** The list as it is (the defaults when `list` is absent). */
    @SerialName("show")
    Show,

    @SerialName("add")
    Add,

    @SerialName("remove")
    Remove,

    /** Puts a removed reply back (Undo). */
    @SerialName("restore")
    Restore,

    /** Swaps the reply with the one above. */
    @SerialName("move_up")
    MoveUp,
}

@Serializable
data class RepliesRequest(
    /** The stored list; the defaults of a fresh install when absent. */
    @EncodeDefault(NEVER) val list: List<String>? = null,
    val action: RepliesAction,
    @EncodeDefault(NEVER) val text: String = "",
    @EncodeDefault(NEVER) val index: Int = 0,
)

@Serializable
data class RepliesAnswer(
    val list: List<String>,
    /** The chip texts, one line each, cut at 28 characters. */
    val chips: List<String>,
    /** Where an added reply landed. */
    val index: Int?,
    /** The reply a remove took out (for Undo). */
    val removed: String?,
    val changed: Boolean,
    @SerialName("max_replies") val maxReplies: Int,
    @SerialName("max_chars") val maxChars: Int,
)

@Serializable
data class InsertReplyRequest(
    val draft: String,
    val reply: String,
)

@Serializable
data class InsertReplyAnswer(
    /** The draft after tapping the chip. */
    val draft: String,
)

/** The channel of the chat a message is forwarded to. */
@Serializable
enum class ForwardChannel {
    @SerialName("sms")
    Sms,

    @SerialName("whatsapp")
    WhatsApp,
}

@Serializable
enum class ForwardKind {
    @SerialName("text")
    Text,

    @SerialName("picture")
    Picture,

    /** Blank text, or SMS text over 5 000 characters. */
    @SerialName("nothing")
    Nothing,
}

@Serializable
data class ForwardPlanRequest(
    /** `Text` or `Picture`. */
    val kind: ForwardKind,
    @EncodeDefault(NEVER) val text: String? = null,
    @EncodeDefault(NEVER) val caption: String? = null,
    val to: ForwardChannel,
)

@Serializable
data class ForwardPlanAnswer(
    val kind: ForwardKind,
    val text: String?,
    val caption: String?,
    @SerialName("max_sms_chars") val maxSmsChars: Int,
)

@Serializable
data class ForwardWordsRequest(
    val title: String,
    val to: ForwardChannel,
    /** The person offers both channels in the chooser. */
    @EncodeDefault(NEVER) val both: Boolean = false,
    val ok: Boolean,
)

@Serializable
data class ForwardWordsAnswer(
    /** "Ada" or "Ada · WhatsApp". */
    @SerialName("target_label") val targetLabel: String,
    /** "Forwarded to Ada" or "Couldn't forward to Ada". */
    val notice: String,
)
