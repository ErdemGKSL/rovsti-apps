@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// `stops.history_*` and `stops.stats` (E2.12). See `crates/rovsti_ffi/src/ops/stops/history.rs`.

/** [filter] is `all` (default), `arrived` or `cancelled`. */
@Serializable
data class HistoryListRequest(
    val history: History,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val query: String? = null,
    @EncodeDefault(NEVER) val filter: String? = null,
)

@Serializable
data class HistoryRow(
    /** Index in [History.records]; pass it to `stops.history_edit`. */
    val index: Int,
    val record: TripRecord,
    val title: String,
    @SerialName("summary_line") val summaryLine: String,
    @SerialName("duration_text") val durationText: String,
    @SerialName("snoozes_text") val snoozesText: String?,
)

@Serializable
data class HistoryListAnswer(
    val rows: List<HistoryRow>,
    val total: Int,
    val filter: String,
    @SerialName("next_filter") val nextFilter: String,
    @SerialName("filter_label") val filterLabel: String,
    @SerialName("next_filter_label") val nextFilterLabel: String,
    /** The trip that ended in the last 12 hours, for the "last trip" card. */
    @SerialName("last_trip") val lastTrip: HistoryRow?,
    @SerialName("max_records") val maxRecords: Int,
)

@Serializable
@JsonClassDiscriminator("kind")
sealed interface HistoryEdit {
    @Serializable
    @SerialName("push")
    data class Push(
        val record: TripRecord,
    ) : HistoryEdit

    /** Takes a record out (a swipe-delete with undo). */
    @Serializable
    @SerialName("take")
    data class Take(
        val index: Int,
    ) : HistoryEdit

    @Serializable
    @SerialName("put_back")
    data class PutBack(
        val index: Int,
        val record: TripRecord,
    ) : HistoryEdit

    @Serializable
    @SerialName("prune")
    data class Prune(
        val days: Int,
        @SerialName("now_ms") val nowMs: Long,
    ) : HistoryEdit

    @Serializable
    @SerialName("clear")
    data object Clear : HistoryEdit
}

@Serializable
data class HistoryEditRequest(
    val history: History,
    val edit: HistoryEdit,
)

@Serializable
data class HistoryEditAnswer(
    val history: History,
    val taken: TripRecord?,
    val removed: Int,
)

@Serializable
data class PrunePlanRequest(
    val history: History,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class PruneChoice(
    val days: Int,
    val count: Int,
    /** "Delete 12 trips older than 30 days". */
    val label: String,
    val enabled: Boolean,
)

@Serializable
data class PrunePlanAnswer(
    val choices: List<PruneChoice>,
)

@Serializable
data class UsageRequest(
    val history: History,
    val a: Coord,
    val b: Coord,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

/** How often a gate was used: [text] is "Used 4 times, last 5 Nov", null when never. */
@Serializable
data class UsageAnswer(
    val count: Int,
    @SerialName("last_ms") val lastMs: Long?,
    val text: String?,
)

@Serializable
data class RecentRequest(
    val history: History,
    val limit: Int,
)

@Serializable
data class RecentPair(
    val a: Endpoint,
    val b: Endpoint,
)

@Serializable
data class RecentAnswer(
    val pairs: List<RecentPair>,
    val points: List<Endpoint>,
)

/** [format] is `csv` or `gpx`; [indices] picks records (all when absent). */
@Serializable
data class HistoryExportRequest(
    val history: History,
    val format: String,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    val today: String,
    @EncodeDefault(NEVER) val indices: List<Int>? = null,
)

@Serializable
data class HistoryExportAnswer(
    val text: String,
    val count: Int,
    @SerialName("file_name") val fileName: String,
)

/** Small texts: a record's share text, a duration, a prune label. Send only what is needed. */
@Serializable
data class HistoryTextRequest(
    @EncodeDefault(NEVER) val record: TripRecord? = null,
    @SerialName("tz_offset_s") @EncodeDefault(NEVER) val tzOffsetS: Int? = null,
    @SerialName("duration_ms") @EncodeDefault(NEVER) val durationMs: Long? = null,
    @SerialName("prune_count") @EncodeDefault(NEVER) val pruneCount: Int? = null,
    @SerialName("prune_days") @EncodeDefault(NEVER) val pruneDays: Int? = null,
)

@Serializable
data class HistoryTextAnswer(
    val share: String?,
    val title: String?,
    val duration: String?,
    val prune: String?,
)

@Serializable
data class StatsRequest(
    val history: History,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class TopDestination(
    val label: String,
    val count: Int,
)

@Serializable
data class StatsAnswer(
    val trips: Int,
    val arrived: Int,
    val cancelled: Int,
    @SerialName("total_m") val totalM: Double,
    @SerialName("total_ms") val totalMs: Long,
    @SerialName("avg_ms") val avgMs: Long,
    @SerialName("last_30_days") val last30Days: Int,
    @SerialName("arrived_percent") val arrivedPercent: Int,
    @SerialName("total_text") val totalText: String,
    @SerialName("avg_text") val avgText: String,
    val top: List<TopDestination>,
)
