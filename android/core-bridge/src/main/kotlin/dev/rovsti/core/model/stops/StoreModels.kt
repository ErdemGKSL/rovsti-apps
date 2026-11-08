@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// `stops.store_*` and `stops.prefs_load/save` (E2.12): the files of the data directory. The ops that touch `trip.json`
// hold the store's trip lock, the same one the headless service takes. See `crates/rovsti_ffi/src/ops/stops/store.rs`.

/** [trip] is the whole persisted trip (a [TripView.snapshot]), null when no trip runs. */
@Serializable
data class StoreTripLoadAnswer(
    val trip: JsonElement?,
    val file: String,
    val label: String?,
)

@Serializable
data class StoreTripSaveRequest(
    val trip: JsonElement,
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** Moves the trip in progress into the history. [outcome] is `arrived` or `cancelled`. */
@Serializable
data class StoreTripEndRequest(
    val outcome: String,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** [record] is null when no trip ran (or it was already recorded). */
@Serializable
data class StoreTripEndAnswer(
    val record: TripRecord?,
)

/** [file] is `saved`, `history` or `draft`. */
@Serializable
data class StoreReadRequest(
    val file: String,
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** [value] is the file's content ([SavedStops], [History] or [Ends]); null when absent. */
@Serializable
data class StoreReadAnswer(
    val file: String,
    val value: JsonElement?,
    val discarded: Boolean,
)

@Serializable
data class StoreWriteRequest(
    val file: String,
    val value: JsonElement,
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class PrefsLoadAnswer(
    val prefs: StopsPrefs,
    /** False when the file is missing and [prefs] are the defaults. */
    val found: Boolean,
)

@Serializable
data class PrefsSaveRequest(
    val prefs: StopsPrefs,
    @EncodeDefault(NEVER) val dir: String? = null,
)
