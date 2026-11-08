@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.phone

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Types several Phone ops share: the call log, its grouped rows, locale. Mirrors `rovsti_core::{wire, call_log,
// recents}`. Stability: `compose_stability.conf`.

/** `CallLog.Calls.TYPE` as the platform reports it. */
@Serializable
enum class CallLogKind {
    @SerialName("incoming")
    Incoming,

    @SerialName("outgoing")
    Outgoing,

    @SerialName("missed")
    Missed,

    @SerialName("rejected")
    Rejected,

    @SerialName("blocked")
    Blocked,

    @SerialName("voicemail")
    Voicemail,

    @SerialName("unknown")
    Unknown,
}

@Serializable
enum class BlockReason {
    @SerialName("user")
    User,

    @SerialName("system")
    System,

    @SerialName("unknown")
    Unknown,
}

/** One row pulled from the system call log. */
@Serializable
data class CallLogEntry(
    val id: Long,
    @SerialName("number_raw") val numberRaw: String,
    val normalized: String,
    val kind: CallLogKind,
    @SerialName("date_ms") val dateMs: Long,
    @SerialName("duration_s") @EncodeDefault(NEVER) val durationS: Long = 0,
    @SerialName("cached_name") @EncodeDefault(NEVER) val cachedName: String? = null,
    @SerialName("number_label") @EncodeDefault(NEVER) val numberLabel: String? = null,
    @SerialName("contact_id") @EncodeDefault(NEVER) val contactId: Long? = null,
    @SerialName("account_key") @EncodeDefault(NEVER) val accountKey: String? = null,
    @SerialName("block_reason") @EncodeDefault(NEVER) val blockReason: BlockReason? = null,
    @EncodeDefault(NEVER) val features: Long = 0,
)

/** The row type Recents shows. */
@Serializable
enum class CallType {
    @SerialName("incoming")
    Incoming,

    @SerialName("outgoing")
    Outgoing,

    @SerialName("missed")
    Missed,

    @SerialName("declined")
    Declined,

    @SerialName("voicemail")
    Voicemail,

    @SerialName("unknown")
    Unknown,
}

/** The stored call behind a row, for deleting or restoring it: a call-log id or a WhatsApp call id. */
@Serializable(with = EntryRefSerializer::class)
sealed interface EntryRef {
    data class Log(
        val id: Long,
    ) : EntryRef

    data class WhatsApp(
        val callId: String,
    ) : EntryRef
}

internal object EntryRefSerializer : ExternallyTagged<EntryRef>("EntryRef") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): EntryRef {
        val primitive = value as? JsonPrimitive ?: unknown(tag)
        return when (tag) {
            "log" -> EntryRef.Log(primitive.content.toLong())
            "whatsapp" -> EntryRef.WhatsApp(primitive.content)
            else -> unknown(tag)
        }
    }

    override fun toJson(value: EntryRef): JsonElement =
        when (value) {
            is EntryRef.Log -> tagged("log", JsonPrimitive(value.id))
            is EntryRef.WhatsApp -> tagged("whatsapp", JsonPrimitive(value.callId))
        }
}

/** One Recents line: a run of consecutive same-number, same-type calls (`recents.group`). */
@Serializable
data class RecentsRow(
    @SerialName("number_raw") val numberRaw: String,
    val normalized: String,
    @SerialName("call_type") val callType: CallType,
    val count: Int,
    @SerialName("first_ms") val firstMs: Long,
    @SerialName("last_ms") val lastMs: Long,
    val name: String?,
    val label: String?,
    @SerialName("contact_id") val contactId: Long?,
    val callable: Boolean,
    @SerialName("account_key") val accountKey: String?,
    val whatsapp: Boolean,
    val refs: List<EntryRef>,
)

/** A WhatsApp call kept by the app, shown beside the call log. */
@Serializable
data class ExtraCall(
    @SerialName("number_raw") val numberRaw: String,
    @SerialName("started_ms") val startedMs: Long,
    @SerialName("duration_s") val durationS: Long,
    val missed: Boolean,
    val outgoing: Boolean,
    val label: String?,
    @SerialName("call_id") val callId: String,
)

@Serializable
enum class DateOrder {
    @SerialName("day_month_year")
    DayMonthYear,

    @SerialName("month_day_year")
    MonthDayYear,

    @SerialName("year_month_day")
    YearMonthDay,
}

@Serializable
data class TimeFormat(
    val hour12: Boolean,
    @SerialName("date_order") val dateOrder: DateOrder,
)

/** What the device says about local time; ops never read the device clock themselves. */
@Serializable
data class LocaleTime(
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    val format: TimeFormat,
)

/** The glyph in front of a Recents subtitle. */
@Serializable
enum class Direction {
    @SerialName("incoming")
    Incoming,

    @SerialName("outgoing")
    Outgoing,

    @SerialName("missed")
    Missed,

    @SerialName("declined")
    Declined,

    @SerialName("voicemail")
    Voicemail,

    @SerialName("unknown")
    Unknown,
}

/** A Recents line with every string it draws or speaks ready (`recents.build`). */
@Serializable
data class RowView(
    val title: String,
    val count: String?,
    val subtitle: String,
    val time: String,
    val aria: String,
    val direction: Direction,
    val missed: Boolean,
    val callable: Boolean,
    val number: String,
    val hue: Int?,
    val monogram: String?,
    val named: Boolean,
    @SerialName("account_key") val accountKey: String?,
    val whatsapp: Boolean,
)

/** One call in a person's history. */
@Serializable
data class HistoryLine(
    val entry: EntryRef,
    val blocked: Boolean,
    @SerialName("call_type") val callType: CallType,
    val whatsapp: Boolean,
    @SerialName("date_ms") val dateMs: Long,
    @SerialName("duration_s") val durationS: Long,
    val number: String,
)

/** A WhatsApp or cellular number pair a number-based op takes: names for contacts by number as stored. */
typealias NamesByNumber = Map<String, String>
