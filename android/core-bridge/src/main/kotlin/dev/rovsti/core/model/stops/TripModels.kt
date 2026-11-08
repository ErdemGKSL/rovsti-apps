@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// `stops.trip_*`, `stops.crossing_*` and `stops.arrival_clock` (E2.12): the trip in progress behind a handle and the
// diameter checks. See `crates/rovsti_ffi/src/ops/stops/trip.rs`.

/** `stops.trip_start` request. With [dir] the trip is also written to `trip.json` (under the store lock). */
@Serializable
data class TripStartRequest(
    val id: Long,
    val a: Endpoint,
    val b: Endpoint,
    @SerialName("started_ms") val startedMs: Long,
    @EncodeDefault(NEVER) val notice: NoticePrefs? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** `stops.trip_restore` request: a [TripView.snapshot] or the content of `trip.json`. */
@Serializable
data class TripRestoreRequest(
    val trip: JsonElement,
)

/** The arrival message of a running trip. */
@Serializable
data class TripNotice(
    val chat: String,
    val sent: Boolean,
    val attempts: Int,
    @SerialName("gave_up") val gaveUp: Boolean,
)

/**
 * The trip as the card shows it. [handle] is set by `stops.trip_start` and `stops.trip_restore` only; [snapshot] is
 * the whole persisted trip (for `trip.json` or `stops.trip_restore`).
 */
@Serializable
data class TripView(
    @EncodeDefault(NEVER) val handle: Long? = null,
    val id: Long,
    val label: String,
    val a: Endpoint,
    val b: Endpoint,
    val state: TripState,
    @SerialName("started_ms") val startedMs: Long,
    val snoozes: Int,
    @SerialName("snoozes_left") val snoozesLeft: Int,
    @SerialName("ring_at_ms") val ringAtMs: Long?,
    val timeouts: Int,
    @SerialName("travelled_m") val travelledM: Double,
    val fixes: Int,
    @SerialName("length_m") val lengthM: Double,
    @SerialName("radius_m") val radiusM: Double,
    val center: Coord,
    val side: Side?,
    @SerialName("last_line_m") val lastLineM: Double?,
    val notice: TripNotice?,
    val snapshot: JsonElement,
)

/** The request of every op that only names the open trip. */
@Serializable
data class TripHandleRequest(
    val handle: Long,
)

/** A location fix as delivered by the platform. */
@Serializable
data class FixIn(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("at_ms") val atMs: Long,
    @SerialName("speed_mps") @EncodeDefault(NEVER) val speedMps: Double? = null,
)

@Serializable
data class TripFixRequest(
    val handle: Long,
    val fix: FixIn,
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** A position with its uncertainty, as shown on the map. */
@Serializable
data class FixPos(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
)

/** What the platform needs after a fix (`rovsti_geo::FixOut`). */
@Serializable
data class FixOut(
    val state: TripState,
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("next_fix_ms") val nextFixMs: Long,
    @SerialName("retry_ms") val retryMs: Long,
    @SerialName("high_accuracy") val highAccuracy: Boolean,
    val ring: Boolean,
    val title: String,
    val text: String,
    @SerialName("notice_sent") val noticeSent: Boolean,
    @SerialName("notice_failed") val noticeFailed: Boolean,
    val side: Side?,
    val inside: Boolean,
    @SerialName("circle_m") val circleM: Double?,
    val at: FixPos?,
)

/** [sendNotice] is the rendered arrival message to send now, if the rule is met. */
@Serializable
data class TripFixAnswer(
    val out: FixOut,
    @SerialName("send_notice") val sendNotice: String?,
    @SerialName("eta_min") val etaMin: Int?,
)

/** [result] is `sent`, `retry_later` or `give_up`. */
@Serializable
data class NoticeResultRequest(
    val handle: Long,
    val result: String,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class NoticeResultAnswer(
    val sent: Boolean,
    val failed: Boolean,
    val attempts: Int,
)

/** Request of `stops.trip_snooze` and `stops.trip_timeout`; minutes are clamped to 1..=15. */
@Serializable
data class SnoozeRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val minutes: Int,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class SnoozeAnswer(
    @SerialName("ring_at_ms") val ringAtMs: Long?,
    @SerialName("snoozes_left") val snoozesLeft: Int,
)

@Serializable
data class TimeoutAnswer(
    @SerialName("ring_at_ms") val ringAtMs: Long?,
    val timeouts: Int,
)

@Serializable
data class DismissRequest(
    val handle: Long,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class DismissAnswer(
    val state: TripState,
    val dismissed: Boolean,
)

@Serializable
data class StatusShareRequest(
    val handle: Long,
    @SerialName("line_m") @EncodeDefault(NEVER) val lineM: Double? = null,
    @SerialName("eta_min") @EncodeDefault(NEVER) val etaMin: Int? = null,
    @EncodeDefault(NEVER) val here: Coord? = null,
    @EncodeDefault(NEVER) val crossed: Boolean? = null,
    /** Local clock text from `stops.arrival_clock`. */
    @EncodeDefault(NEVER) val arrive: String? = null,
)

/** An op that answers one sentence. */
@Serializable
data class TextAnswer(
    val text: String,
)

/** [outcome] is `arrived` or `cancelled`. With [dir] the trip moves into `history.json`. */
@Serializable
data class TripEndRequest(
    val handle: Long,
    val outcome: String,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class TripEndAnswer(
    val record: TripRecord,
    val title: String,
    @SerialName("summary_line") val summaryLine: String,
    val recorded: Boolean,
)

@Serializable
data class ArrivalClockRequest(
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("eta_min") val etaMin: Int,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class ClockAnswer(
    val clock: String,
)

/** `stops.crossing_validate` request. A bad pair answers `RustError.Invalid` whose message is the sentence. */
@Serializable
data class CrossingValidateRequest(
    val a: Coord,
    val b: Coord,
)

@Serializable
data class CrossingValidateAnswer(
    @SerialName("length_m") val lengthM: Double,
    @SerialName("radius_m") val radiusM: Double,
    val center: Coord,
    @SerialName("min_m") val minM: Double,
    @SerialName("max_m") val maxM: Double,
)

@Serializable
data class CrossingReadRequest(
    val a: Coord,
    val b: Coord,
    val at: Coord,
    @SerialName("accuracy_m") @EncodeDefault(NEVER) val accuracyM: Double? = null,
    /** Points of the circle outline to draw; none by default. */
    @SerialName("outline_points") @EncodeDefault(NEVER) val outlinePoints: Int? = null,
)

/** [kind] is `side` (with [side]) or `on_line`. */
@Serializable
data class SideReading(
    val kind: String,
    val side: Side?,
)

@Serializable
data class CrossingReadAnswer(
    val reading: SideReading,
    @SerialName("offset_m") val offsetM: Double,
    @SerialName("line_m") val lineM: Double,
    @SerialName("circle_m") val circleM: Double,
    val contains: Boolean,
    val outline: List<Coord>,
    @SerialName("left_disc") val leftDisc: List<Coord>,
    @SerialName("right_disc") val rightDisc: List<Coord>,
)
