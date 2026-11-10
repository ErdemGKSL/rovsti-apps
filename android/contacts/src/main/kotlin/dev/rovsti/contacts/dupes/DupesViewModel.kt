package dev.rovsti.contacts.dupes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.intake.draftSecondary
import dev.rovsti.contacts.intake.draftSubtitle
import dev.rovsti.contacts.intake.draftTitle
import dev.rovsti.contacts.intake.previewSectionsOf
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.contacts.trash.MoveResult
import dev.rovsti.contacts.trash.RestoreResult
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftsRequest
import dev.rovsti.core.model.contacts.DupGroup
import dev.rovsti.core.model.contacts.MergeRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * The duplicates page (E4.11, C8). The groups come from `contacts.dupes` and are computed ONCE per address book
 * ([worker] is `Dispatchers.Default`, never the main thread); the merged card of a group is `contacts.merge_plan`.
 * Merging saves the merged card over the keeper FIRST, then moves every other contact to the Trash (`trash_push` through
 * [ContactTrash.delete], which keeps a copy before it deletes), so nothing is lost between the two steps. A merge, of
 * one group or of all, ends in ONE undo pill; Undo writes every keeper back as it was and takes the copies out of the
 * Trash again.
 */
class DupesViewModel(
    private val book: BookRepository,
    private val contacts: ContactsRepository,
    private val ops: ContactsOps,
    private val trash: ContactTrash,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    /** What it takes to take one merge back: the keeper as it was and the Trash keys of the others. */
    private class Undoable(
        val keeper: ContactDraft,
        val trashKeys: List<Long>,
    )

    private val outbox = Channel<DupesEffect>(Channel.UNLIMITED)

    /** One-shot results: questions, toasts and the undo pill. */
    val effects: Flow<DupesEffect> = outbox.receiveAsFlow()

    private val ui = MutableStateFlow(DupesUiState())
    val state: StateFlow<DupesUiState> = ui.asStateFlow()

    @Volatile private var groups: List<DupGroup> = emptyList()
    private val tokens = AtomicLong()
    private val undos = HashMap<Long, List<Undoable>>()

    init {
        scope.launch {
            book.book.collectLatest { next ->
                when (next) {
                    Book.Loading -> {
                        Unit
                    }

                    Book.NoPermission -> {
                        publish(emptyList(), UiText.Res(R.string.contacts_no_duplicates_found), emptyMap())
                    }

                    is Book.Ready -> {
                        val answer =
                            withContext(worker) {
                                (ops.dupes(DraftsRequest(next.drafts)) as? Outcome.Ok)?.value
                            }
                        val photos = next.contacts.associate { it.id to it.photoUri }
                        publish(
                            answer?.groups.orEmpty(),
                            answer?.summary?.let { UiText.Literal(it) }
                                ?: UiText.Res(R.string.contacts_no_duplicates_found),
                            photos,
                        )
                    }
                }
            }
        }
    }

    fun onAction(action: DupesAction) {
        when (action) {
            is DupesAction.Open -> open(action.key)
            DupesAction.CloseReview -> ui.update { it.copy(review = null) }
            DupesAction.MergeOpen -> mergeOpen()
            DupesAction.AskMergeAll -> askMergeAll()
            DupesAction.ConfirmMergeAll -> merge(groups)
            is DupesAction.Undo -> undo(action.token)
        }
    }

    private fun publish(
        found: List<DupGroup>,
        summary: UiText,
        photos: Map<Long, String?>,
    ) {
        groups = found
        val ready = book.book.value as? Book.Ready
        val byId = ready?.drafts.orEmpty().associateBy { it.id }
        val rows =
            found.mapNotNull { group ->
                val keeper = byId[group.ids.first()] ?: return@mapNotNull null
                val more = group.ids.size - 1
                DupeRow(
                    key = group.ids.first(),
                    name = draftTitle(keeper),
                    secondary = UiText.Res(R.string.contacts_dupe_secondary, listOf(more, UiText.Literal(group.label))),
                    photoUri = photos[group.ids.first()],
                )
            }
        ui.update { old ->
            // A review of a group that is gone (merged, or the book changed) closes with it.
            val review = old.review?.takeIf { r -> rows.any { it.key == r.key } }
            old.copy(
                phase = DupesPhase.Ready,
                summary = summary,
                rows = rows.toImmutableList(),
                review = review,
            )
        }
    }

    private fun draftsOf(group: DupGroup): List<ContactDraft>? {
        val byId = (book.book.value as? Book.Ready)?.drafts.orEmpty().associateBy { it.id }
        val all = group.ids.map { byId[it] ?: return null }
        return all.takeIf { it.size > 1 }
    }

    private fun open(key: Long) {
        scope.launch {
            val group = groups.firstOrNull { it.ids.first() == key } ?: return@launch
            val drafts = draftsOf(group) ?: return@launch
            val merged =
                withContext(worker) {
                    (ops.mergePlan(MergeRequest(drafts.first(), drafts.drop(1))) as? Outcome.Ok)?.value
                } ?: return@launch
            val photo = (book.book.value as? Book.Ready)?.contacts?.firstOrNull { it.id == key }?.photoUri
            val review =
                MergeReview(
                    key = key,
                    name = draftTitle(merged.draft),
                    subtitle = draftSubtitle(merged.draft) ?: draftSecondary(merged.draft),
                    photoUri = photo,
                    sources =
                        UiText.Res(
                            R.string.contacts_merge_sources,
                            listOf(UiText.Literal(drafts.joinToString(", ") { draftTitle(it) })),
                        ),
                    sections = previewSectionsOf(merged.draft),
                    mergeLabel = UiText.Res(R.string.contacts_merge_n_contacts, listOf(drafts.size)),
                )
            ui.update { it.copy(review = review) }
        }
    }

    private fun mergeOpen() {
        val key = ui.value.review?.key ?: return
        val group = groups.firstOrNull { it.ids.first() == key } ?: return
        ui.update { it.copy(review = null) }
        merge(listOf(group))
    }

    private fun askMergeAll() {
        val n = groups.size
        if (n == 0) return
        val body: UiText = UiText.Res(R.string.contacts_merge_body)
        val title: UiText =
            if (n ==
                1
            ) {
                UiText.Res(R.string.contacts_merge_one_title)
            } else {
                UiText.Plural(R.plurals.contacts_merge_all_title, n)
            }
        outbox.trySend(
            DupesEffect.Ask(title, body, UiText.Res(R.string.contacts_merge_all), DupesAction.ConfirmMergeAll),
        )
    }

    private fun merge(selected: List<DupGroup>) {
        if (selected.isEmpty() || ui.value.busy) return
        ui.update { it.copy(busy = true) }
        scope.launch {
            try {
                val undoable = ArrayList<Undoable>()
                var people = 0
                var notice: String? = null
                for (group in selected) {
                    val drafts = draftsOf(group) ?: continue
                    val keeper = drafts.first()
                    val plan =
                        withContext(worker) {
                            (ops.mergePlan(MergeRequest(keeper, drafts.drop(1))) as? Outcome.Ok)?.value
                        } ?: continue
                    // The merged card goes in first: whatever happens next, the keeper holds every field.
                    if (contacts.save(plan.draft) !is ContactResult.Ok) continue
                    val keys = ArrayList<Long>()
                    for (other in drafts.drop(1)) {
                        val id = other.id ?: continue
                        when (val moved = trash.delete(id)) {
                            is MoveResult.Done -> keys += moved.key
                            MoveResult.Gone, MoveResult.NoCopy, MoveResult.Refused -> Unit
                        }
                    }
                    undoable += Undoable(keeper, keys)
                    people += 1 + keys.size
                    notice = plan.notice
                }
                if (undoable.isEmpty()) {
                    outbox.trySend(DupesEffect.Toast(UiText.Res(R.string.contacts_cant_merge)))
                } else {
                    val token = tokens.incrementAndGet()
                    undos[token] = undoable
                    // One group says what the core says; several say the same in the same words.
                    val message =
                        if (undoable.size == 1 &&
                            notice != null
                        ) {
                            UiText.Literal(notice)
                        } else {
                            UiText.Res(R.string.contacts_merged_n_contacts, listOf(people))
                        }
                    outbox.trySend(DupesEffect.Undo(message, token))
                }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun undo(token: Long) {
        val merges = undos.remove(token) ?: return
        scope.launch {
            var failed = false
            for (merge in merges) {
                if (contacts.save(merge.keeper) !is ContactResult.Ok) failed = true
            }
            val keys = merges.flatMap { it.trashKeys }
            val restored = if (keys.isEmpty()) RestoreResult.Done(emptyList(), "") else trash.restore(keys)
            val text =
                when {
                    restored is RestoreResult.Done && !failed -> UiText.Res(R.string.contacts_merge_undone)
                    restored is RestoreResult.Missing -> UiText.Res(R.string.contacts_not_in_trash_many)
                    else -> UiText.Res(R.string.contacts_undo_partial)
                }
            outbox.trySend(DupesEffect.Toast(text))
        }
    }
}
