package dev.rosty.stops.data

import dev.rosty.core.Outcome
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.PrefsEdit
import dev.rosty.core.model.stops.PrefsEditAnswer
import dev.rosty.core.model.stops.PrefsEditRequest
import dev.rosty.core.model.stops.PrefsSaveRequest
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.ops.StopsOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Stops preferences (`stops-prefs.json`, the GPUI app's file read unchanged by `stops.prefs_load`), held as a state
 * flow. [update] reads the stored file the first time, applies the change and writes it back, one at a time under a
 * [Mutex], so two screens editing at once never lose a write; a failed write leaves the flow as it was. [dir] names
 * another data directory (tests); null is the one `core.init` was given.
 */
class StopsStore(
    private val ops: StopsOps,
    private val dir: String? = null,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val state = MutableStateFlow(StopsPrefs())

    /** The preferences; the defaults until the first [load] or [update]. */
    val prefs: StateFlow<StopsPrefs> = state.asStateFlow()

    /** Reads the stored preferences (once); later calls answer the held value. */
    suspend fun load(): Outcome<StopsPrefs> = mutex.withLock { ensureLoaded() }

    /** Applies [change] to the stored preferences and writes the result; answers what is held afterwards. */
    suspend fun update(change: (StopsPrefs) -> StopsPrefs): Outcome<StopsPrefs> =
        mutex.withLock {
            val current =
                when (val read = ensureLoaded()) {
                    is Outcome.Err -> return@withLock read
                    is Outcome.Ok -> read.value
                }
            val next = change(current)
            if (next == current) return@withLock Outcome.Ok(current)
            when (val saved = ops.prefsSave(PrefsSaveRequest(next, dir))) {
                is Outcome.Err -> {
                    saved
                }

                is Outcome.Ok -> {
                    state.value = next
                    Outcome.Ok(next)
                }
            }
        }

    /**
     * Applies the named [edit] in Rust (`stops.prefs_edit`: the rules stay there) and saves the result when it changed.
     * With [until], the edit is applied again (at most [maxSteps] times, all in memory) until the preferences satisfy it:
     * a value picked from `stops.prefs_choices` reached through Rust's own `next_*` cycle. One write at the end.
     */
    suspend fun edit(
        edit: PrefsEdit,
        maxSteps: Int = 1,
        until: ((StopsPrefs) -> Boolean)? = null,
    ): Outcome<PrefsEditAnswer> =
        mutex.withLock {
            val current =
                when (val read = ensureLoaded()) {
                    is Outcome.Err -> return@withLock read
                    is Outcome.Ok -> read.value
                }
            var answer: PrefsEditAnswer? = null
            var prefs = current
            var steps = 0
            val limit = maxSteps.coerceAtLeast(1)
            while (steps < limit && if (until == null) steps == 0 else !until(prefs)) {
                answer =
                    when (val edited = ops.prefsEdit(PrefsEditRequest(prefs, edit))) {
                        is Outcome.Err -> return@withLock edited
                        is Outcome.Ok -> edited.value
                    }
                prefs = answer.prefs
                steps++
            }
            val done =
                answer
                    // Nothing to do: describe the held value (a no-op edit answers the derived fields).
                    ?: when (
                        val described =
                            ops.prefsEdit(
                                PrefsEditRequest(prefs, PrefsEdit.SetShowSaved(prefs.showSavedOnMap)),
                            )
                    ) {
                        is Outcome.Err -> return@withLock described
                        is Outcome.Ok -> described.value.copy(changed = false)
                    }
            if (done.prefs == current) return@withLock Outcome.Ok(done.copy(changed = false))
            when (val saved = ops.prefsSave(PrefsSaveRequest(done.prefs, dir))) {
                is Outcome.Err -> {
                    saved
                }

                is Outcome.Ok -> {
                    state.value = done.prefs
                    Outcome.Ok(done)
                }
            }
        }

    /** What the held preferences derive to (alarm label and default flag, tile URL), changing nothing. */
    suspend fun describe(): Outcome<PrefsEditAnswer> =
        edit(PrefsEdit.SetShowSaved(prefs.value.showSavedOnMap), until = {
            true
        })

    /** Reads the stored file once. The caller holds the lock. */
    private suspend fun ensureLoaded(): Outcome<StopsPrefs> {
        if (loaded) return Outcome.Ok(state.value)
        return when (val read = ops.prefsLoad(DirRequest(dir))) {
            is Outcome.Err -> {
                read
            }

            is Outcome.Ok -> {
                loaded = true
                state.value = read.value.prefs
                Outcome.Ok(read.value.prefs)
            }
        }
    }
}
