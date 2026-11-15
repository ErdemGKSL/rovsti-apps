package dev.rosty.stops.data

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.HistoryEdit
import dev.rosty.core.model.stops.HistoryEditAnswer
import dev.rosty.core.model.stops.HistoryEditRequest
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
 * The trip history (`history.json`, newest first) as a flow. The trip service appends a record when a trip ends, so
 * [reload] reads the file again whenever History opens. Every change is one `stops.history_edit` (take, put back,
 * prune, clear: Rust decides) whose answer is written with `stops.store_write` before the flow moves; [replace] puts a
 * whole history back (Undo, restore). One change at a time under a [Mutex].
 */
class HistoryStore(
    private val ops: StopsOps,
    private val dir: String? = null,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<History?>(null)

    /** The history; null until the first [reload]. */
    val history: StateFlow<History?> = state.asStateFlow()

    /** Reads the file (a missing or unreadable one is an empty history). */
    suspend fun reload(): History =
        mutex.withLock {
            val read = (ops.storeRead(StoreReadRequest(FILE, dir)) as? Outcome.Ok)?.value?.value
            val history =
                read?.let {
                    try {
                        Lenient.decodeFromJsonElement(History.serializer(), it)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                } ?: EMPTY
            state.value = history
            history
        }

    /** Applies [edit] and saves the result. */
    suspend fun edit(edit: HistoryEdit): Outcome<HistoryEditAnswer> =
        mutex.withLock {
            val current = state.value ?: EMPTY
            when (val answer = ops.historyEdit(HistoryEditRequest(current, edit))) {
                is Outcome.Err -> {
                    answer
                }

                is Outcome.Ok -> {
                    when (val written = write(answer.value.history)) {
                        is Outcome.Err -> written
                        is Outcome.Ok -> answer
                    }
                }
            }
        }

    /** Writes [history] as it is. */
    suspend fun replace(history: History): Outcome<History> = mutex.withLock { write(history) }

    private suspend fun write(history: History): Outcome<History> {
        val json = Json.encodeToJsonElement(History.serializer(), history)
        return when (val written = ops.storeWrite(StoreWriteRequest(FILE, json, dir))) {
            is Outcome.Err -> {
                written
            }

            is Outcome.Ok -> {
                state.value = history
                Outcome.Ok(history)
            }
        }
    }

    companion object {
        const val FILE = "history"
        val EMPTY = History(emptyList())
        private val Lenient = Json { ignoreUnknownKeys = true }
    }
}
