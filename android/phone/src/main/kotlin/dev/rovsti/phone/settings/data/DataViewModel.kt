package dev.rovsti.phone.settings.data

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.LocalYmdRequest
import dev.rovsti.core.model.contacts.VcardWriteRequest
import dev.rovsti.core.model.phone.CallLogCsvRequest
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.ExtraCall
import dev.rovsti.core.model.phone.NamesByNumber
import dev.rovsti.core.model.phone.NotesExportRequest
import dev.rovsti.core.model.phone.PruneAge
import dev.rovsti.core.model.phone.PrunePlanAnswer
import dev.rovsti.core.model.phone.PrunePlanRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.settings.ExportFile
import dev.rovsti.phone.settings.MIME_CSV
import dev.rovsti.phone.settings.MIME_VCARD
import dev.rovsti.phone.settings.SavedWhat
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.calllog.CallLogResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone

/** What the Data page shows: how much there is to export and whether "Delete all" is waiting out its Undo window. */
@Immutable
data class DataUi(
    val calls: Int = 0,
    val notes: Int = 0,
    val contacts: Int = 0,
    /** The five-second window of "Delete all" is open: the provider has not been touched yet. */
    val deletePending: Boolean = false,
)

/** The calls older than [age], with the core's sentence for the confirm sheet. */
@Immutable
data class OlderChoice(
    val age: PruneAge,
    val count: Int,
)

sealed interface DataAction {
    data object ExportHistory : DataAction

    data object ExportNotes : DataAction

    data object ExportContacts : DataAction

    /** Opens the chooser: 30 days, 90 days, 1 year, each with its count. */
    data object ChooseOlder : DataAction

    /** The user picked [age]: asks the confirm sheet (the core's title and body). */
    data class AskOlder(
        val age: PruneAge,
    ) : DataAction

    /** Confirmed: deletes the calls older than [age]. */
    data class DeleteOlder(
        val age: PruneAge,
    ) : DataAction

    data class UndoOlder(
        val rows: List<CallLogEntry>,
    ) : DataAction

    /** Confirmed: starts the Undo window; the provider is asked once it has run out. */
    data object DeleteAll : DataAction

    /** The Undo pill of "Delete all": nothing was deleted yet and nothing will be. */
    data object UndoDeleteAll : DataAction

    /** The screen was shown or the logs changed: counts again. */
    data object Recount : DataAction
}

sealed interface DataEffect {
    /** A file to save where the user chooses. */
    data class Export(
        val file: ExportFile,
    ) : DataEffect

    /** Nothing to export of [what]. */
    data class NothingToExport(
        val what: SavedWhat,
    ) : DataEffect

    data class OlderChoices(
        val choices: List<OlderChoice>,
    ) : DataEffect

    /** The confirm sheet of "Delete older": [plan] holds the core's title and body. */
    data class ConfirmOlder(
        val age: PruneAge,
        val plan: PrunePlanAnswer,
    ) : DataEffect

    data class DeletedOlder(
        val count: Int,
        val rows: List<CallLogEntry>,
    ) : DataEffect

    data class DeleteAllPending(
        val windowMs: Long,
    ) : DataEffect

    data object DeletedAll : DataEffect

    /** The provider refused (permission) or the core could not answer. */
    data object Failed : DataEffect
}

/**
 * Settings, Data (E5.9b). Exports go through the core (`call_log.csv` with the note column, `call_notes.export` with
 * the names of the callers, `contacts.vcard_write`) and leave the page as [DataEffect.Export] for the host to save or
 * share. "Delete older" uses `call_log.prune_plan` for the count and the sentence, deletes at once and offers Undo
 * (the rows come back through the repository). "Delete all" (U24) waits [undoWindowMs] before the provider is asked:
 * Undo inside the window just cancels the job, so nothing was ever deleted.
 *
 * The call lists are read through lambdas (the Recents repository owns them); [forgetWhatsApp] drops WhatsApp calls
 * and [refresh] reloads Recents after a delete.
 */
