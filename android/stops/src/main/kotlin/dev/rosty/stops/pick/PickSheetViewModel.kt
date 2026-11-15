package dev.rosty.stops.pick

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.DestParseAnswer
import dev.rosty.core.model.stops.DestParseRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.FormatDistanceRequest
import dev.rosty.core.model.stops.GtfsSearchRequest
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.PickLabelsAnswer
import dev.rosty.core.model.stops.PickLabelsRequest
import dev.rosty.core.model.stops.RecentRequest
import dev.rosty.core.model.stops.SavedListRequest
import dev.rosty.core.model.stops.SharedPlace
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.ops.StopsOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.R
import dev.rosty.stops.data.Latest
import dev.rosty.stops.data.SavedStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One row of the pick sheet (GPUI `PickItem`); every text is made by the core. */
@Immutable
sealed interface PickRow {
    val key: String

    /** The point the typed text names: opens the Confirm sheet. */
    data class Offer(
        val dest: Destination,
        val title: String,
        val details: String,
    ) : PickRow {
        override val key get() = "offer"
    }

    /** Why the typed link is no location (shown only for text that looks like a link, [PickRules]). */
    data class Refusal(
        val text: UiText,
    ) : PickRow {
        override val key get() = "refusal"
    }

    data class NoMatch(
        val text: String,
    ) : PickRow {
        override val key get() = "no-match"
    }

    data class Header(
        val text: UiText,
    ) : PickRow {
        override val key get() = "header-$text"
    }

    /** A transit stop of the feed: its sheet (S7). [distance] when the position is known; [lines] calls there. */
    data class Stop(
        val id: String,
        val name: String,
        val coord: Coord,
        val distance: String?,
        val lines: Int,
    ) : PickRow {
        override val key get() = "stop-$id"
    }

    data class Saved(
        val id: Long,
        val name: String,
        val length: String,
        val distance: String?,
        val a: Destination,
        val b: Destination,
    ) : PickRow {
        override val key get() = "saved-$id"
    }

    /** A recent trip: both ends at once. */
    data class Pair(
        val index: Int,
        val title: String,
        val a: Destination,
        val b: Destination,
    ) : PickRow {
        override val key get() = "pair-$index"
    }

    /** A recent point: fills the end the sheet is for. */
    data class Recent(
        val index: Int,
        val title: String,
        val dest: Destination,
    ) : PickRow {
        override val key get() = "recent-$index"
    }

    /** Nothing saved and no history yet: how to bring a place in. */
    data object Hint : PickRow {
        override val key get() = "hint"
    }
}

@Immutable
data class PickSheetUiState(
    val query: String = "",
    /** "Choose point A" / "Choose point B" (`pick_labels`). */
    val title: String = "",
    /** The end a recent point or a confirmed place fills. */
    val slot: Slot = Slot.A,
    val rows: ImmutableList<PickRow> = persistentListOf(),
    val loading: Boolean = true,
)

/** A place to confirm (shared link, pasted point, typed coordinates): the Confirm sheet's texts. */
@Immutable
data class ConfirmPlace(
    val dest: Destination,
    val title: String,
    val details: String,
)

sealed interface PickEffect {
    data class Toast(
        val text: UiText,
    ) : PickEffect

    /** Open the Confirm sheet for a place. */
    data class Confirm(
        val place: ConfirmPlace,
    ) : PickEffect

    /** Both ends at once (a saved stop, a recent trip, a pasted stop share); [toast] when one is due. */
    data class SetPair(
        val a: Destination,
        val b: Destination,
        val toast: UiText? = null,
    ) : PickEffect

    /** One end (a recent point). */
    data class Place(
        val slot: Slot,
        val dest: Destination,
    ) : PickEffect

    /** Open the stop sheet (S7) of a transit stop. */
    data class OpenStop(
        val stop: PickRow.Stop,
    ) : PickEffect

