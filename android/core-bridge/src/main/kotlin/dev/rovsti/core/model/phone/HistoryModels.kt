@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.phone

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// `recents.*`, `call_log.*`, `call_notes.*`, `call_stats.*`: the call history. See `crates/rovsti_ffi/src/ops/phone/log.rs`.

// ---- recents.*

@Serializable
data class GroupRequest(
    val entries: List<CallLogEntry>,
)

/** Answer of `recents.group` and `recents.merge_extras`. */
@Serializable
data class RowsAnswer(
    val rows: List<RecentsRow>,
)

@Serializable
data class BuildRowsRequest(
    val rows: List<RecentsRow>,
    @SerialName("now_ms") val nowMs: Long,
    val locale: LocaleTime,
)

@Serializable
data class BuildRowsAnswer(
    val views: List<RowView>,
)

/** `recents.refresh_delta` request: the rows on screen and the freshly grouped ones. */
@Serializable
data class RefreshDeltaRequest(
    val old: List<RecentsRow>,
    val new: List<RecentsRow>,
)

/** What changed at the top of the list: [fresh] new rows, [shift] rows pushed down. */
@Serializable
data class RefreshDelta(
    val fresh: Int,
    val shift: Int,
)

@Serializable
data class MergeExtrasRequest(
    val rows: List<RecentsRow>,
    val extras: List<ExtraCall>,
)

@Serializable
data class MissedExtrasRequest(
    @EncodeDefault(NEVER) val entries: List<CallLogEntry> = emptyList(),
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    @SerialName("last_seen_ms") val lastSeenMs: Long,
)

/** Unseen missed calls: [label] is the tab badge text, null for zero, "99+" above 99. */
@Serializable
data class MissedExtrasAnswer(
    val log: Int,
    val extras: Int,
    val total: Int,
    val label: String?,
)

@Serializable
data class HistoryRequest(
    val entries: List<CallLogEntry>,
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    val numbers: List<String>,
    val limit: Int,
)

@Serializable
data class BlockedHistoryRequest(
    val entries: List<CallLogEntry>,
    val numbers: List<String>,
    val limit: Int,
)

@Serializable
data class HistoryAnswer(
    val lines: List<HistoryLine>,
)

@Serializable
data class HistoryTextRequest(
    val line: HistoryLine,
    @SerialName("now_ms") val nowMs: Long,
    val locale: LocaleTime,
)

@Serializable
data class HistoryTextAnswer(
    val title: String,
    val detail: String,
)

@Serializable
data class SectionStartsRequest(
    val rows: List<RecentsRow>,
    val visible: List<Int>,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    /** With the time now the answer also holds the day label of each section. */
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
)

@Serializable
data class SectionStartsAnswer(
    val starts: List<Int>,
    /** "Today", "Yesterday", a weekday or a date for each of [starts]; empty when the request had no `now_ms`. */
    val labels: List<String> = emptyList(),
)

// ---- call_log.*

@Serializable
enum class RecentsFilter {
    @SerialName("all")
    All,

    @SerialName("missed")
    Missed,

    @SerialName("incoming")
    Incoming,

    @SerialName("outgoing")
    Outgoing,

    @SerialName("whatsapp")
    WhatsApp,

    @SerialName("with_notes")
    WithNotes,
}

/** `call_log.query` request: with [notes], a row also passes when a note written for it mentions the text. */
@Serializable
data class CallLogQueryRequest(
    val rows: List<RecentsRow>,
    val text: String,
    val filter: RecentsFilter,
    @EncodeDefault(NEVER) val notes: Notes? = null,
)

@Serializable
data class CallLogQueryAnswer(
    /** Positions in `rows` that pass, in input order. */
    val visible: List<Int>,
    @SerialName("empty_text") val emptyText: String,
)

@Serializable
data class FrequentRequest(
    val entries: List<CallLogEntry>,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("window_days") @EncodeDefault(NEVER) val windowDays: Long? = null,
    @EncodeDefault(NEVER) val limit: Int? = null,
    /** Contact ids already in Favorites. */
    @EncodeDefault(NEVER) val starred: List<Long> = emptyList(),
)

