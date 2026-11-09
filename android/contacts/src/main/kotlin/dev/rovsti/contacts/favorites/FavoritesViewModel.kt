package dev.rovsti.contacts.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.toPerson
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.ContactsPrefs
import dev.rovsti.core.model.contacts.DatesRequest
import dev.rovsti.core.model.contacts.EventLineRequest
import dev.rovsti.core.model.contacts.FavoritesMoveRequest
import dev.rovsti.core.model.contacts.FrequentContactsRequest
import dev.rovsti.core.model.contacts.IcsRequest
import dev.rovsti.core.model.contacts.LocalYmdRequest
import dev.rovsti.core.model.contacts.UpcomingEvent
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.StatCall
import dev.rovsti.core.model.phone.StatDir
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
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
import java.util.TimeZone

/**
 * The Favorites tab (E4.6, C26/C42/C9/C50). The favorites come from `contacts.view` in the order of the saved
 * `favorites_order` (lookup keys); a drop in edit mode and the row menu's "Move earlier / later" go through
 * `contacts.favorites_move` (drag mode `from`/`to`, button mode `index`/`up`) and the answer is saved to the
 * preferences. Frequently contacted people come from `contacts.frequent` over the call log and exist only while
 * READ_CALL_LOG is granted; a star adds one to the favorites. Upcoming birthdays and anniversaries come from
 * `contacts.dates`; "Wish" opens the SMS composer with the greeting of `contacts.event_line`, "Add to Calendar" asks
 * `contacts.ics` for the file and hands it to the page, which asks where to save it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModel(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val ops: ContactsOps,
    private val contacts: ContactsRepository,
    private val callLog: CallLogRepository,
    private val callLogPermission: Flow<PermissionState>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val widthDp: Double = DEFAULT_WIDTH_DP,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val editing = MutableStateFlow(false)
    private val outbox = Channel<FavoritesEffect>(Channel.UNLIMITED)
    private val pageOutbox = Channel<FavoritesEffect>(Channel.UNLIMITED)

    private class Inputs(
        val book: Book,
        val prefs: ContactsPrefs,
        val order: List<String>,
        val calls: List<CallLogEntry>?,
        val editing: Boolean,
    )

    /** Null while the call log may not be read; the rows otherwise (newest first). */
    private val calls: Flow<List<CallLogEntry>?> =
        callLogPermission
            .map { it == PermissionState.Granted }
            .distinctUntilChanged()
            .flatMapLatest { granted -> if (granted) callLog.entries() else flowOf(null) }

    val state: StateFlow<FavoritesUiState> =
        combine(
            combine(book.book, store.prefs, store.favoritesOrder) { b, p, o -> Triple(b, p, o) },
            calls,
            editing,
        ) { (b, p, o), c, e -> Inputs(b, p, o, c, e) }
            .conflate()
            .mapLatest { build(it) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FavoritesUiState())

    /** Navigation, toasts, the composer and the calendar file, each delivered once. */
    val effects: Flow<FavoritesEffect> = outbox.receiveAsFlow()

    /**
     * What the See All page asked for (a Wish, the calendar file). A second stream because the Favorites tab stays
     * composed under the page: one shared stream would let either of them take the other's answer.
     */
    val pageEffects: Flow<FavoritesEffect> = pageOutbox.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: FavoritesAction) {
        when (action) {
            is FavoritesAction.Open -> {
                outbox.trySend(FavoritesEffect.OpenCard(action.id))
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
                viewModelScope.launch { star(action.id, on = false, said = "Removed from Favorites") }
            }

            is FavoritesAction.Star -> {
                viewModelScope.launch { star(action.id, on = true, said = "Added to Favorites") }
            }

            is FavoritesAction.AddAll -> {
                viewModelScope.launch { addAll(action.ids) }
            }

            is FavoritesAction.Wish -> {
                viewModelScope.launch { wish(action) }
            }

            FavoritesAction.SaveCalendar -> {
                viewModelScope.launch { calendar() }
            }
        }
    }

    // ---- building the tab

    private suspend fun build(i: Inputs): FavoritesUiState {
        val ready =
            when (val b = i.book) {
                Book.Loading -> return FavoritesUiState(FavoritesPhase.Loading)
                Book.NoPermission -> return FavoritesUiState(FavoritesPhase.NoPermission)
                is Book.Ready -> b
            }
        val answer = ok(ops.view(viewRequest(ready.contacts, i.prefs, i.order)))
        val rows = answer?.rows.orEmpty().associateBy { it.id }
        val favorites =
            answer
                ?.favorites
                .orEmpty()
                .mapNotNull { rows[it]?.toPerson() }
                .toImmutableList()
        val inFavorites = favorites.mapTo(HashSet()) { it.id }
        val pickables =
            ok(ops.view(viewRequest(ready.contacts.filter { it.id !in inFavorites }, i.prefs, emptyList())))
                ?.rows
                .orEmpty()
                .map { it.toPerson() }
                .toImmutableList()
        val now = clock()
        val dates = ok(ops.dates(DatesRequest(ready.drafts, now, offsetS(now), PAGE_DAYS, UPCOMING_SHOWN)))
        val byId = ready.contacts.associateBy { it.id }
        val withNumber = ready.drafts.filter { it.numbers.isNotEmpty() }.mapNotNullTo(HashSet()) { it.id }

        fun upcoming(event: UpcomingEvent) =
            event.toItem(byId[event.contactId]?.photoUri, event.contactId in withNumber)
        return FavoritesUiState(
            phase = FavoritesPhase.Ready,
            favorites = favorites,
            editing = i.editing && favorites.size >= 2,
            frequent = if (i.calls == null || i.editing) persistentListOf() else frequentOf(i.calls, ready, rows, now),
            frequentGate = i.calls == null && !i.editing,
            upcoming =
                dates
                    ?.favorites
                    .orEmpty()
                    .map(::upcoming)
                    .toImmutableList(),
            upcomingTotal = dates?.items?.size ?: 0,
            upcomingGroups =
                dates
                    ?.groups
                    .orEmpty()
                    .map { g -> UpcomingGroupUi(g.title, g.items.map(::upcoming).toImmutableList()) }
                    .toImmutableList(),
            pickables = pickables,
        )
    }

    private suspend fun frequentOf(
        entries: List<CallLogEntry>,
        ready: Book.Ready,
        rows: Map<Long, dev.rovsti.core.model.contacts.ContactRow>,
        now: Long,
    ): ImmutableList<PersonItem> {
        val people =
            ok(
                ops.frequent(FrequentContactsRequest(statCalls(entries), ready.contacts, now)),
            )?.people.orEmpty()
        return people
            .mapNotNull { p -> rows[p.contactId]?.toPerson(secondary = p.subtitle) }
            .toImmutableList()
    }

    private fun viewRequest(
        contacts: List<WireContact>,
        prefs: ContactsPrefs,
        order: List<String>,
    ) = ViewRequest(
        contacts = contacts,
        nameFormat = prefs.nameFormat,
        sortBy = prefs.sortBy,
        favoritesOrder = order,
        widthDp = widthDp,
    )

    // ---- actions

    /** The favorites as the rows show them now, for the move ops. */
    private fun shown(): List<WireContact> {
        val ready = book.book.value as? Book.Ready ?: return emptyList()
        val byId = ready.contacts.associateBy { it.id }
        return state.value.favorites.mapNotNull { byId[it.id] }
    }

    private suspend fun move(request: FavoritesMoveRequest) {
        val keys = ok(ops.favoritesMove(request))?.keys ?: return
        store.update { it.copy(favoritesOrder = keys) }
    }

    private suspend fun star(
        id: Long,
        on: Boolean,
        said: String,
    ) {
        val result = contacts.setStarred(id, on)
        outbox.trySend(
            FavoritesEffect.Toast(if (result is ContactResult.Failed) "Can't change this contact right now." else said),
        )
    }

    private suspend fun addAll(ids: Set<Long>) {
        var added = 0
        for (id in ids) if (contacts.setStarred(id, true) is ContactResult.Ok) added++
        val said =
            when {
                added == 0 -> "Can't change these contacts right now."
                added == 1 -> "1 contact added to Favorites"
                else -> "$added contacts added to Favorites"
            }
        outbox.trySend(FavoritesEffect.Toast(said))
    }

    private suspend fun wish(action: FavoritesAction.Wish) {
        val draft = draftOf(action.contactId) ?: return
        val to = if (action.onPage) pageOutbox else outbox
        val number = draft.numbers.firstOrNull()?.value
        if (number == null) {
            to.trySend(FavoritesEffect.Toast("This contact has no number."))
            return
        }
        val event = draft.events.firstOrNull { it.kind == action.kind } ?: draft.events.firstOrNull() ?: return
        val now = clock()
        val text =
            ok(ops.eventLine(EventLineRequest(event, action.name, now, offsetS(now))))?.wish
                ?: return
        to.trySend(FavoritesEffect.Sms(number, text))
    }

    private suspend fun calendar() {
        val ready = book.book.value as? Book.Ready ?: return
        val now = clock()
        val today = ok(ops.localYmd(LocalYmdRequest(now, offsetS(now)))) ?: return
        val file = ok(ops.ics(IcsRequest(ready.drafts, today, now))) ?: return
        pageOutbox.trySend(
            if (file.count == 0) {
                FavoritesEffect.Toast(NO_DATES)
            } else {
                FavoritesEffect.SaveCalendar(file.fileName, file.text, file.count, file.notice)
            },
        )
    }

    private fun draftOf(id: Long): ContactDraft? = (book.book.value as? Book.Ready)?.drafts?.firstOrNull { it.id == id }

    private fun offsetS(now: Long) = TimeZone.getDefault().getOffset(now) / MS_PER_S

    private fun <T> ok(outcome: Outcome<T>): T? = (outcome as? Outcome.Ok)?.value

    companion object {
        const val DEFAULT_WIDTH_DP = 411.0

        /** The Upcoming section shows this many (`FAVORITES_SHOWN` of the GPUI app); See All has the rest. */
        const val UPCOMING_SHOWN = 3

        /** The See All page looks a year ahead. */
        const val PAGE_DAYS = 365L

        /** The toast when there is nothing to put in a calendar file. */
        const val NO_DATES = "No birthdays or anniversaries to save."
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MS_PER_S = 1_000
    }
}

