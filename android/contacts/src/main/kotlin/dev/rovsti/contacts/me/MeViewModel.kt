package dev.rovsti.contacts.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.list.MyCardItem
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.toPerson
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * My card (C38) and the emergency list (C43) for the Contacts list (E4.14). [state] is the header row of the list and
 * the emergency ids (`contacts.me_state` over the book and the preferences); [onAction] sets or clears My Card and
 * adds or removes an emergency contact, and every answer is Rust's own sentence as a toast. [choices] feeds the chooser
 * of "Set My Card".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeViewModel(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val ops: ContactsOps,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val me = MeOps(store, ops)
    private val outbox = Channel<MeEffect>(Channel.UNLIMITED)

    val state: StateFlow<MeUiState> =
        combine(book.book, store.prefs, store.emergency) { b, p, e -> Triple(b, p, e) }
            .conflate()
            .mapLatest { (b, _, _) ->
                val ready = b as? Book.Ready ?: return@mapLatest MeUiState()
                val answer = me.state(ready.contacts) ?: return@mapLatest MeUiState()
                MeUiState(
                    myCard =
                        MyCardItem(
                            // A stored key whose contact is gone reads as "no card": the row offers to set one again.
                            contactId = answer.myCardContact,
                            title = answer.myCard.title,
                            detail = answer.myCard.detail,
                        ),
                    emergencyIds = answer.emergency.map { it.contactId }.toImmutableSet(),
                    max = answer.max,
                )
            }.flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MeUiState())

    val effects: Flow<MeEffect> = outbox.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: MeAction) {
        viewModelScope.launch {
            val ready = book.book.value as? Book.Ready ?: return@launch
            val contact = { id: Long -> ready.contacts.firstOrNull { it.id == id } }
            val said =
                when (action) {
                    is MeAction.SetMyCard -> contact(action.id)?.let { me.setMyCard(it, true, ready.contacts) }
                    is MeAction.ClearMyCard -> contact(action.id)?.let { me.setMyCard(it, false, ready.contacts) }
                    is MeAction.ToggleEmergency -> contact(action.id)?.let { me.toggleEmergency(it) }
                }
            said?.let { outbox.trySend(MeEffect.Toast(it.notice)) }
        }
    }

    /** Everybody, in the list's order, for the "Set My Card" chooser. */
    suspend fun choices(): ImmutableList<PersonItem> {
        val ready = book.book.value as? Book.Ready ?: return persistentListOf()
        return rowsOf(ready.contacts).toImmutableList()
    }

    private suspend fun rowsOf(contacts: List<WireContact>): List<PersonItem> {
        val prefs = store.prefs.value
        val request =
            ViewRequest(
                contacts = contacts,
                nameFormat = prefs.nameFormat,
                sortBy = prefs.sortBy,
                favoritesOrder = emptyList(),
                widthDp = WIDTH_DP,
            )
        return (ops.view(request) as? Outcome.Ok)
            ?.value
            ?.rows
            .orEmpty()
            .map { it.toPerson() }
    }

    private companion object {
        const val WIDTH_DP = 411.0
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
