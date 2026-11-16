@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// The smaller `stops.*` ops (E2.12): preferences, the arrival message, alarm, polling, GTFS, coordinates, measure,
// backup. See `crates/rosty_ffi/src/ops/stops/misc.rs`.

/** One named edit of the preferences; the rules stay in Rust and Kotlin saves the result. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface PrefsEdit {
    /** Sound and vibration cannot both be off: the last one on stays (`changed` false). */
    @Serializable
    @SerialName("toggle_sound")
    data object ToggleSound : PrefsEdit

    @Serializable
    @SerialName("toggle_vibrate")
    data object ToggleVibrate : PrefsEdit

    @Serializable
    @SerialName("next_snooze")
    data object NextSnooze : PrefsEdit

    @Serializable
    @SerialName("next_tile_cache")
    data object NextTileCache : PrefsEdit

    @Serializable
    @SerialName("next_appearance")
    data object NextAppearance : PrefsEdit

    @Serializable
    @SerialName("next_vibration")
    data object NextVibration : PrefsEdit

    @Serializable
    @SerialName("next_volume")
    data object NextVolume : PrefsEdit

    @Serializable
    @SerialName("alarm_defaults")
    data object AlarmDefaults : PrefsEdit

    /** An empty or over-long template answers `RustError.Invalid`. */
    @Serializable
    @SerialName("set_template")
    data class SetTemplate(
        val text: String,
    ) : PrefsEdit

    @Serializable
    @SerialName("set_notice")
    data class SetNotice(
        val chat: String,
        @SerialName("chat_name") val chatName: String,
    ) : PrefsEdit

    @Serializable
    @SerialName("set_notice_when")
    data class SetNoticeWhen(
        @SerialName("when") val sendWhen: NoticeWhen,
    ) : PrefsEdit

    @Serializable
    @SerialName("clear_notice")
    data object ClearNotice : PrefsEdit

    /** A null or blank URL restores the default server; a bad one answers `RustError.Invalid`. */
    @Serializable
    @SerialName("set_tile_url")
    data class SetTileUrl(
        val url: String?,
    ) : PrefsEdit

    @Serializable
    @SerialName("set_sound")
    data class SetSound(
        val uri: String,
        val title: String,
    ) : PrefsEdit

    @Serializable
    @SerialName("clear_sound")
    data object ClearSound : PrefsEdit

    @Serializable
    @SerialName("set_show_saved")
    data class SetShowSaved(
        val value: Boolean,
    ) : PrefsEdit
}

