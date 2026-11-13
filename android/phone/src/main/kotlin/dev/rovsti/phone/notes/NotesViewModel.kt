package dev.rovsti.phone.notes

import androidx.compose.runtime.Immutable
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CallNote
import dev.rovsti.core.model.phone.HistoryLine
import dev.rovsti.core.model.phone.HistoryRequest
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.NoteRemoveRequest
import dev.rovsti.core.model.phone.NoteRestoreRequest
import dev.rovsti.core.model.phone.NoteRowsRequest
import dev.rovsti.core.model.phone.NotesExportRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.RecentsRepository
import dev.rovsti.phone.details.CallDetailsTarget
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One line of the Notes page: the core's title (name, else the number), its "date - first words" subtitle. */
@Immutable
data class NoteRowUi(
    /** Number and start time: what makes a note unique. */
    val key: String,
    val number: String,
    val atMs: Long,
    val title: String,
    val subtitle: String,
    /** The contact's name when the number is saved; null for a stranger. */
    val name: String?,
)

/** What the Notes page draws. */
@Immutable
data class NotesUi(
    val ready: Boolean = false,
    val query: String = "",
    /** Notes in all (a search may show fewer). */
    val total: Int = 0,
    val rows: ImmutableList<NoteRowUi> = persistentListOf(),
) {
    val searching: Boolean get() = query.isNotBlank()
    val empty: Boolean get() = ready && rows.isEmpty()
}

sealed interface NotesAction {
    data class SetQuery(
        val text: String,
    ) : NotesAction

    data class Open(
        val key: String,
    ) : NotesAction

    data class Delete(
        val key: String,
    ) : NotesAction

    data class Undo(
        val note: CallNote,
    ) : NotesAction

    data object ShareAll : NotesAction

    data object ExportCsv : NotesAction
}

enum class NotesNotice { CallGone, DeleteFailed, UndoFailed, ExportFailed }

sealed interface NotesEffect {
    /** The row's call is in the log: its details sheet opens. */
    data class OpenCall(
        val target: CallDetailsTarget,
    ) : NotesEffect

    data class Undoable(
        val note: CallNote,
    ) : NotesEffect

    data class Say(
        val notice: NotesNotice,
    ) : NotesEffect

    data class ShareText(
        val text: String,
    ) : NotesEffect

    data class SaveCsv(
        val fileName: String,
        val csv: String,
    ) : NotesEffect

    data object Reject : NotesEffect
}

