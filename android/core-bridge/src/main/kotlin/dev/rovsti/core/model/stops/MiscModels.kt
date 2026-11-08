@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// The smaller `stops.*` ops (E2.12): preferences, the arrival message, alarm, polling, GTFS, coordinates, measure,
// backup. See `crates/rovsti_ffi/src/ops/stops/misc.rs`.

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
    @SerialName("default_tile_url") val defaultTileUrl: String,
    val attribution: String,
)

/** Fills `{stop}` and `{min}`; without [etaS] `{min}` becomes "a few". */
@Serializable
data class NoticeRenderRequest(
    val template: String,
    val stop: String,
    @SerialName("eta_s") @EncodeDefault(NEVER) val etaS: Long? = null,
)

/** A template that cannot be saved answers `RustError.Invalid` with the reason as its message. */
@Serializable
data class NoticeValidateRequest(
    val template: String,
)

@Serializable
data class NoticeValidateAnswer(
    @SerialName("max_chars") val maxChars: Int,
    val trimmed: String,
)

@Serializable
data class AlarmPlanRequest(
    val prefs: StopsPrefs,
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

@Serializable
data class CoordFormatRequest(
    val at: Coord,
    @EncodeDefault(NEVER) val label: String? = null,
    /** Where the user is, for the "1.2 km from you, NE" line. */
    @EncodeDefault(NEVER) val here: Coord? = null,
    @EncodeDefault(NEVER) val zoom: Double? = null,
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
)
