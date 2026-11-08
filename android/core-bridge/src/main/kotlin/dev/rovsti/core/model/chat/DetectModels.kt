@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.chat

import dev.rovsti.core.model.phone.LocaleTime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `text_detect.detect`, `date_detect.detect`, `location_link.*`, `shared_card.*`, `message_info.rows`,
// `message_reminder.*` and `call_reminder.*` (E2.11b), mirroring the fixtures of the same names.
// Offsets into a body are UTF-16 units. Stability: `compose_stability.conf`.

@Serializable
data class TextDetectRequest(
    val body: String,
    /** At most this many numbers and addresses (10 when absent). */
    @EncodeDefault(NEVER) val max: Int? = null,
    /** Numbers of the chat's own counterpart: no Call pill for them. */
    @EncodeDefault(NEVER) val own: List<String> = emptyList(),
)

@Serializable
enum class FoundKind {
    @SerialName("phone")
    Phone,

    @SerialName("email")
    Email,
}

@Serializable
data class FoundItem(
    val kind: FoundKind,
    /** As written in the body. */
    val text: String,
)

@Serializable
enum class DetectActionKind {
    @SerialName("call")
    Call,

    @SerialName("message")
    Message,

    @SerialName("copy_number")
    CopyNumber,

    @SerialName("email")
    Email,

    @SerialName("copy_email")
    CopyEmail,
}

/** One tap pill under a message. */
@Serializable
data class DetectedAction(
    val kind: DetectActionKind,
    /** The number (as written for call and copy, dialled for message) or the address. */
    val value: String,
    /** The pill text. */
    val label: String,
)

@Serializable
data class TextDetectAnswer(
    val found: List<FoundItem>,
    val actions: List<DetectedAction>,
    /** Each found phone number reduced to what is dialled, in order. */
    val dialled: List<String>,
)

@Serializable
data class DateDetectRequest(
    val body: String,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @EncodeDefault(NEVER) val max: Int? = null,
    /** The chat title, for the calendar event title. */
    @EncodeDefault(NEVER) val chat: String? = null,
)

@Serializable
data class FoundDate(
    val start: Int,
    val end: Int,
    /** The matched text, what "Copy date" copies. */
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    /** One hour after the start, one day when all day. */
    @SerialName("end_ms") val endMs: Long,
    @SerialName("all_day") val allDay: Boolean,
    /** `Thu 12 Nov, 15:00` or `Thu 12 Nov 2026`. */
    val label: String,
)

@Serializable
data class DateDetectAnswer(
    val dates: List<FoundDate>,
    /** `{chat}: {first 40 characters}` for the calendar insert. */
    @SerialName("event_title") val eventTitle: String,
)

@Serializable
data class PlacesRequest(
    val text: String,
    @EncodeDefault(NEVER) val max: Int? = null,
)

@Serializable
data class FoundPlace(
    val lat: Double,
    val lon: Double,
    val label: String?,
    /** The link inside the text. */
    val start: Int,
    val end: Int,
    /** `geo:` URL for a maps app. */
    @SerialName("geo_url") val geoUrl: String,
    /** `41.008200, 28.978400`, for the clipboard. */
    val coords: String,
)

@Serializable
data class PlacesAnswer(
    val places: List<FoundPlace>,
)