/**
 * The brain of the Notes page (E5.11, U30, U33, U42). The list, the search, the row texts (dates in the phone's own clock
 * and date style), the share text and the CSV are the core's (`call_notes.rows` / `export`); this class owns the query,
 * finds the logged call a row stands for (`call_log.history`), and removes and restores notes (`call_notes.remove` /
 * `restore`) through the Phone store, which writes the file.
 *
 * [nameOf] answers the saved name of a number (null for a stranger); a row without a name is titled by the number as people
 * read it ([display], F38). [scope] belongs to the page: the owner calls [close].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(
    private val store: PhoneStore,
    private val recents: RecentsRepository,
    private val ops: PhoneOps,
    private val nameOf: suspend (String) -> String?,
    private val display: NumberDisplay = NumberDisplay.AsIs,
    private val locale: () -> LocaleTime,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetS: () -> Int = { locale().tzOffsetS },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val effects = Channel<NotesEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    private val query = MutableStateFlow("")

    @Volatile private var names: Map<String, String> = emptyMap()

    val state: StateFlow<NotesUi> =
        combine(store.notes, query) { notes, text -> notes to text }
            .mapLatest { (notes, text) -> build(notes, text) }
            .flowOn(compute)
            .stateIn(scope, SharingStarted.Eagerly, NotesUi())

    /** Stops everything the page started. */
    fun close() {
        scope.cancel()
    }

    private suspend fun namesFor(notes: List<CallNote>): Map<String, String> {
        val out = HashMap<String, String>()
        for (number in notes.map { it.number }.distinct()) {
            nameOf(number)?.takeIf { it.isNotBlank() }?.let { out[number.trim()] = it }
        }
        names = out
        return out
    }

    private suspend fun build(
        notes: dev.rovsti.core.model.phone.Notes,
        text: String,
    ): NotesUi {
        val known = namesFor(notes.notes)
        val answer =
            (
                ops.noteRows(NoteRowsRequest(notes, text, known, locale())) as? Outcome.Ok
            )?.value ?: return NotesUi(ready = true, query = text, total = notes.notes.size)
        return NotesUi(
            ready = true,
            query = text,
            total = notes.notes.size,
            rows =
                answer.rows
                    .map { row ->
                        NoteRowUi(
                            key = rowKey(row.number, row.atMs),
                            number = row.number,
                            atMs = row.atMs,
                            title =
                                row.title.takeIf { known.containsKey(row.number.trim()) } ?: display.text(row.number),
                            subtitle = row.subtitle,
                            name = known[row.number.trim()],
                        )
                    }.toImmutableList(),
        )
    }

    fun onAction(action: NotesAction) {
        when (action) {
            is NotesAction.SetQuery -> {
                query.value = action.text
            }

            is NotesAction.Open -> {
                scope.launch(compute) { open(action.key) }
            }

            is NotesAction.Delete -> {
                scope.launch(compute) { delete(action.key) }
            }

            is NotesAction.Undo -> {
                scope.launch(compute) { undo(action.note) }
            }

            NotesAction.ShareAll -> {
                scope.launch(compute) { export()?.let { say(NotesEffect.ShareText(it.text)) } }
            }

            NotesAction.ExportCsv -> {
                scope.launch(compute) {
                    export()?.let { say(NotesEffect.SaveCsv(it.fileName, it.csv)) }
                }
            }
        }
    }

    private fun say(effect: NotesEffect) {
        effects.trySend(effect)
    }

    private fun rowOf(key: String): NoteRowUi? = state.value.rows.firstOrNull { it.key == key }

    /** The logged call at the note's start time (a few seconds of slack: the log and the note may round differently). */
    private suspend fun callOf(row: NoteRowUi): HistoryLine? {
        val lines =
            (
                ops.history(
                    HistoryRequest(recents.callEntries, recents.extraCalls, listOf(row.number), HISTORY_LIMIT),
                ) as? Outcome.Ok
            )?.value?.lines.orEmpty()
        return lines.firstOrNull { it.dateMs == row.atMs }
            ?: lines.filter { abs(it.dateMs - row.atMs) <= SLACK_MS }.minByOrNull { abs(it.dateMs - row.atMs) }
    }

    private suspend fun open(key: String) {
        val row = rowOf(key) ?: return
        val line = callOf(row)
        if (line == null) {
            say(NotesEffect.Reject)
            say(NotesEffect.Say(NotesNotice.CallGone))
        } else {
            say(NotesEffect.OpenCall(CallDetailsTarget(line, row.name)))
        }
    }

    private suspend fun delete(key: String) {
        val row = rowOf(key) ?: return
        val out = (ops.noteRemove(NoteRemoveRequest(store.notes.value, row.number, row.atMs)) as? Outcome.Ok)?.value
        val removed = out?.removed
        if (out == null || removed == null) {
            say(NotesEffect.Reject)
            say(NotesEffect.Say(NotesNotice.DeleteFailed))
            return
        }
        store.updateNotes { out.notes }
        say(NotesEffect.Undoable(removed))
    }

    private suspend fun undo(note: CallNote) {
        val restored = (ops.noteRestore(NoteRestoreRequest(store.notes.value, note)) as? Outcome.Ok)?.value
        if (restored == null) {
            say(NotesEffect.Say(NotesNotice.UndoFailed))
        } else {
            store.updateNotes { restored.notes }
        }
    }

    /** All notes as text and CSV, with the names the page knows; null (and a notice) when there is nothing to send. */
    private suspend fun export(): dev.rovsti.core.model.phone.NotesExportAnswer? {
        val notes = store.notes.value
        if (notes.notes.isEmpty()) return null
        val answer =
            (
                ops.notesExport(NotesExportRequest(notes, names, tzOffsetS(), clock())) as? Outcome.Ok
            )?.value
        if (answer == null) {
            say(NotesEffect.Reject)
            say(NotesEffect.Say(NotesNotice.ExportFailed))
        }
        return answer
    }

    companion object {
        private const val HISTORY_LIMIT = 5_000
        private const val SLACK_MS = 5_000L

        fun rowKey(
            number: String,
            atMs: Long,
        ) = "$number@$atMs"
    }
}