class DataViewModel(
    private val ops: PhoneOps,
    private val contactsOps: ContactsOps,
    private val store: PhoneStore,
    private val callLog: CallLogRepository,
    private val entries: () -> List<CallLogEntry>,
    private val extras: () -> List<ExtraCall>,
    private val contacts: () -> List<WireContact>,
    private val names: () -> NamesByNumber,
    private val forgetWhatsApp: (List<String>) -> Unit = {},
    private val refresh: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetS: () -> Int = { TimeZone.getDefault().getOffset(clock()) / MS_PER_S },
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val ui = MutableStateFlow(DataUi())
    private val effects = Channel<DataEffect>(Channel.BUFFERED)
    private var pendingDelete: Job? = null

    val state: StateFlow<DataUi> = ui.asStateFlow()
    val effect: Flow<DataEffect> = effects.receiveAsFlow()

    init {
        work.launch {
            store.load()
            recount()
        }
    }

    fun onAction(action: DataAction) {
        work.launch {
            when (action) {
                DataAction.ExportHistory -> exportHistory()
                DataAction.ExportNotes -> exportNotes()
                DataAction.ExportContacts -> exportContacts()
                DataAction.ChooseOlder -> chooseOlder()
                is DataAction.AskOlder -> askOlder(action.age)
                is DataAction.DeleteOlder -> deleteOlder(action.age)
                is DataAction.UndoOlder -> undoOlder(action.rows)
                DataAction.DeleteAll -> deleteAll()
                DataAction.UndoDeleteAll -> undoDeleteAll()
                DataAction.Recount -> recount()
            }
        }
    }

    private fun recount() {
        ui.update {
            it.copy(
                calls = entries().size + extras().size,
                notes = store.notes.value.notes.size,
                contacts = contacts().size,
            )
        }
    }

    private fun say(effect: DataEffect) {
        effects.trySend(effect)
    }

    private suspend fun exportHistory() {
        if (entries().isEmpty() && extras().isEmpty()) return say(DataEffect.NothingToExport(SavedWhat.History))
        val request =
            CallLogCsvRequest(
                entries = entries(),
                extras = extras(),
                tzOffsetS = tzOffsetS(),
                nowMs = clock(),
                notes = store.notes.value,
            )
        val answer = (ops.callLogCsv(request) as? Outcome.Ok)?.value
        val csv = answer?.csv
        val name = answer?.fileName
        if (csv == null || name == null) {
            say(DataEffect.NothingToExport(SavedWhat.History))
        } else {
            say(DataEffect.Export(ExportFile(name, MIME_CSV, csv, SavedWhat.History)))
        }
    }

    private suspend fun exportNotes() {
        val notes = store.notes.value
        if (notes.notes.isEmpty()) return say(DataEffect.NothingToExport(SavedWhat.Notes))
        val answer =
            (ops.notesExport(NotesExportRequest(notes, names(), tzOffsetS(), clock())) as? Outcome.Ok)?.value
                ?: return say(DataEffect.Failed)
        say(DataEffect.Export(ExportFile(answer.fileName, MIME_CSV, answer.csv, SavedWhat.Notes)))
    }

    private suspend fun exportContacts() {
        val people = contacts()
        if (people.isEmpty()) return say(DataEffect.NothingToExport(SavedWhat.Contacts))
        val now = clock()
        val day =
            (contactsOps.localYmd(LocalYmdRequest(now, tzOffsetS())) as? Outcome.Ok)?.value
                ?: return say(DataEffect.Failed)
        val drafts =
            people.map { c ->
                ContactDraft(
                    id = c.id,
                    given = c.displayName,
                    numbers = c.numbers.map { DraftField(value = it.raw, primary = it.default) },
                    starred = c.starred,
                )
            }
        val file =
            (contactsOps.vcardWrite(VcardWriteRequest(drafts, day)) as? Outcome.Ok)?.value
                ?: return say(DataEffect.Failed)
        say(DataEffect.Export(ExportFile(file.fileName, MIME_VCARD, file.text, SavedWhat.Contacts)))
    }

    private suspend fun plan(age: PruneAge): PrunePlanAnswer? =
        (ops.prunePlan(PrunePlanRequest(age, entries(), extras(), clock())) as? Outcome.Ok)?.value

    private suspend fun chooseOlder() {
        val choices = PruneAge.entries.map { OlderChoice(it, plan(it)?.count ?: 0) }
        say(DataEffect.OlderChoices(choices))
    }

    private suspend fun askOlder(age: PruneAge) {
        val plan = plan(age) ?: return say(DataEffect.Failed)
        say(DataEffect.ConfirmOlder(age, plan))
    }

    private suspend fun deleteOlder(age: PruneAge) {
        val plan = plan(age) ?: return say(DataEffect.Failed)
        if (plan.count == 0) return
        when (val result = callLog.delete(plan.logIds)) {
            is CallLogResult.Ok -> {
                if (plan.callIds.isNotEmpty()) forgetWhatsApp(plan.callIds)
                refresh()
                recount()
                say(DataEffect.DeletedOlder(plan.count, result.value.rows))
            }

            is CallLogResult.Failed -> {
                say(DataEffect.Failed)
            }
        }
    }

    private suspend fun undoOlder(rows: List<CallLogEntry>) {
        if (rows.isEmpty()) return
        callLog.restore(rows)
        refresh()
        recount()
    }

    private fun deleteAll() {
        if (pendingDelete?.isActive == true) return
        if (ui.value.calls == 0) return
        ui.update { it.copy(deletePending = true) }
        say(DataEffect.DeleteAllPending(undoWindowMs))
        pendingDelete =
            work.launch {
                try {
                    delay(undoWindowMs)
                } finally {
                    ui.update { it.copy(deletePending = false) }
                }
                val ids = extras().map { it.callId }
                when (callLog.deleteAll()) {
                    is CallLogResult.Ok -> {
                        if (ids.isNotEmpty()) forgetWhatsApp(ids)
                        refresh()
                        recount()
                        say(DataEffect.DeletedAll)
                    }

                    is CallLogResult.Failed -> {
                        say(DataEffect.Failed)
                    }
                }
            }
    }

    private fun undoDeleteAll() {
        pendingDelete?.cancel()
        pendingDelete = null
        ui.update { it.copy(deletePending = false) }
    }

    private companion object {
        const val MS_PER_S = 1000
        const val UNDO_WINDOW_MS = 5_000L
    }
}
