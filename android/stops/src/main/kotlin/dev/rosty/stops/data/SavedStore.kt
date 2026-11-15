package dev.rosty.stops.data

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.SavedEdit
import dev.rosty.core.model.stops.SavedEditAnswer
import dev.rosty.core.model.stops.SavedEditRequest
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.StoreReadRequest
import dev.rosty.core.model.stops.StoreWriteRequest
import dev.rosty.core.ops.StopsOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The saved stops (`saved.json`, format 2) as a flow. Every change is one `stops.saved_edit` (Rust decides: name
 * rules, duplicates, the cap, the order) whose answer is written back with `stops.store_write` before the flow moves,
 * one edit at a time under a [Mutex]. A refused edit or a failed write leaves the list as it was and answers the error.
 * [dir] names another data directory (tests); [nowMs] is the wall clock (the created time of a new stop).
 */
class SavedStore(
    private val ops: StopsOps,
    private val dir: String? = null,
    private val nowMs: () -> Long,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val state = MutableStateFlow<SavedStops?>(null)

    /** The list; null until the first [load]. */
    val saved: StateFlow<SavedStops?> = state.asStateFlow()

    /** Reads `saved.json` once (a missing or old file is an empty list, as Rust answers). */
    suspend fun load(): SavedStops = mutex.withLock { ensureLoaded() }

    /** Reads the file again (another screen or the import wrote it). */
    suspend fun reload(): SavedStops =
        mutex.withLock {
            loaded = false
            ensureLoaded()
        }

    /** Applies [edit] and saves the result. */
    suspend fun edit(edit: SavedEdit): Outcome<SavedEditAnswer> =
        mutex.withLock {
            val current = ensureLoaded()
            when (val answer = ops.savedEdit(SavedEditRequest(current, edit, nowMs()))) {
                is Outcome.Err -> {
                    answer
                }

                is Outcome.Ok -> {
                    val json = Json.encodeToJsonElement(SavedStops.serializer(), answer.value.saved)
                    when (val written = ops.storeWrite(StoreWriteRequest(FILE, json, dir))) {
                        is Outcome.Err -> {
                            written
                        }

                        is Outcome.Ok -> {
                            state.value = answer.value.saved
                            answer
                        }
                    }
                }
            }
        }

    /** Writes [saved] as it is (a restored backup, an import, their Undo). */
    suspend fun replace(saved: SavedStops): Outcome<SavedStops> =
        mutex.withLock {
            val json = Json.encodeToJsonElement(SavedStops.serializer(), saved)
            when (val written = ops.storeWrite(StoreWriteRequest(FILE, json, dir))) {
                is Outcome.Err -> {
                    written
                }

                is Outcome.Ok -> {
                    loaded = true
                    state.value = saved
                    Outcome.Ok(saved)
                }
            }
        }

    private suspend fun ensureLoaded(): SavedStops {
        state.value?.takeIf { loaded }?.let { return it }
        val read = (ops.storeRead(StoreReadRequest(FILE, dir)) as? Outcome.Ok)?.value?.value
        val saved =
            read?.let {
                try {
                    Lenient.decodeFromJsonElement(SavedStops.serializer(), it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: EMPTY
        loaded = true
        state.value = saved
        return saved
    }

    companion object {
        const val FILE = "saved"

        /** A list with nothing in it (`SavedStops::default`, format 2). */
        val EMPTY = SavedStops(version = 2, nextId = 1, stops = emptyList())

        private val Lenient = Json { ignoreUnknownKeys = true }
    }
}