@Serializable
data class ShareLocationRequest(
    @EncodeDefault(NEVER) val name: String? = null,
    @EncodeDefault(NEVER) val address: String? = null,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class ShareLocationAnswer(
    /** Finite, in range and not the (0, 0) placeholder. */
    val valid: Boolean,
    @SerialName("osm_url") val osmUrl: String,
    @SerialName("geo_url") val geoUrl: String,
    val coords: String,
    /** Name, address and the link, one per line. */
    val text: String,
)

@Serializable
data class CardField(
    val value: String,
    val label: String,
)

/** A contact card received in a message. */
@Serializable
data class SharedCard(
    val name: String,
    val org: String,
    val numbers: List<CardField>,
    val emails: List<CardField>,
    val note: String,
)

@Serializable
data class SharedCardRequest(
    /** Shown when the vCard has no name. */
    @SerialName("display_name") val displayName: String,
    val vcard: String,
    /** Numbers listed in a bubble before "+N more" (2 when absent). */
    @SerialName("max_lines") @EncodeDefault(NEVER) val maxLines: Int? = null,
)

@Serializable
data class SharedCardAnswer(
    val card: SharedCard,
    /** The number to call or message first. */
    @SerialName("primary_number") val primaryNumber: String?,
    /** The bubble's number lines, then "+N more". */
    @SerialName("number_lines") val numberLines: List<String>,
    /** vCard 3.0 text (what Save .vcf writes). */
    val vcf: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("cache_file_name") val cacheFileName: String,
    /** Plain text for the share sheet. */
    @SerialName("share_text") val shareText: String,
    /** JSON text for the insert-contact intent. */
    @SerialName("insert_json") val insertJson: String,
)

@Serializable
data class MimeRequest(
    val mime: String,
)

@Serializable
data class MimeAnswer(
    /** The part is a vCard. */
    val vcard: Boolean,
    /** The largest vCard file that is read (bytes). */
    @SerialName("max_bytes") val maxBytes: Long,
)

@Serializable
enum class InfoChannel {
    @SerialName("whatsapp")
    WhatsApp,

    @SerialName("sms")
    Sms,

    @SerialName("mms")
    Mms,
}

@Serializable
enum class InfoState {
    @SerialName("pending")
    Pending,

    @SerialName("sent")
    Sent,

    @SerialName("delivered")
    Delivered,

    @SerialName("read")
    Read,

    @SerialName("played")
    Played,

    @SerialName("failed")
    Failed,

    @SerialName("received")
    Received,
}

/** Everything known about one message; unknown fields stay out. */
@Serializable
data class MessageInfo(
    @EncodeDefault(NEVER) val channel: InfoChannel? = null,
    @SerialName("at_ms") @EncodeDefault(NEVER) val atMs: Long = 0,
    @EncodeDefault(NEVER) val outgoing: Boolean = false,
    @EncodeDefault(NEVER) val state: InfoState? = null,
    @EncodeDefault(NEVER) val author: String? = null,
    @SerialName("edited_ms") @EncodeDefault(NEVER) val editedMs: Long? = null,
    @EncodeDefault(NEVER) val forwarded: Boolean = false,
    @EncodeDefault(NEVER) val starred: Boolean = false,
    @EncodeDefault(NEVER) val sim: String? = null,
    @EncodeDefault(NEVER) val text: String? = null,
    /** Short description, e.g. "Photo, 1280 × 720". */
    @EncodeDefault(NEVER) val media: String? = null,
)

@Serializable
data class MessageInfoRequest(
    val info: MessageInfo,
    val locale: LocaleTime,
)

@Serializable
data class InfoRow(
    val label: String,
    val value: String,
)

@Serializable
data class MessageInfoAnswer(
    val rows: List<InfoRow>,
    /** "Label: value" lines, for Copy. */
    val text: String,
)

/** The choices offered when setting a reminder. */
@Serializable
enum class ReminderSlot {
    @SerialName("in15_min")
    In15Min,

    @SerialName("in1_hour")
    In1Hour,

    @SerialName("tonight")
    Tonight,

    @SerialName("tomorrow_morning")
    TomorrowMorning,
}

@Serializable
enum class ReminderKind {
    /** A reminder on one message at a chosen slot. */
    @SerialName("message")
    Message,

    /** A reminder on a whole chat (from a notification) after `minutes`. */
    @SerialName("chat")
    Chat,
}

/** A pending reminder for one message or chat. */
@Serializable
data class MessageReminder(
    val id: Long,
    /** Chat tag: `sms:12` or `wa:<jid>`. */
    val tag: String,
    /** WhatsApp id, or `sms:12` / `mms:7`; empty for a chat reminder. */
    @SerialName("message_id") val messageId: String,
    @SerialName("message_at_ms") val messageAtMs: Long,
    @SerialName("at_ms") val atMs: Long,
    /** Chat title. */
    val title: String,
    val preview: String,
)

@Serializable
data class MakeReminderRequest(
    val kind: ReminderKind,
    val tag: String,
    @SerialName("message_id") @EncodeDefault(NEVER) val messageId: String = "",
    @SerialName("message_at_ms") @EncodeDefault(NEVER) val messageAtMs: Long = 0,
    val title: String,
    val preview: String,
    @EncodeDefault(NEVER) val slot: ReminderSlot = ReminderSlot.In1Hour,
    @EncodeDefault(NEVER) val minutes: Long = 0,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class MakeReminderAnswer(
    val reminder: MessageReminder,
    /** The reminder plus its notification texts, as the alarm layer stores it. */
    @SerialName("arm_json") val armJson: String,
    @SerialName("notif_title") val notifTitle: String,
    @SerialName("notif_body") val notifBody: String,
    /** "Reminder set for 18:05" / "Reminder set for tomorrow at 09:00". */
    @SerialName("set_text") val setText: String,
    /** The badge on the message, "Reminder 18:05". */
    val badge: String,
    /** `HH:MM` local time the reminder fires. */
    @SerialName("at_clock") val atClock: String,
)

@Serializable
data class ReminderSlotsRequest(
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class ReminderSlotOption(
    val key: ReminderSlot,
    val label: String,
    @SerialName("due_ms") val dueMs: Long,
)

@Serializable
data class ReminderSlotsAnswer(
    val slots: List<ReminderSlotOption>,
)

@Serializable
data class ReminderListRequest(
    /** The stored list (JSON text); bad JSON is an empty list and bad entries are skipped. */
    val json: String,
    @SerialName("message_id") @EncodeDefault(NEVER) val messageId: String? = null,
    /** A `message_reminder_fired` payload (`{"id": 5}`). */
    @EncodeDefault(NEVER) val fired: String? = null,
)

@Serializable
data class ReminderListAnswer(
    val reminders: List<MessageReminder>,
    /** The reminder armed for `messageId`. */
    @SerialName("for_message") val forMessage: MessageReminder?,
    @SerialName("fired_id") val firedId: Long?,
)

/** A "call back later" reminder of the Phone app. */
@Serializable
data class CallReminder(
    val id: Long,
    val number: String,
    val name: String?,
    @SerialName("at_ms") val atMs: Long,
    @SerialName("created_ms") val createdMs: Long,
    /** Set from a missed call, so the notification can say so. */
    val missed: Boolean,
)

@Serializable
data class CallReminderRequest(
    val number: String,
    @EncodeDefault(NEVER) val name: String? = null,
    val slot: ReminderSlot,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class CallReminderAnswer(
    val reminder: CallReminder,
    @SerialName("arm_json") val armJson: String,
    /** "Call back Anna", or the grouped number. */
    val title: String,
    val body: String,
    /** "Reminder at 18:05" / "Reminder tomorrow at 09:00". */
    @SerialName("set_text") val setText: String,
    @SerialName("at_clock") val atClock: String,
)

@Serializable
data class CallReminderListRequest(
    val json: String,
    @EncodeDefault(NEVER) val number: String? = null,
)

@Serializable
data class CallReminderListAnswer(
    val reminders: List<CallReminder>,
    /** The reminder armed for `number`, matched by parsed number. */
    @SerialName("for_number") val forNumber: CallReminder?,
)