private fun UpcomingEvent.toItem(
    photoUri: String?,
    canWish: Boolean,
): UpcomingItem {
    val section = listOfNotNull(whenText, turningText).joinToString(SEPARATOR)
    val page = listOfNotNull(whenText, dateText, turningText).joinToString(SEPARATOR)
    return UpcomingItem(
        key = "$contactId-${kind.name}-$inDays",
        contactId = contactId,
        name = name,
        kind = kind,
        photoUri = photoUri,
        sectionLine = section,
        pageLine = page,
        canWish = canWish,
    )
}

private const val SEPARATOR = " · "

/** The call log as the stats ops read it (`call_stats::from_sources`): blocked and unknown calls are dropped. */
internal fun statCalls(entries: List<CallLogEntry>): List<StatCall> =
    entries.mapNotNull { e ->
        val dir =
            when (e.kind) {
                CallLogKind.Incoming -> StatDir.Incoming
                CallLogKind.Outgoing -> StatDir.Outgoing
                CallLogKind.Missed, CallLogKind.Rejected, CallLogKind.Voicemail -> StatDir.Missed
                CallLogKind.Blocked, CallLogKind.Unknown -> return@mapNotNull null
            }
        StatCall(
            number = e.numberRaw.ifEmpty { e.normalized },
            name = e.cachedName?.takeIf { it.isNotEmpty() },
            dateMs = e.dateMs,
            durationS = e.durationS.coerceAtLeast(0),
            dir = dir,
            whatsapp = false,
        )
    }