/** One person in the Frequent row. */
@Serializable
data class Frequent(
    @SerialName("number_raw") val numberRaw: String,
    val normalized: String,
    val name: String?,
    @SerialName("contact_id") val contactId: Long?,
    val count: Int,
    @SerialName("last_ms") val lastMs: Long,
)

@Serializable
data class FrequentAnswer(
    val people: List<Frequent>,
)

@Serializable
data class CallCountsRequest(
    val entries: List<CallLogEntry>,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("window_days") @EncodeDefault(NEVER) val windowDays: Long? = null,
)

@Serializable
data class CallCountOut(
    val id: Long,
    val count: Int,
)

/** Calls per contact id, sorted by id; hand it to `search.set_call_counts`. */
@Serializable
data class CallCountsAnswer(
    val counts: List<CallCountOut>,
)

@Serializable
data class IdsForNumbersRequest(
    val entries: List<CallLogEntry>,
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    val numbers: List<String>,
)

@Serializable
data class IdsForNumbersAnswer(
    val ids: List<Long>,
    @SerialName("blocked_ids") val blockedIds: List<Long>,
    @SerialName("extra_ids") val extraIds: List<String>,
)

@Serializable
data class PersonSelection(
    val numbers: List<String>,
    val who: String,
)

/** `call_log.csv` request; with [person] only that person's calls are exported. */
@Serializable
data class CallLogCsvRequest(
    val entries: List<CallLogEntry>,
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val notes: Notes? = null,
    @EncodeDefault(NEVER) val person: PersonSelection? = null,
)

/** Both null when the person has no call to export. */
@Serializable
data class CallLogCsvAnswer(
    @SerialName("file_name") val fileName: String?,
    val csv: String?,
)

@Serializable
enum class PruneAge {
    @SerialName("days30")
    Days30,

    @SerialName("days90")
    Days90,

    @SerialName("year")
    Year,
}

@Serializable
data class PrunePlanRequest(
    val age: PruneAge,
    val entries: List<CallLogEntry>,
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    @SerialName("now_ms") val nowMs: Long,
)

/** Which calls are older than [age]; [title] is null when there are none and [body] says so. */
@Serializable
data class PrunePlanAnswer(
    @SerialName("cutoff_ms") val cutoffMs: Long,
    @SerialName("log_ids") val logIds: List<Long>,
    @SerialName("call_ids") val callIds: List<String>,
    val count: Int,
    val title: String?,
    val body: String,
)

// ---- call_notes.*

@Serializable
data class CallNote(
    val number: String,
    @SerialName("at_ms") val atMs: Long,
    val text: String,
    @SerialName("edited_ms") val editedMs: Long,
)

/** The notes file: one note per call (number and start time). */
@Serializable
data class Notes(
    val schema: Int,
    val notes: List<CallNote>,
)

@Serializable
enum class SetOutcome {
    @SerialName("added")
    Added,

    @SerialName("updated")
    Updated,

    @SerialName("removed")
    Removed,

    @SerialName("unchanged")
    Unchanged,
}