    /** Put [text] in the search field (pasted text that is not a place). */
    data class FieldText(
        val text: String,
    ) : PickEffect
}

sealed interface PickAction {
    data class Query(
        val text: String,
    ) : PickAction

    /** The Paste button: the clipboard's text, read on the tap only. */
    data class Paste(
        val clip: String?,
    ) : PickAction

    data class Choose(
        val row: PickRow,
    ) : PickAction
}

/**
 * Rules the core does not answer yet; the pick sheet asks them here so a Rust op can replace them without a UI change.
 * [showRefusal]: whether a typed query that is no location gets its reason (GPUI `looks_like_link`, an app-local rule
 * in `apps/stops/src/picker.rs`). The stub says no: typing a name searches below without an explanation.
 */
interface PickRules {
    fun showRefusal(query: String): Boolean = false

    companion object {
        val Core: PickRules = object : PickRules {}
    }
}

/**
 * The pick sheet (E7.8): the search / paste field and its rows, all from the core: `stops.dest_parse` (a link,
 * a `geo:` URI, bare coordinates, DMS, DDM, Plus Code, S23; a shared stop), `stops.pick_labels` (titles, details,
 * the cleaned paste, the empty line, row labels, a recent endpoint as a destination), `stops.gtfs_search` (stops by
 * name, nearest first), `stops.saved_list` (saved stops by name), `stops.history_recent` (recent trips and points),
 * `stops.format_distance`. [ends] are the ends on the map when the sheet opens.
 */
