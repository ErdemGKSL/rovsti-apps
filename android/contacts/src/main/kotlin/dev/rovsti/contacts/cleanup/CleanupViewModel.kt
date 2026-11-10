package dev.rovsti.contacts.cleanup

import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.intake.draftSecondary
import dev.rovsti.contacts.intake.draftTitle
import dev.rovsti.contacts.list.ContactsListEffect
import dev.rovsti.contacts.list.SelectController
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.CleanupId
import dev.rovsti.core.model.contacts.CleanupRequest
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.SelectAction
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.controls.ChipModel
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.files.ClipboardRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Clean up page (E4.11, C24, C54). The filters, their counts, the ids under one filter and the words for an empty
 * one all come from `contacts.cleanup`; the page only picks the filter. Select mode, the delete question, the Trash
 * copy of each contact and the ONE undo are the Contacts list's own [SelectController] (E4.8), so deleting here and
 * there is the same path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CleanupViewModel(
    private val book: BookRepository,
    contacts: ContactsRepository,
    private val ops: ContactsOps,
    trash: ContactTrash,
    clipboard: ClipboardRepository,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val outbox = Channel<CleanupEffect>(Channel.UNLIMITED)

    /** One-shot results: the delete question, toasts and the undo pill. */
    val effects: Flow<CleanupEffect> = outbox.receiveAsFlow()

    private val select =
        SelectController(book, contacts, ops, trash, clipboard, clock) { effect ->
            when (effect) {
                is ContactsListEffect.ConfirmDeleteSelected -> outbox.trySend(CleanupEffect.Ask(effect.confirm))
                is ContactsListEffect.Deleted -> outbox.trySend(CleanupEffect.Deleted(effect.notice, effect.keys))
                is ContactsListEffect.Toast -> outbox.trySend(CleanupEffect.Toast(effect.text))
                else -> Unit
            }
        }

    private val filter = MutableStateFlow(CleanupId.NoNumber)

    /** The answer of the core for one book and one filter, with the rows already built. */
    private class Computed(
        val filter: CleanupId,
        val chips: List<ChipModel>,
        val emptyText: String,
        val rows: List<CleanupRow>,
    )

    private val computed: Flow<Computed?> =
        combine(book.book, filter) { b, f -> b to f }.mapLatest { (b, f) ->
            val ready =
                b as? Book.Ready
                    ?: return@mapLatest if (b == Book.NoPermission) Computed(f, emptyList(), "", emptyList()) else null
            withContext(worker) {
                val photoIds = ready.contacts.filter { it.photoUri != null }.map { it.id }
                val answer =
                    (ops.cleanup(CleanupRequest(ready.drafts, photoIds, f)) as? Outcome.Ok)?.value
                        ?: return@withContext null
                val byId = ready.drafts.associateBy { it.id }
                val photos = ready.contacts.associate { it.id to it.photoUri }
                Computed(
                    filter = f,
                    chips = answer.filters.map { ChipModel(it.id.name, it.label, it.count) },
                    emptyText =
                        answer.filters
                            .firstOrNull { it.id == f }
                            ?.emptyText
                            .orEmpty(),
                    rows = answer.ids.mapNotNull { id -> byId[id]?.let { rowOf(it, photos[id]) } },
                )
            }
        }

    val state: StateFlow<CleanupUiState> =
        combine(computed, select.selection, select.hidden) { c, picked, hidden ->
            Snapshot(c, picked, hidden)
        }.mapLatest { s ->
            val c = s.computed ?: return@mapLatest CleanupUiState()
            val rows = c.rows.filter { it.id !in s.hidden }
            // The chip of the filter on counts what is left; the others follow when the address book has caught up.
            val chips =
                c.chips.map { chip -> if (chip.id == c.filter.name) chip.copy(count = rows.size) else chip }
            CleanupUiState(
                phase = CleanupPhase.Ready,
                chips = chips.toImmutableList(),
                filter = c.filter.name,
                emptyText = c.emptyText,
                rows = rows.toImmutableList(),
                select = s.picked?.let { selectOf(it - s.hidden, rows) },
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CleanupUiState())

    private class Snapshot(
        val computed: Computed?,
        val picked: Set<Long>?,
        val hidden: Set<Long>,
    )

    private suspend fun selectOf(
        picked: Set<Long>,
        rows: List<CleanupRow>,
    ): CleanupSelect {
        val bar = select.bar(picked, rows.map { it.id }, hasGroups = false)
        return CleanupSelect(
            selected = picked.toImmutableSet(),
            title = bar?.title ?: UiText.Res(R.string.contacts_select_contacts),
            allSelected = bar?.allSelected ?: false,
            canDelete = bar?.actions?.contains(SelectAction.Delete) == true,
        )
    }

    fun onAction(action: CleanupAction) {
        when (action) {
            is CleanupAction.SelectFilter -> {
                val next = CleanupId.entries.firstOrNull { it.name == action.id } ?: return
                if (next != filter.value) {
                    // The picks belong to the filter they were made under.
                    if (select.selection.value != null) select.enter(null)
                    filter.value = next
                }
            }

            is CleanupAction.EnterSelect -> {
                select.enter(action.first)
            }

            CleanupAction.ExitSelect -> {
                select.exit()
            }

            is CleanupAction.Toggle -> {
                select.toggle(action.id)
            }

            CleanupAction.ToggleAll -> {
                select.toggleAll(state.value.rows.map { it.id })
            }

            CleanupAction.AskDelete -> {
                scope.launch { select.askDelete() }
            }

            CleanupAction.ConfirmDelete -> {
                scope.launch { select.delete() }
            }

            is CleanupAction.Undo -> {
                scope.launch { select.undo(action.keys) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun rowOf(
    draft: ContactDraft,
    photoUri: String?,
) = CleanupRow(draft.id ?: 0, draftTitle(draft), draftSecondary(draft), photoUri)
