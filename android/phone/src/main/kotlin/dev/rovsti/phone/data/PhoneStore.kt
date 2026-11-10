package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.phone.DirRequest
import dev.rovsti.core.model.phone.Notes
import dev.rovsti.core.model.phone.NotesSaveRequest
import dev.rovsti.core.model.phone.PhonePrefs
import dev.rovsti.core.model.phone.PrefsSaveRequest
import dev.rovsti.core.ops.PhoneOps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Phone preferences (`prefs.json`, schema 2) and the call notes (`call-notes.json`): files in the data directory
 * the Rust core owns (`phone.prefs_*`, `phone.notes_*`). Both are held as state flows. An edit changes the flow at
 * once and is written [debounceMs] after the last edit (a slider or a burst of toggles is one write); a [Mutex]
 * serialises the loads and the writes, which run on [io]. [flush] writes now (the app calls it when it stops).
 *
 * A preferences file written by a newer app ([origin] `newer`) is never overwritten. A failed write keeps the edit
 * pending and the next edit or [flush] tries again; [lastError] says what went wrong.
 *
 * [dir] names another data directory (tests); null is the one `core.init` was given.
 */
class PhoneStore(
    private val ops: PhoneOps,
    private val scope: CoroutineScope,
    private val dir: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val disk = Mutex()
    private var loaded = false
    private var saver: Job? = null
    private var prefsDirty = false
    private var notesDirty = false
    private var readOnlyPrefs = false

    private val prefsState = MutableStateFlow(DEFAULT_PREFS)
    private val notesState = MutableStateFlow(EMPTY_NOTES)
    private val errorState = MutableStateFlow<RustError?>(null)

    /** The preferences; [DEFAULT_PREFS] until the first [load] or [updatePrefs]. */
    val prefs: StateFlow<PhonePrefs> = prefsState.asStateFlow()

    /** The call notes. */
    val notes: StateFlow<Notes> = notesState.asStateFlow()

    /** `missing`, `current`, `upgraded_from_v1`, `newer` or `corrupt`: how the file was read. Null before the load. */
    @Volatile
    var origin: String? = null
        private set

    /** The last load or write failure; null after a success. */
    val lastError: StateFlow<RustError?> = errorState.asStateFlow()

    /** Reads both files (once); later calls answer at once. */
    suspend fun load(): Outcome<Unit> = disk.withLock { ensureLoaded() }

    /** Changes the preferences now and schedules the write. Nothing changes when the stored file could not be read. */
    suspend fun updatePrefs(change: (PhonePrefs) -> PhonePrefs) {
        if (load() is Outcome.Err) return
        var changed = false
        prefsState.update { old ->
            val next = change(old)
            changed = next != old
            next
        }
        if (changed) {
            prefsDirty = true
            schedule()
        }
    }

    /** Changes the notes now and schedules the write. */
    suspend fun updateNotes(change: (Notes) -> Notes) {
        if (load() is Outcome.Err) return
        var changed = false
        notesState.update { old ->
            val next = change(old)
            changed = next != old
            next
        }
        if (changed) {
            notesDirty = true
            schedule()
        }
    }

    /** Writes what is pending now; the answer is false when a write failed. */
    suspend fun flush(): Boolean {
        saver?.cancel()
        saver = null
        return disk.withLock { write() }
    }

    private fun schedule() {
        saver?.cancel()
        saver =
            scope.launch {
                delay(debounceMs)
                disk.withLock { write() }
            }
    }

    /** The caller holds [disk]. */
    private suspend fun ensureLoaded(): Outcome<Unit> {
        if (loaded) return Outcome.Ok(Unit)
        val req = DirRequest(dir)
        val read = withContext(io) { ops.prefsLoad(req) }
        val notes = withContext(io) { ops.notesLoad(req) }
        var failure: RustError? = null
        when (read) {
            is Outcome.Err -> {
                failure = read.error
            }

            is Outcome.Ok -> {
                origin = read.value.origin
                readOnlyPrefs = read.value.origin == ORIGIN_NEWER
                // Edits made before the load finished (none today) win over the stored value.
                if (!prefsDirty) prefsState.value = read.value.prefs
            }
        }
        when (notes) {
            is Outcome.Err -> failure = failure ?: notes.error
            is Outcome.Ok -> if (!notesDirty) notesState.value = notes.value.notes
        }
        errorState.value = failure
        if (failure != null) return Outcome.Err(failure)
        loaded = true
        return Outcome.Ok(Unit)
    }

    /** The caller holds [disk]. */
    private suspend fun write(): Boolean =
        // A cancelled saver (a newer edit, flush) must not leave the edit marked as written when it was not.
        withContext(NonCancellable) { writePending() }

    private suspend fun writePending(): Boolean {
        var failure: RustError? = null
        if (prefsDirty && !readOnlyPrefs) {
            prefsDirty = false
            val prefs = prefsState.value
            val out = withContext(io) { ops.prefsSave(PrefsSaveRequest(dir, prefs)) }
            if (out is Outcome.Err) {
                prefsDirty = true
                failure = out.error
            }
        }
        if (notesDirty) {
            notesDirty = false
            val notes = notesState.value
            val out = withContext(io) { ops.notesSave(NotesSaveRequest(dir, notes)) }
            if (out is Outcome.Err) {
                notesDirty = true
                failure = failure ?: out.error
            }
        }
        errorState.value = failure
        return failure == null
    }

    companion object {
        /** The write follows the last edit by this long. */
        const val DEBOUNCE_MS = 300L

        private const val ORIGIN_NEWER = "newer"

        /** What the core holds for a new install, for the first frame before the load. */
        val DEFAULT_PREFS = PhonePrefs()

        val EMPTY_NOTES = Notes(schema = 1, notes = emptyList())
    }
}
