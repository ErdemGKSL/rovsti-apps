package dev.rosty.stops.saved

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.DestShareRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.FormatDistanceRequest
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.NearestStop
import dev.rosty.core.model.stops.SavedEdit
import dev.rosty.core.model.stops.SavedEditAnswer
import dev.rosty.core.model.stops.SavedListRequest
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.ShortcutAnswer
import dev.rosty.core.model.stops.ShortcutRequest
import dev.rosty.core.model.stops.StopColour
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.model.stops.SuggestNameRequest
import dev.rosty.core.model.stops.UsageRequest
import dev.rosty.core.ops.StopsOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.R
import dev.rosty.stops.data.SavedStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The order of the Saved list (S6): the user's own, by name, or nearest first. Wire values of `stops.saved_list`. */
enum class SavedSort(
    val wire: String,
) {
    Saved("saved"),
    Name("name"),
    Nearest("nearest"),
}

/** One row as the list draws it; every text is already made (no formatting in composition). */
@Immutable
data class SavedRowUi(
    val stop: SavedStop,
    /** The gate's length ("420 m"). */
    val length: String,
    /** S29: how far its middle is from here ("1.2 km"), when a position is known. */
    val distance: String?,
    /** S38: "Used 4 times, last 5 Nov", null when never used. */
    val usage: String?,
    val valid: Boolean,
)

@Immutable
data class SavedUiState(
    val loading: Boolean = true,
    val query: String = "",
    val sort: SavedSort = SavedSort.Saved,
    val rows: ImmutableList<SavedRowUi> = persistentListOf(),
    /** The whole file in its own order (Move up / down and the drag work on it). */
    val stops: ImmutableList<SavedStop> = persistentListOf(),
    /** The listed rows as plain text (S30 Copy list, Share list). */
    val listText: String = "",
    /** Drag handles are showing (Edit). */
    val editing: Boolean = false,
    val maxNameChars: Int = DEFAULT_NAME_CHARS,
    /** S44: a refused note of stop [NoteError.id], shown under the note field (the field keeps the text). */
    val noteError: NoteError? = null,
    /** S37: the saved stop nearest the last position, when one is close (`saved_list` `nearest`). */
    val nearest: NearestStop? = null,
) {
    /** The user's own order, unfiltered: what Move and the drag change is what is shown (GPUI `reorderable`). */
    val canReorder: Boolean get() = sort == SavedSort.Saved && (!showSearch(stops.size) || query.isBlank())

    companion object {
        const val DEFAULT_NAME_CHARS = 40

        /** The longest note the core keeps (`saved_edit` `set_note`). */
        const val NOTE_MAX_CHARS = 200
    }
}

/** A note the core refused, for the field of stop [id]. */
@Immutable
data class NoteError(
    val id: Long,
    val text: UiText,
)

/** What the screen does once (toasts, sheets, the share sheet, the launcher). */
sealed interface SavedEffect {
    data class Toast(
        val text: UiText,
    ) : SavedEffect

    /** A delete happened: offer Undo for [stop] at [index]. */
    data class Deleted(
        val stop: SavedStop,
        val index: Int,
    ) : SavedEffect

    /** "Save as stop" made stop [id] named [name]: offer Undo. */
    data class Saved(
        val id: Long,
        val name: String,
    ) : SavedEffect

    data class Share(
        val text: String,
    ) : SavedEffect

    data class Qr(
        val title: String,
        val payload: String,
    ) : SavedEffect

    /** Copy [text]; [done] is the toast. */
    data class Copy(
        val text: String,
        val done: UiText,
    ) : SavedEffect

    data class Shortcut(
        val spec: ShortcutAnswer,
    ) : SavedEffect

    data class OpenInMaps(
        val geoUri: String,
    ) : SavedEffect

    /** Put the stop on the map (both ends). */
    data class ShowOnMap(
        val a: Destination,
        val b: Destination,
    ) : SavedEffect

    /** Start a trip to [stop] (the trip story takes it from here). */
    data class Start(
        val stop: SavedStop,
    ) : SavedEffect
}

sealed interface SavedAction {
    data class Query(
        val text: String,
    ) : SavedAction

