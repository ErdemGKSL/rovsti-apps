package dev.rovsti.contacts.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.RecentListsRequest
import dev.rovsti.core.model.contacts.RecentRow
import dev.rovsti.core.ops.ContactsOps
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The Recently Added / Edited page (E4.14, C15/C45). Both lists, their "when" texts, the segment labels and the empty
 * states are answers of `contacts.recent_lists` over the book and the stored marks (which [dev.rovsti.contacts.data.RecentTracker]
 * keeps up to date with `contacts.recent_note_pull`). This class only keeps which segment is shown and maps the rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentListsViewModel(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val ops: ContactsOps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val selected = MutableStateFlow(RecentUiState.ADDED)
    private val outbox = Channel<RecentEffect>(Channel.UNLIMITED)

    val state: StateFlow<RecentUiState> =
        combine(book.book, store.prefs, selected) { b, p, s -> Triple(b, p, s) }
            .conflate()
            .mapLatest { (b, p, s) -> build(b, p.added, p.viewed, s) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecentUiState())

    val effects: Flow<RecentEffect> = outbox.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: RecentAction) {
        when (action) {
            is RecentAction.SelectTab -> selected.value = action.id
            is RecentAction.Open -> outbox.trySend(RecentEffect.OpenCard(action.id))
        }
    }

    private suspend fun build(
        b: Book,
        marks: List<dev.rovsti.core.model.contacts.AddedMark>,
        viewed: List<String>,
        tab: String,
    ): RecentUiState {
        val ready =
            when (b) {
                Book.Loading -> return RecentUiState(RecentPhase.Loading, selected = tab)
                Book.NoPermission -> return RecentUiState(RecentPhase.NoPermission, selected = tab)
                is Book.Ready -> b
            }
        val now = clock()
        val request =
            RecentListsRequest(
                contacts = ready.contacts,
                marks = marks,
                viewedKeys = viewed,
                hidden = emptyList(),
                nowMs = now,
                tzOffsetS = TimeZone.getDefault().getOffset(now) / MS_PER_S,
            )
        val answer =
            (ops.recentLists(request) as? Outcome.Ok)?.value ?: return RecentUiState(RecentPhase.Ready, selected = tab)
        val photos = ready.contacts.associate { it.id to it.photoUri }

        fun rows(list: List<RecentRow>) =
            list.map { PersonItem(it.id, it.name, it.whenText, photos[it.id]) }.toImmutableList()
        return RecentUiState(
            phase = RecentPhase.Ready,
            tabs = answer.tabs.map { RecentTabUi(it.id, it.label, it.emptyTitle, it.emptyBody) }.toImmutableList(),
            selected = tab,
            added = rows(answer.added),
            edited = rows(answer.edited),
            windowDays = answer.windowDays,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MS_PER_S = 1_000
    }
}