class PickSheetViewModel(
    private val ops: StopsOps,
    private val saved: SavedStore,
    private val here: suspend () -> Coord?,
    private val rules: PickRules = PickRules.Core,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val mutable = MutableStateFlow(PickSheetUiState())
    val state: StateFlow<PickSheetUiState> = mutable.asStateFlow()

    private val effectChannel = Channel<PickEffect>(Channel.BUFFERED)
    val effects: Flow<PickEffect> = effectChannel.receiveAsFlow()

    private var ends = Ends()
    private val listing = Latest(work)

    /** The sheet opens for [onMap]: an empty field, the rows of no query. */
    fun open(onMap: Ends) {
        ends = onMap
        mutable.value = PickSheetUiState()
        work.launch {
            val labels = labels(PickLabelsRequest(onMap))
            val slot = labels?.nextSlot ?: Slot.A
            val title = if (slot == Slot.A) labels?.pickerTitleA else labels?.pickerTitleB
            mutable.value = mutable.value.copy(slot = slot, title = title.orEmpty())
            relist(delayMs = 0)
        }
    }

    fun onAction(action: PickAction) {
        when (action) {
            is PickAction.Query -> {
                mutable.value = mutable.value.copy(query = action.text)
                relist(delayMs = QUERY_DEBOUNCE_MS)
            }

            is PickAction.Paste -> {
                work.launch { paste(action.clip) }
            }

            is PickAction.Choose -> {
                work.launch { choose(action.row) }
            }
        }
    }

    /** Text handed to the app (a shared link, a `geo:` intent): a stop fills A and B, a place asks to confirm. */
    fun offerText(text: String) {
        work.launch {
            when (val parsed = ops.destParse(DestParseRequest(text))) {
                is Outcome.Err -> emit(PickEffect.Toast(refusal(parsed.error)))
                is Outcome.Ok -> offer(parsed.value, refusedIntoField = null)
            }
        }
    }

    private fun emit(effect: PickEffect) {
        effectChannel.trySend(effect)
    }

    private suspend fun labels(request: PickLabelsRequest): PickLabelsAnswer? =
        (ops.pickLabels(request) as? Outcome.Ok)?.value

    private suspend fun paste(clip: String?) {
        val text = labels(PickLabelsRequest(ends, paste = clip.orEmpty()))?.paste
        if (text.isNullOrBlank()) {
            emit(PickEffect.Toast(UiText.Res(R.string.pick_nothing_to_paste)))
            return
        }
        when (val parsed = ops.destParse(DestParseRequest(text))) {
            // Not a place: the text goes into the field and searches.
            is Outcome.Err -> emit(PickEffect.FieldText(text))

            is Outcome.Ok -> offer(parsed.value, refusedIntoField = text)
        }
    }

    private suspend fun offer(
        parsed: DestParseAnswer,
        refusedIntoField: String?,
    ) {
        val stop = parsed.stop
        val place = parsed.place
        when {
            stop != null -> {
                val name = stop.name.trim()
                val toast =
                    if (name.isEmpty()) {
                        UiText.Res(
                            R.string.pick_filled_pair,
                        )
                    } else {
                        UiText.Res(R.string.pick_filled_pair_from, listOf(name))
                    }
                emit(PickEffect.SetPair(destOf(stop.a), destOf(stop.b), toast))
            }

            place != null -> {
                confirmOf(destOf(place))?.let { emit(PickEffect.Confirm(it)) }
            }

            refusedIntoField != null -> {
                emit(PickEffect.FieldText(refusedIntoField))
            }
        }
    }

    /** The Confirm sheet's texts for [dest] (`pick_labels` of the point as an end). */
    private suspend fun confirmOf(dest: Destination): ConfirmPlace? {
        val side = labels(PickLabelsRequest(Ends(a = dest)))?.a ?: return null
        return ConfirmPlace(dest, side.title, side.details)
    }

    private suspend fun choose(row: PickRow) {
        when (row) {
            is PickRow.Offer -> emit(PickEffect.Confirm(ConfirmPlace(row.dest, row.title, row.details)))
            is PickRow.Stop -> emit(PickEffect.OpenStop(row))
            is PickRow.Saved -> emit(PickEffect.SetPair(row.a, row.b))
            is PickRow.Pair -> emit(PickEffect.SetPair(row.a, row.b))
            is PickRow.Recent -> emit(PickEffect.Place(mutable.value.slot, row.dest))
            is PickRow.Refusal, is PickRow.NoMatch, is PickRow.Header, PickRow.Hint -> Unit
        }
    }

    private fun relist(delayMs: Long) {
        val query = mutable.value.query.trim()
        listing.launch {
            if (delayMs > 0) delay(delayMs)
            val rows = rowsFor(query)
            publish { mutable.value = mutable.value.copy(rows = rows.toImmutableList(), loading = false) }
        }
    }

    /** GPUI `pick_rows`: the typed point, stops, saved stops; with no query, the recent trips and points. */
    private suspend fun rowsFor(query: String): List<PickRow> {
        val rows = ArrayList<PickRow>()
        val position = here()
        if (query.isNotEmpty()) {
            when (val parsed = ops.destParse(DestParseRequest(query))) {
                is Outcome.Ok -> {
                    parsed.value.place?.let { place ->
                        confirmOf(destOf(place))?.let { rows += PickRow.Offer(it.dest, it.title, it.details) }
                    }
                }

                is Outcome.Err -> {
                    if (rules.showRefusal(query)) rows += PickRow.Refusal(refusal(parsed.error))
                }
            }
        }
        var hasFeed = false
        if (query.isNotEmpty()) {
            val search =
                (
                    ops.gtfsSearch(
                        GtfsSearchRequest(query, MAX_STOP_HITS, near = position),
                    ) as? Outcome.Ok
                )?.value
            hasFeed = search?.hasFeed == true
            val stops = search?.rows.orEmpty()
            if (stops.isNotEmpty()) {
                rows += PickRow.Header(UiText.Res(R.string.pick_stops))
                stops.forEach { hit ->
                    rows +=
                        PickRow.Stop(hit.id, hit.name, hit.coord, hit.distanceM?.let { text(it) }, hit.patterns.size)
                }
            }
        }
        val file = saved.load()
        if (file.stops.isNotEmpty()) {
            val list =
                (
                    ops.savedList(
                        SavedListRequest(
                            file,
                            query =
                                query.ifEmpty {
                                    null
                                },
                            here = position,
                        ),
                    ) as? Outcome.Ok
                )?.value
            val found = list?.rows.orEmpty()
            if (found.isNotEmpty()) {
                rows += PickRow.Header(UiText.Res(R.string.pick_saved))
                found.forEach { row ->
                    rows +=
                        PickRow.Saved(
                            id = row.stop.id,
                            name = row.stop.name,
                            length = text(row.lengthM),
                            distance = row.distanceM?.let { text(it) },
                            a = Destination(row.stop.a.label, row.stop.a.coord),
                            b = Destination(row.stop.b.label, row.stop.b.coord),
                        )
                }
            }
        }
        if (query.isNotEmpty() && rows.isEmpty()) {
            labels(PickLabelsRequest(ends, query = query, hasFeed = hasFeed))?.noMatch?.let {
                rows +=
                    PickRow.NoMatch(it)
            }
        }
        if (query.isEmpty()) {
            val history = readHistory()
            val recent = history?.let { (ops.historyRecent(RecentRequest(it, RECENT_POINTS)) as? Outcome.Ok)?.value }
            val pairs = recent?.pairs.orEmpty().take(RECENT_PAIRS)
            if (pairs.isNotEmpty()) {
                rows += PickRow.Header(UiText.Res(R.string.pick_recent_trips))
                pairs.forEachIndexed { i, pair ->
                    val a = destinationOf(pair.a)
                    val b = destinationOf(pair.b)
                    rows += PickRow.Pair(i, "${rowLabel(a.label)} – ${rowLabel(b.label)}", a, b)
                }
            }
            val points = recent?.points.orEmpty().take(RECENT_POINTS)
            if (points.isNotEmpty()) {
                rows += PickRow.Header(UiText.Res(R.string.pick_recent))
                points.forEachIndexed { i, point ->
                    val dest = destinationOf(point)
                    rows += PickRow.Recent(i, rowLabel(dest.label), dest)
                }
            }
            if (file.stops.isEmpty() && history?.records.isNullOrEmpty()) rows += PickRow.Hint
        }
        return rows
    }

    private suspend fun rowLabel(label: String): String =
        labels(PickLabelsRequest(ends, rowLabel = label))?.rowLabel ?: label

    /** A stored endpoint back as a destination (`pick_labels` `endpoint`). */
    private suspend fun destinationOf(endpoint: Endpoint): Destination =
        labels(PickLabelsRequest(ends, endpoint = endpoint))?.destination ?: Destination(endpoint.label, endpoint.coord)

    private suspend fun text(metres: Double): String =
        (ops.formatDistance(FormatDistanceRequest(metres)) as? Outcome.Ok)?.value?.text ?: "${metres.toInt()} m"

    private suspend fun readHistory(): History? {
        val value = (ops.storeRead(StoreReadRequest(HISTORY)) as? Outcome.Ok)?.value?.value ?: return null
        return try {
            Lenient.decodeFromJsonElement(History.serializer(), value)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val HISTORY = "history"
        private const val QUERY_DEBOUNCE_MS = 150L

        /** GPUI: three recent trips, five recent points, a page of stop hits. */
        const val RECENT_PAIRS = 3
        const val RECENT_POINTS = 5
        const val MAX_STOP_HITS = 20

        private val Lenient = Json { ignoreUnknownKeys = true }

        /** A shared place as a destination; a blank label stays unnamed (GPUI `offer`). */
        fun destOf(place: SharedPlace): Destination = Destination(place.label?.trim().orEmpty(), place.coord)

        /** The words for text that is no location, from `detail.reason` (`stops.dest_parse`). */
        fun refusal(error: RustError): UiText =
            UiText.Res(
                when (error.reason) {
                    "no_coordinates" -> R.string.pick_error_no_coordinates
                    "out_of_range" -> R.string.pick_error_out_of_range
                    else -> R.string.pick_error_not_a_location
                },
            )
    }
}