@Serializable
data class PrefsEditRequest(
    val prefs: StopsPrefs,
    val edit: PrefsEdit,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

@Serializable
data class PrefsEditAnswer(
    val prefs: StopsPrefs,
    val changed: Boolean,
    @SerialName("alarm_is_default") val alarmIsDefault: Boolean,
    @SerialName("alarm_sound_label") val alarmSoundLabel: String,
    @SerialName("tile_cache_bytes") val tileCacheBytes: Long,
    /** The server the map uses now (the default when none is set). */
    @SerialName("tile_url") val tileUrl: String,
)

@Serializable
data class AppearanceChoice(
    val id: Appearance,
    val label: String,
)

@Serializable
data class VibrationChoice(
    val id: VibrationPattern,
    val label: String,
    @SerialName("pattern_ms") val patternMs: List<Long>,
    @SerialName("preview_ms") val previewMs: List<Long>,
)

@Serializable
data class NoticeWhenChoice(
    val id: NoticeWhen,
    val label: String,
)

/** Request of `stops.prefs_choices`: only the language of the labels (absent for English). */
@Serializable
data class PrefsChoicesRequest(
    @EncodeDefault(NEVER) val locale: String? = null,
)

/** The fixed choices the settings page lists, with their labels. */
@Serializable
data class PrefsChoicesAnswer(
    @SerialName("snooze_min") val snoozeMin: List<Int>,
    @SerialName("tile_cache_mb") val tileCacheMb: List<Int>,
    @SerialName("notice_minutes") val noticeMinutes: List<Int>,
    @SerialName("volume_pct") val volumePct: List<Int>,
    @SerialName("min_volume_pct") val minVolumePct: Int,
    val appearance: List<AppearanceChoice>,
    val vibration: List<VibrationChoice>,
    @SerialName("notice_when") val noticeWhen: List<NoticeWhenChoice>,
    @SerialName("default_template") val defaultTemplate: String,
    /** The default message of every language: a stored message that is one of them was never rewritten. */
    @SerialName("default_templates") val defaultTemplates: List<String> = emptyList(),
    @SerialName("default_tile_url") val defaultTileUrl: String,
    val attribution: String,
)

/** Fills `{stop}` and `{min}`; without [etaS] `{min}` becomes "a few". */
@Serializable
data class NoticeRenderRequest(
    val template: String,
    val stop: String,
    @SerialName("eta_s") @EncodeDefault(NEVER) val etaS: Long? = null,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

/** A template that cannot be saved answers `RustError.Invalid` with the reason as its message. */
@Serializable
data class NoticeValidateRequest(
    val template: String,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

@Serializable
data class NoticeValidateAnswer(
    @SerialName("max_chars") val maxChars: Int,
    val trimmed: String,
)

/** One chat of `stops.notice_chats`, with what is known about the person behind it (each absent when unknown). */
@Serializable
data class NoticeChatInput(
    val jid: String,
    @EncodeDefault(NEVER) val group: Boolean = false,
    /** The saved contact name. */
    @EncodeDefault(NEVER) val contact: String? = null,
    /** The verified business name. */
    @EncodeDefault(NEVER) val verified: String? = null,
    /** The name the person set on WhatsApp. */
    @EncodeDefault(NEVER) val push: String? = null,
    /** The name stored with the chat (a group's subject). */
    @SerialName("chat_name") @EncodeDefault(NEVER) val chatName: String? = null,
)

@Serializable
data class NoticeChatsRequest(
    val chats: List<NoticeChatInput>,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

/**
 * A chat as the arriving-soon picker lists it: [name] by the shared rule (contact, verified business, push name,
 * the chat's own name, the number, else "WhatsApp user"), [tier] says which of them it is (`contact`, `verified`,
 * `push`, `stored_chat`, `number`, `unknown`), [number] is the second line (null for a group and a bare number).
 */
@Serializable
data class NamedNoticeChat(
    val jid: String,
    val name: String,
    val tier: String,
    val number: String?,
    val group: Boolean,
)

@Serializable
data class NoticeChatsAnswer(
    val chats: List<NamedNoticeChat>,
)

@Serializable
data class AlarmPlanRequest(
    val prefs: StopsPrefs,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

/** The plan plus the two flags the service reads when it rings. */
@Serializable
data class TestRing(
    @SerialName("volume_pct") val volumePct: Int,
    @SerialName("ramp_ms") val rampMs: Long,
    @SerialName("ring_timeout_ms") val ringTimeoutMs: Long,
    @SerialName("sound_order") val soundOrder: List<String>,
    @SerialName("vibrate_pattern_ms") val vibratePatternMs: List<Long>,
    val sound: Boolean,
    val vibrate: Boolean,
    @SerialName("user_sound_uri") @EncodeDefault(NEVER) val userSoundUri: String? = null,
)

@Serializable
data class AlarmPlanAnswer(
    val plan: AlarmPlan,
    @SerialName("test_ring") val testRing: TestRing,
    val sound: Boolean,
    val vibrate: Boolean,
    @SerialName("next_volume_pct") val nextVolumePct: Int,
    @SerialName("alarm_sound_label") val alarmSoundLabel: String,
)

@Serializable
data class PollRequest(
    @SerialName("line_m") @EncodeDefault(NEVER) val lineM: Double? = null,
    @SerialName("accuracy_m") @EncodeDefault(NEVER) val accuracyM: Double? = null,
)

/** How soon the location service should deliver the next fix. */
@Serializable
data class PollAnswer(
    @SerialName("interval_ms") val intervalMs: Long,
    @SerialName("retry_ms") val retryMs: Long,
    @SerialName("min_ms") val minMs: Long,
    @SerialName("max_ms") val maxMs: Long,
    @SerialName("idle_ms") val idleMs: Long,
)

/** One step of the trip's cadence (`stops.cadence`, AD-SA10); absent [assumedMps] plans a first fix. */
@Serializable
data class CadenceRequest(
    @SerialName("line_m") @EncodeDefault(NEVER) val lineM: Double? = null,
    @SerialName("accuracy_m") @EncodeDefault(NEVER) val accuracyM: Double? = null,
    @SerialName("notice_m") @EncodeDefault(NEVER) val noticeM: Double? = null,
    @SerialName("assumed_mps") @EncodeDefault(NEVER) val assumedMps: Double? = null,
    @SerialName("est_mps") @EncodeDefault(NEVER) val estMps: Double? = null,
    @SerialName("prev_ms") @EncodeDefault(NEVER) val prevMs: Long? = null,
    @SerialName("prev_high") @EncodeDefault(NEVER) val prevHigh: Boolean? = null,
)

/** The interval and priority of that step; [coldMs] is the old curve (`stops.poll`) for the same fix. */
@Serializable
data class CadenceAnswer(
    @SerialName("interval_ms") val intervalMs: Long,
    @SerialName("retry_ms") val retryMs: Long,
    @SerialName("high_accuracy") val highAccuracy: Boolean,
    @SerialName("assumed_mps") val assumedMps: Double,
    @SerialName("cold_ms") val coldMs: Long,
)

/** [zip] is a file path; with [save] the feed is written as `gtfs.json` for `stops.gtfs_search`. */
@Serializable
data class GtfsImportRequest(
    val zip: String,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val save: Boolean? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class FeedReport(
    val stops: Int,
    val routes: Int,
    val trips: Int,
    @SerialName("stop_times") val stopTimes: Long,
    val patterns: Int,
    @SerialName("ungrouped_trips") val ungroupedTrips: Int,
    @SerialName("skipped_rows") val skippedRows: Long,
)

@Serializable
data class GtfsImportAnswer(
    val report: FeedReport,
    val saved: Boolean,
    @SerialName("imported_ms") val importedMs: Long,
)

@Serializable
data class GtfsSearchRequest(
    val query: String,
    val limit: Int,
    @EncodeDefault(NEVER) val near: Coord? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** A line that calls at a stop. */
@Serializable
data class GtfsLine(
    val index: Int,
    @SerialName("route_short") val routeShort: String,
    @SerialName("route_long") val routeLong: String,
    val kind: Int,
    val headsign: String,
)

@Serializable
data class GtfsHit(
    val index: Int,
    val id: String,
    val name: String,
    val coord: Coord,
    @SerialName("distance_m") val distanceM: Double?,
    val patterns: List<GtfsLine>,
)

@Serializable
data class GtfsSearchAnswer(
    @SerialName("has_feed") val hasFeed: Boolean,
    @SerialName("imported_ms") val importedMs: Long?,
    val rows: List<GtfsHit>,
)

/** A latitude and longitude box in degrees; [west] greater than [east] crosses the antimeridian. */
@Serializable
data class GtfsBbox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/** What the map shows of the imported feed: the stops in [bbox] and, with [pattern], the line of that pattern. */
@Serializable
data class GtfsViewRequest(
    val bbox: GtfsBbox,
    /** The most stops to answer; those nearest the middle of the box win. */
    val cap: Int,
    @EncodeDefault(NEVER) val pattern: Int? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class GtfsViewStop(
    /** Index in the feed, as in [GtfsHit.index]. */
    val index: Int,
    val id: String,
    val name: String,
    val coord: Coord,
)

@Serializable
data class GtfsViewAnswer(
    @SerialName("has_feed") val hasFeed: Boolean,
    /** Stops inside the box before the cap. */
    val total: Int,
    val capped: Boolean,
    /** In feed order. */
    val stops: List<GtfsViewStop>,
    /** The stops of the pattern in travel order; null without a pattern and for one the feed does not have. */
    val line: List<Coord>?,
)

/** The lines at a stop, named by its feed index ([stop]) or its GTFS id ([stopId]); the index wins. */
@Serializable
data class GtfsLinesRequest(
    @EncodeDefault(NEVER) val stop: Int? = null,
    @SerialName("stop_id") @EncodeDefault(NEVER) val stopId: String? = null,
    /** The most lines to list; [GtfsLinesAnswer.maxLines] when absent. */
    @EncodeDefault(NEVER) val limit: Int? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

/** One line that calls at a stop and has a stop before it, as the stop sheet lists it. */
@Serializable
data class GtfsLineChoice(
    /** Goes on the picked point as [Destination.pattern]; `stops.gtfs_view` then answers its line. */
    val pattern: Int,
    /** "34", or the long name when the route has no short one. */
    val name: String,
    val headsign: String,
    /** Stops the line calls at before this one. */
    val before: Int,
    /** "Line 34 to Kadıköy". */
    val title: String,
    /** "3 stops earlier on this line". */
    val earlier: String,
)

@Serializable
data class GtfsLinesAnswer(
    @SerialName("has_feed") val hasFeed: Boolean,
    /** Null when the feed does not have the stop. */
    val stop: GtfsViewStop?,
    /** One per line and headsign, numbers before names. */
    val lines: List<GtfsLineChoice>,
    @SerialName("max_lines") val maxLines: Int,
)

/** The Settings row of the imported feed. [skippedRows] is [FeedReport.skippedRows] of the import just done. */
@Serializable
data class GtfsSummaryRequest(
    @SerialName("now_ms") val nowMs: Long,
    /** Seconds east of UTC of the phone's calendar. */
    @SerialName("tz_offset_s") @EncodeDefault(NEVER) val tzOffsetS: Int? = null,
    @SerialName("skipped_rows") @EncodeDefault(NEVER) val skippedRows: Long? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

@Serializable
data class GtfsSummaryAnswer(
    @SerialName("has_feed") val hasFeed: Boolean,
    /** "1 234 stops, 87 lines". */
    val summary: String?,
    /** "Imported 5 Nov", with the year when it is not this year and the skipped rows when there were any. */
    val imported: String?,
    @SerialName("imported_ms") val importedMs: Long?,
    val stops: Int,
    val routes: Int,
    val patterns: Int,
)

@Serializable
data class CoordFormatRequest(
    val at: Coord,
    @EncodeDefault(NEVER) val label: String? = null,
    /** Where the user is, for the "1.2 km from you, NE" line. */
    @EncodeDefault(NEVER) val here: Coord? = null,
    @EncodeDefault(NEVER) val zoom: Double? = null,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

@Serializable
data class CoordFormatRow(
    val id: String,
    val label: String,
    val text: String,
)

@Serializable
data class ViewLinks(
    @SerialName("osm_url") val osmUrl: String,
    @SerialName("geo_url") val geoUrl: String,
    @SerialName("share_text") val shareText: String,
)

@Serializable
data class CoordFormatAnswer(
    val formats: List<CoordFormatRow>,
    @SerialName("from_you") val fromYou: String?,
    val bearing: String?,
    val view: ViewLinks?,
    @SerialName("plus_code") val plusCode: String,
)

@Serializable
data class CoordParseRequest(
    val text: String,
)

@Serializable
data class CoordParseAnswer(
    val coord: Coord?,
)

@Serializable
data class MeasureRequest(
    val points: List<Coord>,
    @EncodeDefault(NEVER) val name: String? = null,
    @EncodeDefault(NEVER) val today: String? = null,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

@Serializable
data class MeasureGate(
    val a: Coord,
    val b: Coord,
)

@Serializable
data class MeasureAnswer(
    /** Points kept; the rest passed the cap. */
    val accepted: Int,
    @SerialName("legs_m") val legsM: List<Double>,
    @SerialName("total_m") val totalM: Double,
    val summary: String,
    val gpx: String,
    /** The first and last point as a gate, when they can form one. */
    val gate: MeasureGate?,
    @SerialName("file_name") val fileName: String?,
    @SerialName("max_points") val maxPoints: Int,
)

@Serializable
data class BackupExportRequest(
    val saved: SavedStops,
    val history: History,
    val prefs: StopsPrefs,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    val today: String,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

@Serializable
data class BackupExportAnswer(
    val text: String,
    @SerialName("file_name") val fileName: String,
    val summary: String,
)

/** A refused file answers `RustError.Invalid` with the pill text as its message and `detail.reason`. */
@Serializable
data class BackupParseRequest(
    val text: String,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)

@Serializable
data class BackupParseAnswer(
    val summary: String,
    @SerialName("created_ms") val createdMs: Long,
    val saved: SavedStops,
    val history: History,
    val prefs: StopsPrefs,
)

@Serializable
data class FormatDistanceRequest(
    val metres: Double,
    /** The language of the sentences in the answer (a locale tag); absent for English. */
    @EncodeDefault(NEVER) val locale: String? = null,
)
