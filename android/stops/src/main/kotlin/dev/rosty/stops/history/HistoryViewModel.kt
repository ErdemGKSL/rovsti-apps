package dev.rosty.stops.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.HistoryEdit
import dev.rosty.core.model.stops.HistoryExportRequest
import dev.rosty.core.model.stops.HistoryListRequest
import dev.rosty.core.model.stops.HistoryRow
import dev.rosty.core.model.stops.HistoryTextRequest
import dev.rosty.core.model.stops.PruneChoice
import dev.rosty.core.model.stops.PrunePlanRequest
import dev.rosty.core.model.stops.StatsAnswer
import dev.rosty.core.model.stops.StatsRequest
import dev.rosty.core.ops.StopsOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.R
import dev.rosty.stops.data.HistoryStore
import dev.rosty.stops.data.Latest
import dev.rosty.stops.saved.toDestination
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The outcome filter of S15; [wire] is the `filter` of `stops.history_list`. */
enum class HistoryFilter(
    val wire: String,
) {
    All("all"),
    Arrived("arrived"),
    Cancelled("cancelled"),
}

/** The History sheet (AD-ST8). */
@Immutable
data class HistoryUiState(
    val loading: Boolean = true,
    /** Every record, whatever the search and the filter show. */
    val total: Int = 0,
    val query: String = "",
    val filter: HistoryFilter = HistoryFilter.All,
    val rows: ImmutableList<HistoryRow> = persistentListOf(),
    val stats: StatsAnswer? = null,
    /** S36: "Delete 12 trips older than 30 days", the enabled choices only. */
    val prune: ImmutableList<PruneChoice> = persistentListOf(),
)

sealed interface HistoryAction {
    data object Refresh : HistoryAction

    data class Query(
        val text: String,
    ) : HistoryAction

    data class Filter(
        val filter: HistoryFilter,
    ) : HistoryAction

    data class StartAgain(
        val row: HistoryRow,
    ) : HistoryAction

    data class ShowOnMap(
        val row: HistoryRow,
    ) : HistoryAction

    data class SaveAsStop(
        val row: HistoryRow,
    ) : HistoryAction

    data class Share(
        val row: HistoryRow,
    ) : HistoryAction

    data class Copy(
        val row: HistoryRow,
    ) : HistoryAction

    data class Delete(
        val row: HistoryRow,
    ) : HistoryAction

    data class Prune(
        val choice: PruneChoice,
    ) : HistoryAction

    data object Clear : HistoryAction

    /** S16: the trips on screen as `csv` or `gpx`. */
    data class Export(
        val format: String,
    ) : HistoryAction

    /** Undo of a delete, a prune, a clear or a restore: [history] goes back as it was. */
    data class Restore(
        val history: History,
    ) : HistoryAction
}

sealed interface HistoryEffect {
    data class Toast(
        val text: UiText,
    ) : HistoryEffect

    /** Something left the history: [undo] puts the old one back. */
    data class Undoable(
        val text: UiText,
        val undo: History,
    ) : HistoryEffect

    data class Start(
        val a: Destination,
        val b: Destination,
    ) : HistoryEffect

    data class ShowOnMap(
        val a: Destination,
        val b: Destination,
    ) : HistoryEffect

    data class SaveGate(
        val a: Destination,
        val b: Destination,
    ) : HistoryEffect

    data class Share(
        val text: String,
    ) : HistoryEffect

    data class Copy(
        val text: String,
    ) : HistoryEffect

    /** "Save as" for [text] under [fileName]. */
    data class Export(
        val fileName: String,
        val mime: String,
        val text: String,
    ) : HistoryEffect
}

/**
 * The trip history (AD-ST8, S15, S16, S33, S36): every list, statistic, prune choice, share text and export comes
 * from the core (`stops.history_list`, `stats`, `history_prune_plan`, `history_text`, `history_export`) over the
 * [store]'s file; edits are `stops.history_edit`. [today] is the local date of the export file names; [tzOffsetS] the
 * zone of the times in CSV and share texts.
 */
