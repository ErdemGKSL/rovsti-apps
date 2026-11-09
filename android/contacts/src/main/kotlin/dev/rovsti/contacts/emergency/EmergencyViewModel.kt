package dev.rovsti.contacts.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.me.MeOps
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.toPerson
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.CardRequest
import dev.rovsti.core.model.contacts.FavoritesMoveRequest
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
import kotlinx.collections.immutable.persistentListOf
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

/**
 * The Emergency Contacts page (E4.14, C43). The list is the preference `emergency` (lookup keys in the user's order);
 * `contacts.me_state` resolves the keys against the book, drops deleted people and answers the share text and the limit,
 * `contacts.card` formats each number. A drop or "Move Earlier / Later" goes through `contacts.favorites_move` (the same
 * rule as the favorites) and the answer is written back to the preferences, so the order survives a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyViewModel(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val ops: ContactsOps,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val me = MeOps(store, ops)
    private val editing = MutableStateFlow(false)
    private val outbox = Channel<EmergencyEffect>(Channel.UNLIMITED)

    /** The numbers behind the rows, by contact id (the Call button dials these). */
    @Volatile private var dials: Map<Long, String> = emptyMap()

    val state: StateFlow<EmergencyUiState> =
        combine(book.book, store.prefs, store.emergency, editing) { b, _, _, e -> b to e }
            .conflate()
            .mapLatest { (b, e) -> build(b, e) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EmergencyUiState())

    val effects: Flow<EmergencyEffect> = outbox.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: EmergencyAction) {
        when (action) {
            is EmergencyAction.Open -> {
                outbox.trySend(EmergencyEffect.OpenCard(action.id))
            }

            is EmergencyAction.Call -> {
                call(action.id)
            }

            EmergencyAction.ToggleEdit -> {
                editing.value = !editing.value
            }

            is EmergencyAction.Move -> {
                viewModelScope.launch { move(FavoritesMoveRequest(shown(), from = action.from, to = action.to)) }
            }

            is EmergencyAction.MoveStep -> {
                viewModelScope.launch { move(FavoritesMoveRequest(shown(), index = action.index, up = action.up)) }
            }

            is EmergencyAction.Remove -> {
                viewModelScope.launch { toggle(action.id) }
            }

            is EmergencyAction.AddAll -> {
                viewModelScope.launch { addAll(action.ids) }
            }

            EmergencyAction.Share -> {
                if (state.value.shareText.isNotEmpty() && state.value.people.isNotEmpty()) {
                    outbox.trySend(EmergencyEffect.Share(state.value.shareText))
                }
            }
        }
    }

    private suspend fun build(
        b: Book,
        editingNow: Boolean,
    ): EmergencyUiState {
        val ready =
            when (b) {
                Book.Loading -> return EmergencyUiState(EmergencyPhase.Loading)
                Book.NoPermission -> return EmergencyUiState(EmergencyPhase.NoPermission)
                is Book.Ready -> b
            }
        val answer = me.state(ready.contacts) ?: return EmergencyUiState(EmergencyPhase.Ready)
        val byId = ready.contacts.associateBy { it.id }
        val nameFormat = store.prefs.value.nameFormat
        val dialed = HashMap<Long, String>()
        val people =
            answer.emergency
                .map { entry ->
                    val contact = byId[entry.contactId]
                    // The display format of the card (`contacts.card`), the same for every number.
                    val shown =
                        contact
                            ?.let { (ops.card(CardRequest(it, nameFormat)) as? Outcome.Ok)?.value }
                            ?.numbers
                            ?.firstOrNull { it.raw == entry.number }
                            ?.display
                    dialed[entry.contactId] = entry.number
                    PersonItem(
                        id = entry.contactId,
                        name = entry.name,
                        secondary = (shown ?: entry.number).ifBlank { null },
                        photoUri = contact?.photoUri,
                    )
                }.toImmutableList()
        dials = dialed
        val onList = people.mapTo(HashSet()) { it.id }
        val candidates = ready.contacts.filter { it.id !in onList && it.numbers.any { n -> n.raw.isNotBlank() } }
        return EmergencyUiState(
            phase = EmergencyPhase.Ready,
            people = people,
            max = answer.max,
            editing = editingNow && people.size >= 2,
            shareText = answer.emergencyText,
            pickables = rowsOf(candidates),
        )
    }

    private suspend fun rowsOf(contacts: List<WireContact>): kotlinx.collections.immutable.ImmutableList<PersonItem> {
        if (contacts.isEmpty()) return persistentListOf()
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
            .toImmutableList()
    }

    // ---- actions

    private fun call(id: Long) {
        val number = dials[id]?.takeIf { it.isNotBlank() }
        outbox.trySend(
            if (number !=
                null
            ) {
                EmergencyEffect.Dial(number)
            } else {
                EmergencyEffect.Toast("This contact has no number.")
            },
        )
    }

    /** The people as the rows show them now, for the move op. */
    private fun shown(): List<WireContact> {
        val ready = book.book.value as? Book.Ready ?: return emptyList()
        val byId = ready.contacts.associateBy { it.id }
        return state.value.people.mapNotNull { byId[it.id] }
    }

    private suspend fun move(request: FavoritesMoveRequest) {
        val keys = (ops.favoritesMove(request) as? Outcome.Ok)?.value?.keys ?: return
        store.update { it.copy(emergency = keys) }
    }

    private suspend fun toggle(id: Long) {
        val ready = book.book.value as? Book.Ready ?: return
        val contact = ready.contacts.firstOrNull { it.id == id } ?: return
        outbox.trySend(EmergencyEffect.Toast(me.toggleEmergency(contact).notice))
    }

    /** Adds the ticked people one by one; stops at the limit and says why, otherwise says what Rust says for one add. */
    private suspend fun addAll(ids: Set<Long>) {
        val ready = book.book.value as? Book.Ready ?: return
        val order = ready.contacts.filter { it.id in ids }
        var said: String? = null
        for (contact in order) {
            val result = me.toggleEmergency(contact)
            said = result.notice
            if (!result.done) break
        }
        said?.let { outbox.trySend(EmergencyEffect.Toast(it)) }
    }

    private companion object {
        const val WIDTH_DP = 411.0
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