    data class Sort(
        val sort: SavedSort,
    ) : SavedAction

    data class Editing(
        val on: Boolean,
    ) : SavedAction

    /** Show on map (the stop sheet, the row menu, a stop shortcut). */
    data class Open(
        val id: Long,
    ) : SavedAction

    data class Start(
        val id: Long,
    ) : SavedAction

    data class Favourite(
        val id: Long,
    ) : SavedAction

    data class CycleColour(
        val id: Long,
    ) : SavedAction

    data class Rename(
        val id: Long,
        val name: String,
    ) : SavedAction

    /** S44: the stop's note; blank clears it. */
    data class Note(
        val id: Long,
        val note: String,
    ) : SavedAction

    data class Delete(
        val id: Long,
    ) : SavedAction

    data class Restore(
        val stop: SavedStop,
        val index: Int,
    ) : SavedAction

    /** Move up (-1), Move down (1), or a drag dropped [delta] places away in the stop's group. */
    data class Move(
        val id: Long,
        val delta: Int,
    ) : SavedAction

    data class Share(
        val id: Long,
    ) : SavedAction

    data class Qr(
        val id: Long,
    ) : SavedAction

    data class HomeScreen(
        val id: Long,
    ) : SavedAction

    data class OpenInMaps(
        val id: Long,
    ) : SavedAction

    data object CopyAll : SavedAction

    data object ShareAll : SavedAction

    /** "Save as stop" from a pin menu (E7.4): the pair on the map becomes a stop with a suggested name. */
    data class SaveGate(
        val a: Destination,
        val b: Destination,
    ) : SavedAction

    /** Undo of [SavedEffect.Saved]. */
    data class Unsave(
        val id: Long,
    ) : SavedAction
}

/**
 * The Saved list (E7.7). Rust does the work: `stops.saved_list` (search, sort, nearest distances, the list text),
 * `stops.saved_edit` through [SavedStore], `stops.history_usage` (S38), `stops.dest_share` (S2 share text, S3 QR),
 * `stops.saved_shortcut` (S18), `stops.saved_suggest_name` and `stops.format_distance`. [here] is the last known
 * position for the distances; [nowMs] and [tzOffsetS] are the clock of the usage line. [scope] replaces the view
 * model's own scope in tests.
 */
