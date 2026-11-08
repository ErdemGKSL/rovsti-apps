@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// `stops.saved_*`, `stops.places_*` and `stops.dest_*` (E2.12). See `crates/rovsti_ffi/src/ops/stops/places.rs`.

/** `stops.saved_load`: the content of `saved.json`; absent for no file. */
@Serializable
data class SavedLoadRequest(
    @EncodeDefault(NEVER) val text: String? = null,
)

@Serializable
data class SavedLoadAnswer(
    val saved: SavedStops,
    /** The file was old or unreadable and was dropped; the caller overwrites it. */
    val discarded: Boolean,
    @SerialName("format_version") val formatVersion: Int,
)

/** One edit of the list. A refused edit answers `RustError.Invalid` with `detail.reason`. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface SavedEdit {
    @Serializable
    @SerialName("add")
    data class Add(
        val name: String,
        val a: Endpoint,
        val b: Endpoint,
    ) : SavedEdit

    @Serializable
    @SerialName("rename")
    data class Rename(
        val id: Long,
        val name: String,
    ) : SavedEdit

    @Serializable
    @SerialName("remove")
    data class Remove(
        val id: Long,
    ) : SavedEdit

    @Serializable
    @SerialName("toggle_favourite")
    data class ToggleFavourite(
        val id: Long,
    ) : SavedEdit

    @Serializable
    @SerialName("cycle_colour")
    data class CycleColour(
        val id: Long,
    ) : SavedEdit

    @Serializable
    @SerialName("set_colour")
    data class SetColour(
        val id: Long,
        val colour: StopColour,
    ) : SavedEdit

    @Serializable
    @SerialName("move_by")
    data class MoveBy(
        val id: Long,
        val delta: Int,
    ) : SavedEdit

    /** Puts a removed stop back at [index] (undo of a delete). */
    @Serializable
    @SerialName("restore")
    data class Restore(
        val stop: SavedStop,
        val index: Int,
    ) : SavedEdit
}

@Serializable
data class SavedEditRequest(
    val saved: SavedStops,
    val edit: SavedEdit,
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
)

/**
 * [id] is the stop touched; [removed] and [index] say what a delete took out (for undo); [flag] is the new favourite
 * state or whether a move changed anything; [colour] is the colour now set.
 */
@Serializable
data class SavedEditAnswer(
    val saved: SavedStops,
    val id: Long?,
    val removed: SavedStop?,
    val index: Int?,
    val flag: Boolean?,
    val colour: StopColour?,
)

/** [sort] is `saved` (default), `name` or `nearest`. */
@Serializable
data class SavedListRequest(
    val saved: SavedStops,
    @EncodeDefault(NEVER) val query: String? = null,
    @EncodeDefault(NEVER) val sort: String? = null,
    @EncodeDefault(NEVER) val here: Coord? = null,
)

@Serializable
data class SavedRow(
    val stop: SavedStop,
    val center: Coord,
    @SerialName("length_m") val lengthM: Double,
    /** False when the stored points no longer form a gate. */
    val valid: Boolean,
    @SerialName("distance_m") val distanceM: Double?,
    @SerialName("colour_label") val colourLabel: String,
)

@Serializable
data class NearestStop(
    val id: Long,
    val name: String,
    @SerialName("distance_m") val distanceM: Double,
)

@Serializable
data class ColourChoice(
    val id: StopColour,
    val label: String,
    val next: StopColour,
)

@Serializable
data class SavedListAnswer(
    val rows: List<SavedRow>,
    val total: Int,
    val nearest: NearestStop?,
    @SerialName("nearest_chip_m") val nearestChipM: Double,
    /** The list as plain text for sharing. */
    val text: String,
    @SerialName("max_stops") val maxStops: Int,
    @SerialName("max_name_chars") val maxNameChars: Int,
    val colours: List<ColourChoice>,
)

@Serializable
data class SuggestNameRequest(
    val a: Endpoint,
    val b: Endpoint,
    @EncodeDefault(NEVER) val feed: GtfsFeed? = null,
)

@Serializable
data class NameAnswer(
    val name: String,
)

@Serializable
data class ShortcutRequest(
    val stop: SavedStop,
)

/** What the launcher needs to pin a shortcut that starts a saved stop's trip. */
@Serializable
data class ShortcutAnswer(
    val id: String,
    @SerialName("short_label") val shortLabel: String,
    @SerialName("long_label") val longLabel: String,
    @SerialName("stop_id") val stopId: Long,
)

/** [format] is `json` or `gpx`; [today] is `2026-11-05`. */
@Serializable
data class PlacesExportRequest(
    val saved: SavedStops,
    val format: String,
    val today: String,
)

@Serializable
data class FileTextAnswer(
    val text: String,
    @SerialName("file_name") val fileName: String,
)

/** With [apply] the planned stops are added to the answer's list; else it comes back unchanged. */
@Serializable
data class PlacesImportRequest(
    val saved: SavedStops,
    val text: String,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val apply: Boolean? = null,
)

@Serializable
data class IncomingStop(
    val name: String,
    val a: Coord,
    val b: Coord,
    val favourite: Boolean,
    val colour: StopColour,
)

@Serializable
data class MergePlan(
    val add: List<IncomingStop>,
    val duplicates: Int,
    val invalid: Int,
    @SerialName("over_cap") val overCap: Int,
)

@Serializable
data class PlacesImportAnswer(
    val plan: MergePlan,
    /** One line for the confirm sheet. */
    val summary: String,
    val added: Int,
    val saved: SavedStops,
    @SerialName("max_bytes") val maxBytes: Long,
)

@Serializable
data class DestParseRequest(
    val text: String,
)

@Serializable
data class SharedStop(
    val name: String,
    val a: SharedPlace,
    val b: SharedPlace,
)

/** [kind] is `place` (with [place]) or `stop` (with [stop]); anything else is `RustError.Invalid`. */
@Serializable
data class DestParseAnswer(
    val kind: String,
    val place: SharedPlace?,
    val stop: SharedStop?,
)

@Serializable
data class DestShareRequest(
    val at: Coord,
    @EncodeDefault(NEVER) val label: String? = null,
    /** The second point of a stop; with it [DestShareAnswer.stopText] is written under [name]. */
    @EncodeDefault(NEVER) val b: Coord? = null,
    @EncodeDefault(NEVER) val name: String? = null,
)

@Serializable
data class DestShareAnswer(
    val text: String,
    @SerialName("stop_text") val stopText: String?,
    @SerialName("qr_payload") val qrPayload: String,
)
