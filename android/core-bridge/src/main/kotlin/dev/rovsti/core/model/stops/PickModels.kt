@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// `stops.pick_*` and `stops.snap` (E2.12): choosing A and B on the map. See `crates/rovsti_ffi/src/ops/stops/pick.rs`.

/** One gesture of the pick state machine, in the order it happened. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface PickEvent {
    /** A tap on the map that resolved to [dest]. */
    @Serializable
    @SerialName("tap")
    data class Tap(
        val dest: Destination,
    ) : PickEvent

    @Serializable
    @SerialName("place")
    data class Place(
        val slot: Slot,
        val dest: Destination,
    ) : PickEvent

    @Serializable
    @SerialName("set_pair")
    data class SetPair(
        val a: Destination,
        val b: Destination,
    ) : PickEvent

    @Serializable
    @SerialName("restore")
    data class Restore(
        val ends: Ends,
    ) : PickEvent

    @Serializable
    @SerialName("swap")
    data object Swap : PickEvent

    @Serializable
    @SerialName("clear")
    data object Clear : PickEvent

    @Serializable
    @SerialName("remove")
    data class Remove(
        val slot: Slot,
    ) : PickEvent

    @Serializable
    @SerialName("begin_move")
    data class BeginMove(
        val slot: Slot,
    ) : PickEvent

    @Serializable
    @SerialName("open_menu")
    data class OpenMenu(
        val slot: Slot,
    ) : PickEvent

    @Serializable
    @SerialName("close_menu")
    data object CloseMenu : PickEvent

    @Serializable
    @SerialName("cancel_move")
    data object CancelMove : PickEvent

    @Serializable
    @SerialName("drag_start")
    data class DragStart(
        val slot: Slot,
    ) : PickEvent

    @Serializable
    @SerialName("drag_to")
    data class DragTo(
        val slot: Slot,
        val coord: Coord,
    ) : PickEvent

    @Serializable
    @SerialName("drag_end")
    data class DragEnd(
        val slot: Slot,
        val dest: Destination,
    ) : PickEvent

    @Serializable
    @SerialName("drag_cancel")
    data object DragCancel : PickEvent

    @Serializable
    @SerialName("set_sheet_slot")
    data class SetSheetSlot(
        val slot: Slot,
    ) : PickEvent
}

@Serializable
data class PickReplayRequest(
    val events: List<PickEvent> = emptyList(),
)

/** The line that tells the user what to do next. [kind] is `set_a`, `set_b`, `moving`, `ready` or `invalid`. */
@Serializable
data class PickHint(
    val kind: String,
    val text: String,
    val metres: Double?,
)

/**
 * The state after the events. [outcomes] has one entry per event: `placed_a`, `placed_b`, `menu_closed` or `full` for a
 * tap, `grabbed` or `empty` for a drag start, `ok` for the rest.
 */
@Serializable
data class PickReplayAnswer(
    val ends: Ends,
    val outcomes: List<String>,
    val hint: PickHint,
    @SerialName("can_start") val canStart: Boolean,
    val moving: Slot?,
    val menu: Slot?,
    val dragging: Slot?,
    @SerialName("sheet_slot") val sheetSlot: Slot,
    @SerialName("confirm_slot") val confirmSlot: Slot,
    @SerialName("tap_slot") val tapSlot: Slot?,
)

@Serializable
data class PickLabelsRequest(
    val ends: Ends,
    /** Clipboard text to clean for a field. */
    @EncodeDefault(NEVER) val paste: String? = null,
    /** The picker's search text, for its empty line. */
    @EncodeDefault(NEVER) val query: String? = null,
    @SerialName("has_feed") @EncodeDefault(NEVER) val hasFeed: Boolean? = null,
    @SerialName("row_label") @EncodeDefault(NEVER) val rowLabel: String? = null,
    /** A stored endpoint (a recent trip) to turn back into a destination. */
    @EncodeDefault(NEVER) val endpoint: Endpoint? = null,
)

/** The texts of one picked end. */
@Serializable
data class PickSide(
    val label: String,
    val title: String,
    val named: Boolean,
    val details: String,
    val queued: String,
    val endpoint: Endpoint,
)

@Serializable
data class PickLabelsAnswer(
    val a: PickSide?,
    val b: PickSide?,
    @SerialName("empty_label_a") val emptyLabelA: String,
    @SerialName("empty_label_b") val emptyLabelB: String,
    @SerialName("picker_title_a") val pickerTitleA: String,
    @SerialName("picker_title_b") val pickerTitleB: String,
    val pair: String?,
    @SerialName("next_slot") val nextSlot: Slot,
    val paste: String?,
    @SerialName("no_match") val noMatch: String?,
    @SerialName("row_label") val rowLabel: String?,
    val destination: Destination?,
    @SerialName("coord_text") val coordText: String?,
)

@Serializable
data class SaveOfferRequest(
    val ends: Ends,
    val saved: SavedStops,
)

/** [kind] is `none`, `save` or `saved` (with [id] and [name] of the stop that already has this gate). */
@Serializable
data class SaveOfferAnswer(
    val kind: String,
    val id: Long?,
    val name: String?,
)

@Serializable
data class PointerSample(
    @SerialName("t_ms") val tMs: Long,
    val x: Double,
    val y: Double,
)

/** The release of a dragged pin: from [samples] (read at [nowMs]) or the speeds given directly. */
@Serializable
data class SnapRequest(
    @EncodeDefault(NEVER) val samples: List<PointerSample>? = null,
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
    @SerialName("release_px_s") @EncodeDefault(NEVER) val releasePxS: Double? = null,
    @SerialName("peak_px_s") @EncodeDefault(NEVER) val peakPxS: Double? = null,
    /** Pixels to the nearest stop that could take the pin, if any. */
    @SerialName("candidate_px") @EncodeDefault(NEVER) val candidatePx: Double? = null,
    @SerialName("travel_px") @EncodeDefault(NEVER) val travelPx: Double? = null,
    @SerialName("started_on_candidate") @EncodeDefault(NEVER) val startedOnCandidate: Boolean? = null,
)

/** [decision] is `snap` or `free`. */
@Serializable
data class SnapAnswer(
    @SerialName("release_px_s") val releasePxS: Double,
    @SerialName("peak_px_s") val peakPxS: Double,
    @SerialName("radius_px") val radiusPx: Double,
    val decision: String,
    @SerialName("tap_snaps") val tapSnaps: Boolean,
    @SerialName("tap_radius_px") val tapRadiusPx: Double,
)