class SavedViewModel(
    private val store: SavedStore,
    private val ops: StopsOps,
    private val here: suspend () -> Coord?,
    private val nowMs: () -> Long,
    private val tzOffsetS: () -> Int,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val mutable = MutableStateFlow(SavedUiState())
    val state: StateFlow<SavedUiState> = mutable.asStateFlow()

    private val effectChannel = Channel<SavedEffect>(Channel.BUFFERED)
    val effects: Flow<SavedEffect> = effectChannel.receiveAsFlow()

    private var history: History? = null
    private var listJob: Job? = null

    init {
        work.launch {
            history = readHistory()
            store.load()
            store.saved.collect { if (it != null) relist(it) }
        }
    }

    /** Reads the history again (a trip ended) and lists anew. */
    fun refresh() {
        work.launch {
            history = readHistory()
            store.saved.value?.let { relist(it) }
        }
    }

    fun onAction(action: SavedAction) {
        when (action) {
            is SavedAction.Query -> {
                requery { it.copy(query = action.text) }
            }

            is SavedAction.Sort -> {
                requery { it.copy(sort = action.sort, editing = false) }
            }

            is SavedAction.Editing -> {
                mutable.value =
                    mutable.value.copy(editing = action.on && mutable.value.canReorder)
            }

            is SavedAction.Open -> {
                withStop(action.id) { emit(SavedEffect.ShowOnMap(it.a.toDestination(), it.b.toDestination())) }
            }

            is SavedAction.Start -> {
                withStop(action.id) { emit(SavedEffect.Start(it)) }
            }

            is SavedAction.Favourite -> {
                favourite(action.id)
            }

            is SavedAction.CycleColour -> {
                cycleColour(action.id)
            }

            is SavedAction.Rename -> {
                edit(SavedEdit.Rename(action.id, action.name.trim()))
            }

            is SavedAction.Note -> {
                note(action.id, action.note)
            }

            is SavedAction.Delete -> {
                delete(action.id)
            }

            is SavedAction.Restore -> {
                edit(SavedEdit.Restore(action.stop, action.index))
            }

            is SavedAction.Move -> {
                if (action.delta != 0) edit(SavedEdit.MoveBy(action.id, action.delta))
            }

            is SavedAction.Share -> {
                share(action.id, qr = false)
            }

            is SavedAction.Qr -> {
                share(action.id, qr = true)
            }

            is SavedAction.HomeScreen -> {
                shortcut(action.id)
            }

            is SavedAction.OpenInMaps -> {
                withStop(action.id) { emit(SavedEffect.OpenInMaps(geoUriOf(it))) }
            }

            SavedAction.CopyAll -> {
                copyAll()
            }

            SavedAction.ShareAll -> {
                shareAll()
            }

            is SavedAction.SaveGate -> {
                saveGate(action.a, action.b)
            }

            is SavedAction.Unsave -> {
                edit(SavedEdit.Remove(action.id))
            }
        }
    }

    private fun emit(effect: SavedEffect) {
        effectChannel.trySend(effect)
    }

    /** Runs [block] with stop [id]; the file may not be read yet when a shortcut opens the app. */
    private fun withStop(
        id: Long,
        block: suspend (SavedStop) -> Unit,
    ) {
        work.launch {
            val stop = store.load().stops.firstOrNull { it.id == id }
            if (stop == null) emit(SavedEffect.Toast(UiText.Res(R.string.saved_error_gone))) else block(stop)
        }
    }

    private fun requery(change: (SavedUiState) -> SavedUiState) {
        mutable.value = change(mutable.value)
        store.saved.value?.let { relist(it) }
    }

    private fun relist(saved: SavedStops) {
        listJob?.cancel()
        val now = mutable.value
        listJob =
            work.launch {
                val request =
                    SavedListRequest(saved, query = now.query.ifBlank { null }, sort = now.sort.wire, here = here())
                val list = (ops.savedList(request) as? Outcome.Ok)?.value ?: return@launch
                val rows =
                    list.rows.map { row ->
                        SavedRowUi(
                            stop = row.stop,
                            length = text(row.lengthM),
                            distance = row.distanceM?.let { text(it) },
                            usage = usage(row.stop),
                            valid = row.valid,
                        )
                    }
                val next =
                    mutable.value.copy(
                        loading = false,
                        rows = rows.toImmutableList(),
                        stops = saved.stops.toImmutableList(),
                        listText = list.text,
                        maxNameChars = list.maxNameChars,
                        nearest = list.nearest,
                    )
                mutable.value = next.copy(editing = next.editing && next.canReorder && next.stops.size > 1)
            }
    }

    private suspend fun text(metres: Double): String =
        (ops.formatDistance(FormatDistanceRequest(metres)) as? Outcome.Ok)?.value?.text ?: "${metres.toInt()} m"

    private suspend fun usage(stop: SavedStop): String? {
        val past = history ?: return null
        val answer = ops.historyUsage(UsageRequest(past, stop.a.coord, stop.b.coord, nowMs(), tzOffsetS()))
        return (answer as? Outcome.Ok)?.value?.text
    }

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

    /** One edit; a refusal is a toast worded from its reason, a success may be a toast too ([done]). */
    private fun edit(
        edit: SavedEdit,
        done: (SavedEditAnswer) -> SavedEffect? = { null },
    ) {
        work.launch {
            when (val answer = store.edit(edit)) {
                is Outcome.Err -> emit(SavedEffect.Toast(refusal(answer.error)))
                is Outcome.Ok -> done(answer.value)?.let(::emit)
            }
        }
    }

    /** A refused note stays on its field (inline), a saved one clears the error. */
    private fun note(
        id: Long,
        note: String,
    ) {
        work.launch {
            when (val answer = store.edit(SavedEdit.SetNote(id, note.trim()))) {
                is Outcome.Err -> mutable.value = mutable.value.copy(noteError = NoteError(id, refusal(answer.error)))
                is Outcome.Ok -> mutable.value = mutable.value.copy(noteError = null)
            }
        }
    }

    private fun favourite(id: Long) =
        edit(SavedEdit.ToggleFavourite(id)) { answer ->
            answer.flag?.let { on ->
                SavedEffect.Toast(UiText.Res(if (on) R.string.saved_favourite_on else R.string.saved_favourite_off))
            }
        }

    private fun cycleColour(id: Long) =
        edit(SavedEdit.CycleColour(id)) { answer ->
            answer.colour?.let {
                SavedEffect.Toast(
                    UiText.Res(R.string.saved_colour, listOf(UiText.Res(colourName(it)))),
                )
            }
        }

    private fun delete(id: Long) =
        edit(SavedEdit.Remove(id)) { answer ->
            answer.removed?.let { SavedEffect.Deleted(it, answer.index ?: 0) }
        }

    private fun copyAll() {
        val now = mutable.value
        if (now.rows.isEmpty()) return
        emit(SavedEffect.Copy(now.listText, UiText.Plural(R.plurals.saved_copied_list, now.rows.size)))
    }

    private fun shareAll() {
        val text = mutable.value.listText
        if (text.isNotEmpty()) emit(SavedEffect.Share(text))
    }

    private fun share(
        id: Long,
        qr: Boolean,
    ) = withStop(id) { stop ->
        val request =
            DestShareRequest(
                at = stop.a.coord,
                label = stop.a.label,
                b = stop.b.coord,
                name = stop.name,
                note = stop.note.ifBlank { null },
            )
        val answer = (ops.destShare(request) as? Outcome.Ok)?.value ?: return@withStop
        emit(if (qr) SavedEffect.Qr(stop.name, answer.qrPayload) else SavedEffect.Share(answer.stopText ?: answer.text))
    }

    private fun shortcut(id: Long) =
        withStop(id) { stop ->
            val spec = (ops.savedShortcut(ShortcutRequest(stop)) as? Outcome.Ok)?.value ?: return@withStop
            emit(SavedEffect.Shortcut(spec))
        }

    private fun saveGate(
        a: Destination,
        b: Destination,
    ) {
        work.launch {
            val ea = Endpoint(a.label, a.coord)
            val eb = Endpoint(b.label, b.coord)
            val name = (ops.savedSuggestName(SuggestNameRequest(ea, eb)) as? Outcome.Ok)?.value?.name ?: b.label
            when (val done = store.edit(SavedEdit.Add(name, ea, eb))) {
                is Outcome.Err -> emit(SavedEffect.Toast(refusal(done.error)))
                is Outcome.Ok -> done.value.id?.let { emit(SavedEffect.Saved(it, name)) }
            }
        }
    }

    companion object {
        private const val HISTORY = "history"

        private val Lenient = Json { ignoreUnknownKeys = true }

        /** The words for a refused edit, from the stable `detail.reason` (Rust's English message is for logs). */
        fun refusal(error: RustError): UiText =
            UiText.Res(
                when (error.reason) {
                    "invalid_name" -> R.string.saved_error_name
                    "duplicate" -> R.string.saved_error_duplicate
                    "full" -> R.string.saved_error_full
                    "invalid_gate" -> R.string.saved_error_gate
                    "not_found" -> R.string.saved_error_gone
                    "note_too_long" -> R.string.saved_error_note
                    else -> R.string.saved_error_other
                },
            )

        /** The name of a map colour in the user's language. */
        @StringRes
        fun colourName(colour: StopColour): Int =
            when (colour) {
                StopColour.Orange -> R.string.saved_colour_orange
                StopColour.Red -> R.string.saved_colour_red
                StopColour.Green -> R.string.saved_colour_green
                StopColour.Blue -> R.string.saved_colour_blue
            }

        /** "Open in maps" for a stop: the middle of its gate (S29). */
        fun geoUriOf(stop: SavedStop): String {
            val lat = (stop.a.coord.lat + stop.b.coord.lat) / 2.0
            val lon = (stop.a.coord.lon + stop.b.coord.lon) / 2.0
            return "geo:$lat,$lon?q=$lat,$lon"
        }
    }
}

/** A saved end as a pick destination. */
fun Endpoint.toDestination(): Destination = Destination(label, coord)
