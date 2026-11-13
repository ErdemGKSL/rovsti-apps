package dev.rovsti.phone.favorites

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactRow
import dev.rovsti.core.model.contacts.FavoritesMoveRequest
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.FrequentRequest
import dev.rovsti.core.model.phone.PhonePrefs
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.DialRefusal
import dev.rovsti.phone.data.People
import dev.rovsti.phone.data.PeopleIndex
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.PlaceResult
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.collections.immutable.ImmutableList
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the tab shows while the contacts are not ready. */
enum class FavoritesPhase { Loading, NoPermission, Ready }

/**
 * One person of the Favorites tab. [contactId] is null for a number in the Frequent section that no contact owns;
 * [key] is the list key. [number] is what a tap dials; [label] is the type of that number ("mobile").
 */
@Immutable
data class FavPerson(
    val key: String,
    val contactId: Long?,
    val name: String,
    val label: String?,
    val number: String?,
    val photoUri: String? = null,
) {
    val colorKey: String get() = contactId?.toString() ?: name
}

/** The whole Favorites tab as one value. */
@Immutable
data class FavoritesUi(
    val phase: FavoritesPhase = FavoritesPhase.Loading,
    /** The starred contacts in the user's order. */
    val favorites: ImmutableList<FavPerson> = persistentListOf(),
    /** Edit mode: the rows show a drag handle. Only possible with two favorites or more. */
    val editing: Boolean = false,
    /** Most called, not yet favorites. Empty without the call log, in edit mode, or when the setting is off. */
    val frequent: ImmutableList<FavPerson> = persistentListOf(),
    /** Everybody with a number who is not a favorite yet: the Add Favorite picker. */
    val pickables: ImmutableList<FavPerson> = persistentListOf(),
) {
    /** Edit needs two favorites to reorder. */
    val canEdit: Boolean get() = favorites.size >= 2

    /** Nothing at all to show: the empty state with "Add Favorite". */
    val isEmpty: Boolean get() = favorites.isEmpty() && frequent.isEmpty()
}

/** What the screen asks of the model. */
sealed interface FavoritesAction {
    data class Call(
        val number: String,
    ) : FavoritesAction

    data class CallOn(
        val number: String,
        val simKey: String,
    ) : FavoritesAction

    data object ToggleEdit : FavoritesAction

    /** A drop in edit mode: the row that was at [from] now sits at [to]. */
    data class Move(
        val from: Int,
        val to: Int,
    ) : FavoritesAction

    /** "Move Earlier" / "Move Later" of the row menu. */
    data class MoveStep(
        val index: Int,
        val up: Boolean,
    ) : FavoritesAction

    data class Remove(
        val id: Long,
    ) : FavoritesAction

    data class Add(
        val id: Long,
    ) : FavoritesAction
}

/** One-shot results. */
sealed interface FavoritesEffect {
    data class ChooseSim(
        val number: String,
        val choices: ImmutableList<SimAccount>,
    ) : FavoritesEffect

    data object Added : FavoritesEffect

    data object Removed : FavoritesEffect

    /** The call, the star or the order could not be changed. */
    data object Failed : FavoritesEffect

    /** The core refused the number; the screen words [refusal] from resources (F30). */
    data class Core(
        val refusal: DialRefusal,
    ) : FavoritesEffect
}

