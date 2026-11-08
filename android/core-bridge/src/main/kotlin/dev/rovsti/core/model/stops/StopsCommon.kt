@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import dev.rovsti.core.model.phone.ExternallyTagged
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

// Shared types of `stops.*` and `view.*` (E2.12), mirroring `crates/rovsti_geo` and `rovsti_app_core::stops`. Optional
// request fields are nullable and left out when null (`NEVER`), so a request encodes exactly like its fixture.
// Stability: `compose_stability.conf`.

/** WGS84 latitude and longitude in degrees. */
@Serializable
data class Coord(
    val lat: Double,
    val lon: Double,
)

/** One end of a gate: a point with the name shown to the user. */
@Serializable
data class Endpoint(
    val label: String,
    val coord: Coord,
)

/** The state of the arrival alarm. `Arrived` rings until the user dismisses it. */
@Serializable
enum class TripState { Tracking, Arrived, Dismissed }

/** Which half of the circle a position is in, facing from A toward B. */
@Serializable
enum class Side {
    @SerialName("left")
    Left,

    @SerialName("right")
    Right,
}

@Serializable
enum class Outcome { Arrived, Cancelled }

/** The map colour of a saved stop. */
@Serializable
enum class StopColour {
    @SerialName("orange")
    Orange,

    @SerialName("red")
    Red,

    @SerialName("green")
    Green,

    @SerialName("blue")
    Blue,
}

@Serializable
enum class Appearance {
    @SerialName("auto")
    Auto,

    @SerialName("light")
    Light,

    @SerialName("dark")
    Dark,
}

@Serializable
enum class VibrationPattern {
    @SerialName("standard")
    Standard,

    @SerialName("pulse")
    Pulse,

    @SerialName("long")
    Long,

    @SerialName("heartbeat")
    Heartbeat,
}

/** `a` or `b`: the end of the gate. */
@Serializable
enum class Slot {
    @SerialName("a")
    A,

    @SerialName("b")
    B,
}

/** Where a trip is headed, as picked on the map or in the stop sheet. */
@Serializable
data class Destination(
    val label: String,
    val coord: Coord,
    @SerialName("gtfs_stop") val gtfsStop: String? = null,
    val pattern: Int? = null,
)

/** The two points chosen so far. */
@Serializable
data class Ends(
    val a: Destination? = null,
    val b: Destination? = null,
)

/** One saved stop: a name and the two points of its gate. */
@Serializable
data class SavedStop(
    val id: Long,
    val name: String,
    val a: Endpoint,
    val b: Endpoint,
    @SerialName("created_ms") val createdMs: Long,
    @EncodeDefault(NEVER) val favourite: Boolean = false,
    @EncodeDefault(NEVER) val colour: StopColour = StopColour.Orange,
)

/** The content of `saved.json`. */
@Serializable
data class SavedStops(
    val version: Int,
    @SerialName("next_id") val nextId: Long,
    val stops: List<SavedStop>,
)

/** One finished trip (`history.json`). */
@Serializable
data class TripRecord(
    val id: Long,
    val label: String,
    val a: Endpoint?,
    val b: Endpoint?,
    @SerialName("started_ms") val startedMs: Long,
    @SerialName("ended_ms") val endedMs: Long,
    val outcome: Outcome,
    @SerialName("travelled_m") val travelledM: Double,
    val snoozes: Int,
    @SerialName("notice_sent") val noticeSent: Boolean,
    /** A legacy single-destination record only; never written by the current build. */
    @EncodeDefault(NEVER) val destination: Coord? = null,
)

/** The content of `history.json`, newest first. */
@Serializable
data class History(
    val records: List<TripRecord>,
)

/** When the "arriving soon" message goes out. */
@Serializable(with = NoticeWhenSerializer::class)
sealed interface NoticeWhen {
    /** Together with the alarm. */
    data object WithAlarm : NoticeWhen

    /** This many minutes before the circle is reached. */
    data class MinutesBefore(
        val minutes: Int,
    ) : NoticeWhen
}

internal object NoticeWhenSerializer : ExternallyTagged<NoticeWhen>("NoticeWhen") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): NoticeWhen =
        when (tag) {
            "WithAlarm" -> NoticeWhen.WithAlarm
            "MinutesBefore" -> NoticeWhen.MinutesBefore((value ?: unknown(tag)).jsonPrimitive.int)
            else -> unknown(tag)
        }

    override fun toJson(value: NoticeWhen): JsonElement =
        when (value) {
            NoticeWhen.WithAlarm -> JsonPrimitive("WithAlarm")
            is NoticeWhen.MinutesBefore -> tagged("MinutesBefore", JsonPrimitive(value.minutes))
        }
}

/** The arrival message of the preferences. */
@Serializable
data class NoticePrefs(
    /** WhatsApp chat id on the gateway; empty for none. */
    val chat: String,
    @SerialName("chat_name") val chatName: String,
    val template: String,
    @SerialName("when") val sendWhen: NoticeWhen,
)

@Serializable
data class AlarmSound(
    val uri: String,
    val title: String,
)

/** The Stops settings (`stops-prefs.json`). */
@Serializable
data class StopsPrefs(
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    @SerialName("snooze_min") val snoozeMin: Int = 5,
    val notice: NoticePrefs? = null,
    @SerialName("tile_url") val tileUrl: String? = null,
    val appearance: Appearance = Appearance.Auto,
    val vibration: VibrationPattern = VibrationPattern.Standard,
    @SerialName("volume_pct") val volumePct: Int = 80,
    @SerialName("alarm_sound") val alarmSound: AlarmSound? = null,
    @SerialName("tile_cache_mb") val tileCacheMb: Int = 128,
    @SerialName("show_saved_on_map") val showSavedOnMap: Boolean = true,
)

/** What the foreground service needs to ring. */
@Serializable
data class AlarmPlan(
    @SerialName("volume_pct") val volumePct: Int,
    @SerialName("ramp_ms") val rampMs: Long,
    @SerialName("ring_timeout_ms") val ringTimeoutMs: Long,
    @SerialName("sound_order") val soundOrder: List<String>,
    @SerialName("vibrate_pattern_ms") val vibratePatternMs: List<Long>,
    @SerialName("user_sound_uri") @EncodeDefault(NEVER) val userSoundUri: String? = null,
)

/** A GTFS stop (`gtfs.json`). */
@Serializable
data class GtfsStop(
    val id: String,
    val name: String,
    val coord: Coord,
    val parent: Int?,
)

@Serializable
data class GtfsRoute(
    val id: String,
    @SerialName("short_name") val shortName: String,
    @SerialName("long_name") val longName: String,
    val kind: Int,
)

@Serializable
data class GtfsPattern(
    val route: Int,
    val headsign: String,
    val stops: List<Int>,
)

/** The imported feed (the content of `gtfs.json`). */
@Serializable
data class GtfsFeed(
    val stops: List<GtfsStop>,
    val routes: List<GtfsRoute>,
    val patterns: List<GtfsPattern>,
    @SerialName("imported_ms") val importedMs: Long,
)

/** A place read from shared text. */
@Serializable
data class SharedPlace(
    val coord: Coord,
    val label: String?,
)

/** The view of the map: a centre and a scale. */
@Serializable
data class Viewport(
    val center: Coord,
    @SerialName("metres_per_px") val metresPerPx: Double,
)