class HistoryViewModel(
    private val ops: StopsOps,
    private val store: HistoryStore,
    private val nowMs: () -> Long,
    private val tzOffsetS: () -> Int,
    private val today: () -> String,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val ui = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = ui.asStateFlow()

    private val effectChannel = Channel<HistoryEffect>(Channel.BUFFERED)
    val effects: Flow<HistoryEffect> = effectChannel.receiveAsFlow()

    private val listing = Latest(work)

    fun onAction(action: HistoryAction) {
        when (action) {
            HistoryAction.Refresh -> {
                work.launch {
                    store.reload()
                    relist(debounce = false, withStats = true)
                }
            }

            is HistoryAction.Query -> {
                if (action.text == ui.value.query) return
                ui.update { it.copy(query = action.text) }
                relist(debounce = true)
            }

            is HistoryAction.Filter -> {
                ui.update { it.copy(filter = action.filter) }
                relist(debounce = false)
            }

            is HistoryAction.StartAgain -> {
                ends(action.row)?.let { (a, b) -> emit(HistoryEffect.Start(a, b)) }
            }

            is HistoryAction.ShowOnMap -> {
                ends(action.row)?.let { (a, b) -> emit(HistoryEffect.ShowOnMap(a, b)) }
            }

            is HistoryAction.SaveAsStop -> {
                ends(action.row)?.let { (a, b) -> emit(HistoryEffect.SaveGate(a, b)) }
            }

            is HistoryAction.Share -> {
                shareText(action.row) { emit(HistoryEffect.Share(it)) }
            }

            is HistoryAction.Copy -> {
                shareText(action.row) { emit(HistoryEffect.Copy(it)) }
            }

            is HistoryAction.Delete -> {
                edit(HistoryEdit.Take(action.row.index)) { _ ->
                    UiText.Res(R.string.history_deleted, listOf(action.row.title))
                }
            }

            is HistoryAction.Prune -> {
                val days = action.choice.days
                edit(HistoryEdit.Prune(days, nowMs())) { removed ->
                    UiText.Plural(R.plurals.history_pruned, removed, listOf(removed))
                }
            }

            HistoryAction.Clear -> {
                edit(HistoryEdit.Clear) { _ -> UiText.Res(R.string.history_cleared) }
            }

            is HistoryAction.Export -> {
                export(action.format)
            }

            is HistoryAction.Restore -> {
                work.launch {
                    store.replace(action.history)
                    relist(debounce = false, withStats = true)
                }
            }
        }
    }

    private fun emit(effect: HistoryEffect) {
        effectChannel.trySend(effect)
    }

    private fun ends(row: HistoryRow): Pair<Destination, Destination>? {
        val a = row.record.a ?: return null
        val b = row.record.b ?: return null
        return a.toDestination() to b.toDestination()
    }

    /** S33: "Arrived at Home – Work on 5 Nov 2026, 18:42. 18 min · 3.4 km" and a link to B. */
    private fun shareText(
        row: HistoryRow,
        then: (String) -> Unit,
    ) {
        work.launch {
            val answer = ops.historyText(HistoryTextRequest(record = row.record, tzOffsetS = tzOffsetS()))
            (answer as? Outcome.Ok)?.value?.share?.let(then)
        }
    }

    private fun edit(
        edit: HistoryEdit,
        said: (Int) -> UiText,
    ) {
        work.launch {
            val before = store.history.value ?: store.reload()
            when (val answer = store.edit(edit)) {
                is Outcome.Err -> {
                    emit(HistoryEffect.Toast(UiText.Res(R.string.history_not_saved)))
                }

                is Outcome.Ok -> {
                    val removed = if (edit is HistoryEdit.Take) 1 else answer.value.removed
                    if (removed > 0) emit(HistoryEffect.Undoable(said(removed), before))
                    relist(debounce = false, withStats = true)
                }
            }
        }
    }

    private fun export(format: String) {
        work.launch {
            val history = store.history.value ?: return@launch
            val indices = ui.value.rows.map { it.index }
            if (indices.isEmpty()) {
                emit(HistoryEffect.Toast(UiText.Res(R.string.history_nothing_to_export)))
                return@launch
            }
            val request =
                HistoryExportRequest(
                    history,
                    format,
                    tzOffsetS(),
                    today(),
                    indices.takeIf {
                        it.size <
                            history.records.size
                    },
                )
            val answer = (ops.historyExport(request) as? Outcome.Ok)?.value ?: return@launch
            val mime = if (format == FORMAT_GPX) MIME_GPX else MIME_CSV
            emit(HistoryEffect.Export(answer.fileName, mime, answer.text))
        }
    }

    private fun relist(
        debounce: Boolean,
        withStats: Boolean = false,
    ) {
        listing.launch {
            if (debounce) delay(QUERY_DEBOUNCE_MS)
            val history = store.history.value ?: store.reload()
            val now = ui.value
            val list =
                (
                    ops.historyList(
                        HistoryListRequest(history, nowMs(), now.query.takeIf { it.isNotBlank() }, now.filter.wire),
                    ) as? Outcome.Ok
                )?.value
            var stats = now.stats
            var prune = now.prune
            if (withStats || now.loading) {
                stats = (ops.stats(StatsRequest(history, nowMs())) as? Outcome.Ok)?.value
                prune =
                    (ops.historyPrunePlan(PrunePlanRequest(history, nowMs())) as? Outcome.Ok)
                        ?.value
                        ?.choices
                        ?.filter { it.enabled && it.count > 0 }
                        .orEmpty()
                        .toImmutableList()
            }
            publish {
                ui.update {
                    it.copy(
                        loading = false,
                        total = history.records.size,
                        rows = list?.rows.orEmpty().toImmutableList(),
                        stats = stats,
                        prune = prune,
                    )
                }
            }
        }
    }

    companion object {
        const val FORMAT_CSV = "csv"
        const val FORMAT_GPX = "gpx"
        const val MIME_CSV = "text/csv"
        const val MIME_GPX = "application/gpx+xml"
        const val QUERY_DEBOUNCE_MS = 150L
    }
}