/**
 * The Favorites tab (E5.5). The favorites are the starred contacts with a number, in the order of
 * `prefs.favorites_order` (lookup keys; `contacts.view` puts the ones it does not list after them). A drop in edit mode
 * and the row menu's "Move Earlier / Later" go through `contacts.favorites_move` and the answer is saved to the
 * preferences ([PhoneStore], written 300 ms after the last change). Frequently called people come from
 * `call_log.frequent` over the call log, only while READ_CALL_LOG is granted and `show_frequent` is on; a star adds one
 * to the favorites. A tap on a row places the call (the SIM chooser is an effect when the phone asks every time).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModel(
    private val people: PeopleIndex,
    private val store: PhoneStore,
    private val contactsOps: ContactsOps,
    private val phoneOps: PhoneOps,
    private val contacts: ContactsRepository,
    private val callLog: CallLogRepository,
    callLogPermission: Flow<PermissionState>,
    private val placer: CallPlacer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val widthDp: Double = DEFAULT_WIDTH_DP,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val editing = MutableStateFlow(false)
    private val effects = Channel<FavoritesEffect>(Channel.BUFFERED)

    private class Inputs(
        val people: People,
        val prefs: PhonePrefs,
        val calls: List<CallLogEntry>?,
        val editing: Boolean,
    )

    /** Null while the call log may not be read. */
    private val calls: Flow<List<CallLogEntry>?> =
        callLogPermission
            .map { it == PermissionState.Granted }
            .distinctUntilChanged()
            .flatMapLatest { granted -> if (granted) callLog.entries() else flowOf(null) }

    val state: StateFlow<FavoritesUi> =
        combine(people.people, store.prefs, calls, editing) { p, prefs, c, e -> Inputs(p, prefs, c, e) }
            .conflate()
            .mapLatest { build(it) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FavoritesUi())

    /** Toasts and the SIM chooser, each delivered once. */
    val effect: Flow<FavoritesEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: FavoritesAction) {
        when (action) {
            is FavoritesAction.Call -> {
                viewModelScope.launch { place(action.number, null) }
            }

            is FavoritesAction.CallOn -> {
                viewModelScope.launch { place(action.number, action.simKey) }
            }

            FavoritesAction.ToggleEdit -> {
                editing.value = !editing.value
            }

            is FavoritesAction.Move -> {
                viewModelScope.launch { move(FavoritesMoveRequest(shown(), from = action.from, to = action.to)) }
            }

            is FavoritesAction.MoveStep -> {
                viewModelScope.launch { move(FavoritesMoveRequest(shown(), index = action.index, up = action.up)) }
            }

            is FavoritesAction.Remove -> {
                viewModelScope.launch { star(action.id, on = false) }
            }

            is FavoritesAction.Add -> {
                viewModelScope.launch { star(action.id, on = true) }
            }
        }
    }

    private suspend fun build(i: Inputs): FavoritesUi {
        val ready =
            when (val p = i.people) {
                People.Loading -> return FavoritesUi(FavoritesPhase.Loading)
                People.NoPermission -> return FavoritesUi(FavoritesPhase.NoPermission)
                is People.Ready -> p
            }
        val settings = i.prefs.settings
        val answer =
            (
                contactsOps.view(
                    ViewRequest(
                        contacts = ready.contacts,
                        nameFormat = settings.nameFormat,
                        sortBy = settings.sortBy,
                        favoritesOrder = i.prefs.favoritesOrder,
                        widthDp = widthDp,
                    ),
                ) as? Outcome.Ok
            )?.value ?: return FavoritesUi(FavoritesPhase.Ready, editing = false)
        val rows = answer.rows.associateBy { it.id }
        val favorites = answer.favorites.mapNotNull { rows[it]?.toPerson() }.toImmutableList()
        val inFavorites = favorites.mapNotNullTo(HashSet()) { it.contactId }
        val editingNow = i.editing && favorites.size >= 2
        val frequent =
            if (i.calls == null || editingNow || !settings.showFrequent) {
                persistentListOf()
            } else {
                frequentOf(i.calls, rows, inFavorites)
            }
        val pickables =
            answer.rows
                .filter { it.id !in inFavorites && it.numbers.isNotEmpty() }
                .map { it.toPerson() }
                .toImmutableList()
        return FavoritesUi(FavoritesPhase.Ready, favorites, editingNow, frequent, pickables)
    }

    private suspend fun frequentOf(
        entries: List<CallLogEntry>,
        rows: Map<Long, ContactRow>,
        starred: Set<Long>,
    ): ImmutableList<FavPerson> {
        val out =
            (
                phoneOps.frequent(
                    FrequentRequest(known(entries), clock(), limit = FREQUENT_SHOWN, starred = starred.toList()),
                ) as? Outcome.Ok
            )?.value?.people.orEmpty()
        return out
            .map { f ->
                val row = f.contactId?.let { rows[it] }
                row?.toPerson(f.numberRaw)
                    ?: FavPerson(
                        key = "freq-${f.normalized}",
                        contactId = f.contactId,
                        name = f.name?.takeIf { it.isNotBlank() } ?: f.numberRaw,
                        label = null,
                        number = f.numberRaw,
                    )
            }.toImmutableList()
    }

    /** Entries the provider has no contact for are matched by number, so a favorite never shows up as frequent too. */
    private suspend fun known(entries: List<CallLogEntry>): List<CallLogEntry> {
        val byNumber = HashMap<String, Long?>()
        return entries.map { e ->
            if (e.contactId != null) {
                e
            } else {
                val id = byNumber.getOrPut(e.numberRaw) { people.matchNumber(e.numberRaw)?.id }
                if (id != null) e.copy(contactId = id) else e
            }
        }
    }

    /** The favorites as the rows show them now, for the move ops. */
    private fun shown(): List<WireContact> {
        val ready = people.people.value as? People.Ready ?: return emptyList()
        val byId = ready.contacts.associateBy { it.id }
        return state.value.favorites.mapNotNull { f -> f.contactId?.let { byId[it] } }
    }

    private suspend fun move(request: FavoritesMoveRequest) {
        val keys = (contactsOps.favoritesMove(request) as? Outcome.Ok)?.value?.keys ?: return
        store.updatePrefs { it.copy(favoritesOrder = keys) }
    }

    private suspend fun star(
        id: Long,
        on: Boolean,
    ) {
        val result = contacts.setStarred(id, on)
        effects.trySend(
            when {
                result is ContactResult.Failed -> FavoritesEffect.Failed
                on -> FavoritesEffect.Added
                else -> FavoritesEffect.Removed
            },
        )
    }

    private suspend fun place(
        number: String,
        simKey: String?,
    ) {
        when (val result = placer.place(number, simKey)) {
            is PlaceResult.Placed, is PlaceResult.HandedToDialer -> Unit
            is PlaceResult.NeedsSim -> effects.trySend(FavoritesEffect.ChooseSim(result.number, result.choices))
            is PlaceResult.Invalid -> effects.trySend(FavoritesEffect.Core(result.refusal))
            is PlaceResult.Failed -> effects.trySend(FavoritesEffect.Failed)
        }
    }

    companion object {
        const val DEFAULT_WIDTH_DP = 411.0

        /** The Frequent section lists this many people. */
        const val FREQUENT_SHOWN = 5
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** A `contacts.view` row as a person of the tab; [number] overrides the number a tap dials. */
internal fun ContactRow.toPerson(number: String? = null): FavPerson {
    val first = numbers.firstOrNull { it.default } ?: numbers.firstOrNull()
    return FavPerson(
        key = "fav-$id",
        contactId = id,
        name = name,
        label = first?.label?.takeIf { it.isNotBlank() },
        number = number ?: first?.raw,
        photoUri = photoUri,
    )
}