/** `call_notes.set` request; empty [text] removes the note. */
@Serializable
data class NoteSetRequest(
    val notes: Notes,
    val number: String,
    @SerialName("at_ms") val atMs: Long,
    val text: String,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class NoteSetAnswer(
    val outcome: SetOutcome,
    val notes: Notes,
)

@Serializable
data class NoteRemoveRequest(
    val notes: Notes,
    val number: String,
    @SerialName("at_ms") val atMs: Long,
)

/** [removed] is what Undo restores; null when no note matched. */
@Serializable
data class NoteRemoveAnswer(
    val removed: CallNote?,
    val notes: Notes,
)

@Serializable
data class NoteRestoreRequest(
    val notes: Notes,
    val note: CallNote,
)

@Serializable
data class NotesAnswer(
    val notes: Notes,
)

@Serializable
data class NoteRowsRequest(
    val notes: Notes,
    @EncodeDefault(NEVER) val query: String = "",
    @EncodeDefault(NEVER) val names: NamesByNumber = emptyMap(),
    /** The phone's clock and date style for the row dates; the core's default (UTC, 24 h) when null. */
    @EncodeDefault(NEVER) val locale: LocaleTime? = null,
)

@Serializable
data class NoteRow(
    /** Position in `Notes.notes`. */
    val index: Int,
    val number: String,
    @SerialName("at_ms") val atMs: Long,
    val preview: String,
    val title: String,
    val subtitle: String,
)

@Serializable
data class NoteRowsAnswer(
    val rows: List<NoteRow>,
    @SerialName("empty_text") val emptyText: String,
)

@Serializable
data class NoteCallRef(
    val number: String,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class NotesForCallsRequest(
    val notes: Notes,
    val calls: List<NoteCallRef>,
)

@Serializable
data class NotesForCallsAnswer(
    val flags: List<Boolean>,
)

@Serializable
data class CallDetailsRequest(
    val line: HistoryLine,
    val number: String,
    @EncodeDefault(NEVER) val sim: String? = null,
    val locale: LocaleTime,
    @EncodeDefault(NEVER) val note: String? = null,
    @EncodeDefault(NEVER) val who: String? = null,
)

@Serializable
data class CallDetails(
    val title: String,
    @SerialName("when") val whenText: String,
    val duration: String?,
    val channel: String,
    val number: String,
)

@Serializable
data class CallDetailsAnswer(
    val details: CallDetails,
    @SerialName("copy_text") val copyText: String,
    @SerialName("share_text") val shareText: String,
)

@Serializable
data class NotesExportRequest(
    val notes: Notes,
    @EncodeDefault(NEVER) val names: NamesByNumber = emptyMap(),
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class NotesExportAnswer(
    val text: String,
    val csv: String,
    @SerialName("file_name") val fileName: String,
)

// ---- call_stats.*

@Serializable
enum class StatRange {
    @SerialName("week")
    Week,

    @SerialName("days7")
    Days7,

    @SerialName("weeks8")
    Weeks8,
}

@Serializable
data class Totals(
    val calls: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    @SerialName("talk_s") val talkS: Long,
    @SerialName("longest_s") val longestS: Long,
)

/** One bar of the chart: a day, or a week for the 8-week range. */
@Serializable
data class DayBar(
    @SerialName("day_start_ms") val dayStartMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val label: String,
    @SerialName("talk_s") val talkS: Long,
    val calls: Int,
)

@Serializable
data class PersonStat(
    val key: String,
    val name: String?,
    val number: String,
    val totals: Totals,
)

@Serializable
data class OverviewRequest(
    val entries: List<CallLogEntry>,
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    @EncodeDefault(NEVER) val range: StatRange? = null,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @EncodeDefault(NEVER) val top: Int? = null,
)

@Serializable
data class OverviewAnswer(
    @SerialName("since_ms") val sinceMs: Long,
    @SerialName("range_label") val rangeLabel: String,
    val totals: Totals,
    val summary: String,
    val talk: String,
    val bars: List<DayBar>,
    val people: List<PersonStat>,
    @SerialName("share_text") val shareText: String,
    val csv: String,
    @SerialName("csv_file_name") val csvFileName: String,
)

@Serializable
enum class StatDir {
    @SerialName("incoming")
    Incoming,

    @SerialName("outgoing")
    Outgoing,

    @SerialName("missed")
    Missed,
}

@Serializable
data class StatCall(
    val number: String,
    val name: String?,
    @SerialName("date_ms") val dateMs: Long,
    @SerialName("duration_s") val durationS: Long,
    val dir: StatDir,
    val whatsapp: Boolean,
)

@Serializable
data class PersonStatsRequest(
    val entries: List<CallLogEntry>,
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    val numbers: List<String>,
    @EncodeDefault(NEVER) val range: StatRange? = null,
    @EncodeDefault(NEVER) val who: String = "",
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class PersonStatsAnswer(
    val all: Totals,
    @SerialName("in_range") val inRange: Totals,
    val calls: List<StatCall>,
    @SerialName("share_text") val shareText: String,
)

@Serializable
data class BucketRequest(
    val entries: List<CallLogEntry>,
    @EncodeDefault(NEVER) val extras: List<ExtraCall> = emptyList(),
    val bar: DayBar,
    val range: StatRange,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    val hour12: Boolean,
)

@Serializable
data class BucketLine(
    val call: StatCall,
    val title: String,
    val detail: String,
)

@Serializable
data class BucketAnswer(
    val title: String,
    val summary: String,
    val lines: List<BucketLine>,
)
