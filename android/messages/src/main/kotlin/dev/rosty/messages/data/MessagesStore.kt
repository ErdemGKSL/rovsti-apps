package dev.rosty.messages.data

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.messages.DirRequest
import dev.rosty.core.model.messages.DraftsPutRequest
import dev.rosty.core.model.messages.DraftsRekeyRequest
import dev.rosty.core.model.messages.DraftsSaveRequest
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.model.messages.PrefsSaveRequest
import dev.rosty.core.ops.MessagesOps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * The Messages preferences (`messages-prefs.json`) and the unsent drafts (`drafts.json`): files in the data directory
 * that the Rust core owns (`messages.prefs_*`, `messages.drafts_*`), the same files the GPUI app wrote (ARCH 7.1).
 *
 * Both are state flows. An edit changes the flow at once and is written [debounceMs] after the last edit; a [Mutex]
 * serialises the loads and the writes, which run on [io]. [flush] writes now (the app calls it when it stops). A failed
 * load keeps the defaults and never overwrites the file; [lastError] says what went wrong.
 *
 * [dir] names another data directory (tests); null is the one `core.init` was given.
 */
class MessagesStore(
    private val ops: MessagesOps,
    private val scope: CoroutineScope,
    private val dir: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val disk = Mutex()
    private var loaded = false
    private var readOnly = false
    private var saver: Job? = null
    private var prefsDirty = false
    private var draftsDirty = false

    private val prefsState = MutableStateFlow<MessagesPrefs?>(null)
    private val draftsState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val errorState = MutableStateFlow<RustError?>(null)

    /** The preferences; null until the first [load] answered. */
    val prefs: StateFlow<MessagesPrefs?> = prefsState.asStateFlow()

    /** Conversation tag (`sms:7`, `wa:<jid>`) to its unsent text. */
    val drafts: StateFlow<Map<String, String>> = draftsState.asStateFlow()

    val lastError: StateFlow<RustError?> = errorState.asStateFlow()

    /** Reads both files once; later calls answer at once. */
    suspend fun load(): Outcome<Unit> = disk.withLock { ensureLoaded() }

    /** Changes the preferences now and schedules the write. Nothing changes when the stored file could not be read. */
    suspend fun updatePrefs(change: (MessagesPrefs) -> MessagesPrefs) {
        if (load() is Outcome.Err || readOnly) return
        var changed = false
        prefsState.update { old ->
            old?.let(change).also { changed = it != old }
        }
        if (changed) {
            prefsDirty = true
            schedule()
        }
    }

    /** Puts the draft of [thread] (blank removes it), through Rust's rule (`drafts_put`). */
    suspend fun putDraft(
        thread: String,
        text: String,
    ) {
        if (load() is Outcome.Err) return
        val answer = ops.draftsPut(DraftsPutRequest(draftsState.value, thread, text))
        val next = (answer as? Outcome.Ok)?.value?.drafts ?: return
        if (next != draftsState.value) {
            draftsState.value = next
            draftsDirty = true
            schedule()
        }
    }

    /**
     * A chat merged into another one (`wa:<lid>` into `wa:<number>`): its draft and its mute move to the tag
     * [into], by Rust's rules (`drafts_rekey`: the draft of [into] is kept when both have one; the prefs' `rekey`:
     * the mute and its end follow). Running it again changes nothing.
     */
    suspend fun rekeyThread(
        from: String,
        into: String,
    ) {
        if (from == into) return
        load()
        val drafts = (ops.draftsRekey(DraftsRekeyRequest(draftsState.value, from, into)) as? Outcome.Ok)?.value?.drafts
        if (drafts != null && drafts != draftsState.value) {
            draftsState.value = drafts
            draftsDirty = true
        }
        val prefs = prefsState.value
        if (prefs != null && !readOnly) {
            val edited =
                (
                    ops.prefsEdit(
                        PrefsEditRequest(prefs, "rekey", from = from, into = into),
                    ) as? Outcome.Ok
                )?.value
            if (edited != null && edited.changed) {
                prefsState.value = edited.prefs
                prefsDirty = true
            }
        }
        if (draftsDirty || prefsDirty) schedule()
    }

    /**
     * Forgets what is kept for every thread whose tag starts with [prefix] (`wa:`: the WhatsApp account they belonged
     * to is gone): the drafts (`drafts_put` with nothing) and the mutes with their ends (the prefs' `unmute`).
     */
    suspend fun forgetThreads(prefix: String) {
        if (load() is Outcome.Err) return
        for (thread in draftsState.value.keys.filter { it.startsWith(prefix) }) putDraft(thread, "")
        val before = prefsState.value
        if (before == null || readOnly) return
        var prefs: MessagesPrefs = before
        for (tag in (prefs.muted + prefs.muteUntil.keys).filter { it.startsWith(prefix) }.distinct()) {
            val edited = (ops.prefsEdit(PrefsEditRequest(prefs, "unmute", tag = tag)) as? Outcome.Ok)?.value
            if (edited != null) prefs = edited.prefs
        }
        if (prefs != before) {
            prefsState.value = prefs
            prefsDirty = true
            schedule()
        }
    }

    /** Writes what is pending now; false when a write failed. */
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
        val prefs = withContext(io) { ops.prefsLoad(DirRequest(dir)) }
        val drafts = withContext(io) { ops.draftsLoad(DirRequest(dir)) }
        when (prefs) {
            is Outcome.Ok -> {
                prefsState.value = prefs.value.prefs
            }

            is Outcome.Err -> {
                errorState.value = prefs.error
                readOnly = true
            }
        }
        (drafts as? Outcome.Ok)?.let { draftsState.value = it.value.drafts }
        loaded = true
        return if (prefs is Outcome.Err) prefs else Outcome.Ok(Unit)
    }

    /** The caller holds [disk]. */
    private suspend fun write(): Boolean {
        var ok = true
        val prefs = prefsState.value
        if (prefsDirty && prefs != null && !readOnly) {
            when (val saved = withContext(io) { ops.prefsSave(PrefsSaveRequest(dir, prefs)) }) {
                is Outcome.Ok -> {
                    prefsDirty = false
                    errorState.value = null
                }

                is Outcome.Err -> {
                    errorState.value = saved.error
                    ok = false
                }
            }
        }
        if (draftsDirty) {
            when (val saved = withContext(io) { ops.draftsSave(DraftsSaveRequest(dir, draftsState.value)) }) {
                is Outcome.Ok -> {
                    draftsDirty = false
                }

                is Outcome.Err -> {
                    errorState.value = saved.error
                    ok = false
                }
            }
        }
        return ok
    }

    companion object {
        const val DEBOUNCE_MS = 400L
    }
}
