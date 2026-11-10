package dev.rovsti.contacts.trash

import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.toUi
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.TrashEntry
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Trash page (E4.5). Entries, their ages and the retention come from `contacts.trash_list` (which drops what has
 * expired and rewrites the file), the confirmation words from `contacts.trash_confirm`, the restore from
 * `contacts.trash_restore`. A restore inserts the contact again through [ContactTrash]; Delete Now and Empty Trash ask
 * first and only then touch the file.
 */
class TrashViewModel(
    private val trash: ContactTrash,
    private val store: ContactsStore,
    private val scope: CoroutineScope,
) {
    private val outbox = Channel<TrashEffect>(Channel.UNLIMITED)

    /** One-shot results: confirmations and toasts. */
    val effects: Flow<TrashEffect> = outbox.receiveAsFlow()

    private var retention = 30
    private val ui = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = ui.asStateFlow()

    init {
        refresh()
    }

    fun onAction(action: TrashAction) {
        when (action) {
            TrashAction.Refresh -> refresh()
            is TrashAction.Restore -> restore(action.key)
            is TrashAction.AskDelete -> ask(forever = true, keys = listOf(action.key))
            TrashAction.AskEmpty -> ask(forever = false, keys = emptyList())
            is TrashAction.ConfirmDelete -> deleteForever(action.keys)
            TrashAction.ConfirmEmpty -> empty()
        }
    }

    private fun refresh() {
        scope.launch { load() }
    }

    private suspend fun load() {
        val answer = trash.list() ?: return
        retention = answer.retentionDays
        ui.update {
            TrashUiState(
                phase = TrashPhase.Ready,
                rows = answer.entries.map(::rowOf).toImmutableList(),
                retentionDays = answer.retentionDays,
            )
        }
    }

    private fun rowOf(entry: TrashEntry): TrashRow {
        val draft = entry.draft
        val gone = retention.coerceAtLeast(entry.daysLeft) - entry.daysLeft
        val ago: UiText =
            if (entry.leftText == TODAY) {
                UiText.Res(R.string.contacts_deleted_today)
            } else {
                UiText.Plural(R.plurals.contacts_deleted_days_ago, gone)
            }
        val detail: UiText =
            if (entry.leftText ==
                TODAY
            ) {
                ago
            } else {
                UiText.Res(R.string.contacts_trash_detail, listOf(ago, UiText.Literal(entry.leftText)))
            }
        val name: UiText = draft.trashLabel()
        return TrashRow(entry.key, name, detail, entry.key.toString())
    }

    private fun restore(key: Long) {
        scope.launch {
            when (val result = trash.restore(listOf(key))) {
                is RestoreResult.Done -> {
                    outbox.trySend(TrashEffect.Toast(UiText.Literal(result.notice)))
                }

                RestoreResult.Missing -> {
                    outbox.trySend(TrashEffect.Toast(UiText.Res(R.string.contacts_not_in_trash)))
                }

                RestoreResult.Refused -> {
                    outbox.trySend(
                        TrashEffect.Toast(UiText.Res(R.string.contacts_cant_restore_stays)),
                    )
                }
            }
            load()
        }
    }

    private fun ask(
        forever: Boolean,
        keys: List<Long>,
    ) {
        scope.launch {
            val count = if (forever) keys.size else ui.value.rows.size
            if (count == 0) return@launch
            val confirm = (store.trashConfirm(count, forever) as? Outcome.Ok)?.value?.toUi() ?: return@launch
            val then = if (forever) TrashAction.ConfirmDelete(keys) else TrashAction.ConfirmEmpty
            outbox.trySend(
                TrashEffect.Ask(
                    confirm.title,
                    confirm.body,
                    if (forever) {
                        UiText.Res(
                            R.string.contacts_delete_now,
                        )
                    } else {
                        UiText.Res(R.string.contacts_empty_trash)
                    },
                    then,
                ),
            )
        }
    }

    private fun deleteForever(keys: List<Long>) {
        scope.launch {
            trash.deleteForever(keys)
            load()
        }
    }

    private fun empty() {
        scope.launch {
            trash.empty()
            load()
        }
    }

    private companion object {
        /** The Rust core's words for a trash entry deleted today; the row shows [R.string.contacts_deleted_today]. */
        const val TODAY = "Deleted today"
    }
}
