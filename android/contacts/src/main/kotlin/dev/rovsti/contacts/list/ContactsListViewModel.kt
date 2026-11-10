package dev.rovsti.contacts.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.data.WaMarks
import dev.rovsti.contacts.pinContactToHome
import dev.rovsti.contacts.toUi
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.CallTarget
import dev.rovsti.core.model.contacts.CardRequest
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.ContactRow
import dev.rovsti.core.model.contacts.ContactsPrefs
import dev.rovsti.core.model.contacts.DraftAddRequest
import dev.rovsti.core.model.contacts.DraftQueryRequest
import dev.rovsti.core.model.contacts.PinShortcutRequest
import dev.rovsti.core.model.contacts.QuickAction
import dev.rovsti.core.model.contacts.QuickRequest
import dev.rovsti.core.model.contacts.RecentListsRequest
import dev.rovsti.core.model.contacts.RecentViewedRequest
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactIndexHandle
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.DeepIndexHandle
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.text
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.files.ClipboardRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
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
 * The Contacts tab (E4.2). Rows, sections, the scrubber letters and the empty-state offers come from Rust
 * (`contacts.view`, `contacts.quick`, `contacts.recent_lists`, `contacts.draft_from_query`); the
 * search uses the name index (`search.query_loose`: names, numbers, initials) and, for the "In details" section, the
 * detail index (`contacts.deep_find`). This class orders those calls and maps their answers to one [ContactsListUiState].
 *
 * Typing is conflated: a new query cancels the build of the previous one (`mapLatest`), so the latest text wins and a
 * burst of keys costs one list. The build runs off the main thread. One-shot results go out as [effects].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsListViewModel(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val ops: ContactsOps,
    private val waMarks: WaMarks,
    private val contacts: ContactsRepository,
    private val clipboard: ClipboardRepository,
    private val shortcuts: ShortcutsRepository,
    private val trash: ContactTrash,
    private val clock: () -> Long = System::currentTimeMillis,
    private val widthDp: Double = DEFAULT_WIDTH_DP,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val menus = HashMap<MenuKey, ImmutableList<QuickAction>>()
    private val outbox = Channel<ContactsListEffect>(Channel.UNLIMITED)
    private val select = SelectController(book, contacts, ops, trash, clipboard, clock) { outbox.trySend(it) }

    private class Inputs(
        val book: Book,
        val index: ContactIndexHandle?,
        val deep: DeepIndexHandle?,
        val prefs: ContactsPrefs,
        val order: List<String>,
        val marks: Map<Long, String>,
        val query: String,
        val hidden: Set<Long>,
    )

    /** The list to draw. */
    val state: StateFlow<ContactsListUiState> =
        combine(
            combine(book.book, book.index, book.deep) { b, i, d -> Triple(b, i, d) },
            combine(store.prefs, store.favoritesOrder, waMarks.marks) { p, o, m -> Triple(p, o, m) },
            combine(query, select.hidden) { q, h -> q to h },
        ) { (b, i, d), (p, o, m), (q, h) -> Inputs(b, i, d, p, o, m, q, h) }
            .conflate()
            .mapLatest { build(it) }
            .combine(select.selection) { built, chosen -> built to chosen }
            .combine(contacts.groups()) { (built, chosen), groups -> Triple(built, chosen, groups.isNotEmpty()) }
            .mapLatest { (built, chosen, hasGroups) ->
                if (chosen == null) {
                    if (built.selectMode == null) built else built.copy(selectMode = null)
                } else {
                    built.copy(selectMode = select.bar(chosen, built.rows.map { it.id }, hasGroups))
                }
            }.flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ContactsListUiState())

    /**
     * The bar of select mode without the list (null while not selecting): the app shell folds the tab bar and draws the
     * select toolbar from it, while the list page itself may not be composed.
     */
    val selectToolbar: StateFlow<SelectMode?> =
        combine(select.selection, contacts.groups()) { chosen, groups -> chosen to groups.isNotEmpty() }
            .mapLatest { (chosen, hasGroups) -> select.bar(chosen, emptyList(), hasGroups) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Navigation, toasts, intents and the confirmation, each delivered once. */
    val effects: Flow<ContactsListEffect> = outbox.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: ContactsListAction) {
        when (action) {
            is ContactsListAction.QueryChanged -> query.value = action.text

            is ContactsListAction.Open -> viewModelScope.launch { open(action.id) }

            is ContactsListAction.Quick -> viewModelScope.launch { quick(action.id, action.action) }

            is ContactsListAction.EnterSelect -> select.enter(action.first)

            ContactsListAction.ExitSelect -> select.exit()

            is ContactsListAction.ToggleSelected -> select.toggle(action.id)

            ContactsListAction.ToggleSelectAll -> select.toggleAll(state.value.rows.map { it.id })

            ContactsListAction.ShareSelected -> viewModelScope.launch { select.share() }

            ContactsListAction.ExportSelected -> viewModelScope.launch { select.exportVcf() }

            ContactsListAction.CopySelectedNumbers -> viewModelScope.launch { select.copyAddresses("sms") }

            ContactsListAction.CopySelectedEmails -> viewModelScope.launch { select.copyAddresses("email") }

            ContactsListAction.StarSelected -> viewModelScope.launch { select.star() }

            ContactsListAction.AskDeleteSelected -> viewModelScope.launch { select.askDelete() }

            ContactsListAction.DeleteSelected -> viewModelScope.launch { select.delete() }

            ContactsListAction.AskAddToGroup -> viewModelScope.launch { select.askGroup() }

            is ContactsListAction.AddSelectedToGroup -> viewModelScope.launch { select.addToGroup(action.groupId) }

            is ContactsListAction.UndoDelete -> viewModelScope.launch { select.undo(action.keys) }

            ContactsListAction.NewContact -> outbox.trySend(ContactsListEffect.OpenEditor(null, null))

            ContactsListAction.CreateFromQuery -> createFromQuery()

            is ContactsListAction.AddToContact -> viewModelScope.launch { addTo(action.id) }

            ContactsListAction.AskClearViewed -> askClear()

            ContactsListAction.ClearViewed -> viewModelScope.launch { clearViewed() }

            // The page opens these three (the model of My card is `MeViewModel`).
            ContactsListAction.OpenMyCard, ContactsListAction.OpenEmergency, ContactsListAction.OpenRecent -> Unit
        }
    }

    // ---- building the list

    private suspend fun build(i: Inputs): ContactsListUiState =
        when (val b = i.book) {
            Book.Loading -> ContactsListUiState(phase = ListPhase.Loading, query = i.query)
            Book.NoPermission -> ContactsListUiState(phase = ListPhase.NoPermission, query = i.query)
            is Book.Ready -> buildReady(b, i)
        }

    private suspend fun buildReady(
        b: Book.Ready,
        i: Inputs,
    ): ContactsListUiState {
        val searching = i.query.isNotBlank()
        val matched: List<WireContact> =
            if (searching) {
                (i.index?.queryLoose(i.query) as? Outcome.Ok)?.value?.rows.orEmpty()
            } else {
                b.contacts
            }
        val answer =
            (ops.view(viewRequest(matched, i.query, i.prefs, i.order)) as? Outcome.Ok)?.value
                ?: return ContactsListUiState(
                    phase = ListPhase.Ready,
                    query = i.query,
                    emptyKind = if (searching) EmptyKind.NoResults else EmptyKind.NoContacts,
                    queryEcho = i.query.trim(),
                )
        // The view orders by name; a search keeps the index's ranking (name hits first, initials after).
        val rank = matched.withIndex().associate { it.value.id to it.index }
        val ordered = if (searching) answer.rows.sortedBy { rank[it.id] ?: Int.MAX_VALUE } else answer.rows
        val rows = ordered.map { it.toItem(i.marks) }.toImmutableList()
        val deep = if (searching) deepRows(b, i, rows) else persistentListOf()

        val empty =
            when {
                rows.isNotEmpty() || deep.isNotEmpty() -> EmptyKind.None
                searching -> EmptyKind.NoResults
                else -> EmptyKind.NoContacts
            }
        val offer = if (empty == EmptyKind.NoResults) offer(i.query) else SearchOffer()
        val pickables =
            if (offer.addition != null) {
                itemsOf(b.contacts, i.prefs, i.order, i.marks, emptyList())
            } else {
                persistentListOf()
            }
        val letters = answer.scrubberLetters.mapNotNull { it.firstOrNull() }.toImmutableList()
        val (sectionIndex, scrubItems) = sectionIndexOf(rows)

        var viewed: ImmutableList<ContactItem> = persistentListOf()
        var viewedClear = ContactsListUiState().viewedClear
        var recent: RecentEntry? = null
        if (!searching) {
            val lists = (ops.recentLists(recentRequest(b, i)) as? Outcome.Ok)?.value
            if (lists != null) {
                viewedClear = lists.clearViewed.toUi()
                if (lists.showEntry) recent = RecentEntry(lists.entryTitle, lists.entryDetail)
                val byId = b.contacts.associateBy { it.id }
                viewed = itemsOf(lists.viewed.mapNotNull { byId[it] }, i.prefs, i.order, i.marks, lists.viewed)
            }
        }
        val keys = (rows + deep + viewed + pickables).map { it.menuKey }.toSet()
        return ContactsListUiState(
            phase = ListPhase.Ready,
            rows = rows,
            deepRows = deep,
            scrubberLetters = letters,
            showScrubber = answer.showScrubber,
            sectionIndex = sectionIndex,
            scrubItems = scrubItems,
            query = i.query,
            queryEcho = answer.queryEcho,
            emptyKind = empty,
            offer = offer,
            pickables = pickables,
            viewed = viewed,
            viewedClear = viewedClear,
            recentEntry = recent,
            quick = quickFor(keys),
        )
    }

    private fun viewRequest(
        contacts: List<WireContact>,
        text: String,
        prefs: ContactsPrefs,
        order: List<String>,
    ) = ViewRequest(
        contacts = contacts,
        query = text,
        nameFormat = prefs.nameFormat,
        sortBy = prefs.sortBy,
        favoritesOrder = order,
        hidden = select.hidden.value.toList(),
        widthDp = widthDp,
    )

    /** Rows for [chosen] contacts in the given id order (or the view's order when [order] is empty). */
    private suspend fun itemsOf(
        chosen: List<WireContact>,
        prefs: ContactsPrefs,
        order: List<String>,
        marks: Map<Long, String>,
        ids: List<Long>,
    ): ImmutableList<ContactItem> {
        if (chosen.isEmpty()) return persistentListOf()
        val rows = (ops.view(viewRequest(chosen, "", prefs, order)) as? Outcome.Ok)?.value?.rows.orEmpty()
        val rank = ids.withIndex().associate { it.value to it.index }
        val sorted = if (ids.isEmpty()) rows else rows.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        return sorted.map { it.toItem(marks).copy(header = false) }.toImmutableList()
    }

    private suspend fun deepRows(
        b: Book.Ready,
        i: Inputs,
        listed: List<ContactItem>,
    ): ImmutableList<ContactItem> {
        val handle = i.deep ?: return persistentListOf()
        val hits =
            (handle.find(i.query, MAX_DEEP, listed.map { it.id }) as? Outcome.Ok)?.value?.hits.orEmpty()
        if (hits.isEmpty()) return persistentListOf()
        val byId = b.contacts.associateBy { it.id }
        val found = hits.mapNotNull { byId[it.id] }
        val items = itemsOf(found, i.prefs, i.order, i.marks, hits.map { it.id }).associateBy { it.id }
        return hits
            .mapNotNull { hit -> items[hit.id]?.copy(secondary = "${hit.label} · ${hit.snippet}") }
            .toImmutableList()
    }

    private suspend fun offer(text: String): SearchOffer {
        val made = (ops.draftFromQuery(DraftQueryRequest(text)) as? Outcome.Ok)?.value ?: return SearchOffer()
        return SearchOffer(create = made.draft?.toSeed(), addition = made.addition)
    }

    private suspend fun recentRequest(
        b: Book.Ready,
        i: Inputs,
    ): RecentListsRequest {
        val now = clock()
        return RecentListsRequest(
            contacts = b.contacts,
            marks = i.prefs.added,
            viewedKeys = i.prefs.viewed,
            hidden = select.hidden.value.toList(),
            nowMs = now,
            tzOffsetS = TimeZone.getDefault().getOffset(now) / MS_PER_S,
            query = i.query,
        )
    }

    private suspend fun quickFor(
        keys: Set<MenuKey>,
    ): kotlinx.collections.immutable.ImmutableMap<MenuKey, ImmutableList<QuickAction>> {
        for (key in keys) {
            if (key in menus) continue
            val answer = (ops.quick(QuickRequest(key.hasNumber, key.onWhatsapp, key.starred)) as? Outcome.Ok)?.value
            menus[key] = answer?.actions.orEmpty().toImmutableList()
        }
        return keys.associateWith { menus.getValue(it) }.toImmutableMap()
    }

    // ---- actions

    private suspend fun open(id: Long) {
        val key = lookupKeyOf(id)
        if (key != null) {
            val next = (ops.recentViewed(RecentViewedRequest(store.viewed, key, "note")) as? Outcome.Ok)?.value
            if (next != null) store.update { it.copy(viewed = next.keys) }
        }
        outbox.trySend(ContactsListEffect.OpenCard(id))
    }

    private suspend fun quick(
        id: Long,
        action: String,
    ) {
        val contact = contactOf(id) ?: return
        when (action) {
            "call" -> {
                numberFor(contact, UiText.Res(R.string.contacts_choose_number_call))?.let {
                    outbox.trySend(ContactsListEffect.Dial(it))
                }
            }

            "message" -> {
                numberFor(contact, UiText.Res(R.string.contacts_choose_number_message))?.let {
                    outbox.trySend(ContactsListEffect.Sms(it))
                }
            }

            "copy_number" -> {
                numberFor(contact, UiText.Res(R.string.contacts_tap_number_copy))?.let { copy(it) }
            }

            "whatsapp" -> {
                val jid = waMarks.marks.value[id]
                outbox.trySend(
                    if (jid != null) {
                        ContactsListEffect.WhatsApp(jid, contact.displayName)
                    } else {
                        ContactsListEffect.Toast(UiText.Res(R.string.contacts_not_on_whatsapp))
                    },
                )
            }

            "share" -> {
                card(contact)?.let { outbox.trySend(ContactsListEffect.Share(it.shareText)) }
            }

            "star", "unstar" -> {
                star(id, action == "star")
            }

            "add_to_home" -> {
                pin(contact)
            }
        }
    }

    private suspend fun card(contact: WireContact) =
        (ops.card(CardRequest(contact, store.prefs.value.nameFormat)) as? Outcome.Ok)?.value

    /** The number Call, Message and Copy use: the default or only one; with several and no default the card opens. */
    private suspend fun numberFor(
        contact: WireContact,
        hint: UiText,
    ): String? {
        val card = card(contact) ?: return null
        return when (card.callTarget) {
            CallTarget.Direct -> {
                card.numbers.getOrNull(card.callIndex ?: 0)?.raw
            }

            CallTarget.Choose -> {
                outbox.trySend(ContactsListEffect.OpenCard(contact.id))
                outbox.trySend(ContactsListEffect.Toast(hint))
                null
            }

            CallTarget.Nothing -> {
                outbox.trySend(ContactsListEffect.Toast(UiText.Res(R.string.contacts_no_number)))
                null
            }
        }
    }

    private fun copy(number: String) {
        val done = clipboard.copy(number)
        outbox.trySend(
            ContactsListEffect.Toast(
                if (done) UiText.Res(R.string.contacts_number_copied_dot) else UiText.Res(R.string.contacts_cant_copy),
            ),
        )
    }

    private suspend fun star(
        id: Long,
        on: Boolean,
    ) {
        if (contacts.setStarred(id, on) is ContactResult.Failed) {
            outbox.trySend(ContactsListEffect.Toast(UiText.Res(R.string.contacts_cant_change_contact)))
        }
    }

    private suspend fun pin(contact: WireContact) {
        val toast = pinContactToHome(ops, shortcuts, contact.id, contact.lookupKey, contact.displayName) ?: return
        outbox.trySend(ContactsListEffect.Toast(toast))
    }

    private fun createFromQuery() {
        val seed = state.value.offer.create ?: return
        outbox.trySend(ContactsListEffect.OpenEditor(null, seed))
    }

    private suspend fun addTo(id: Long) {
        val addition = state.value.offer.addition ?: return
        val target = (book.book.value as? Book.Ready)?.drafts?.firstOrNull { it.id == id } ?: return
        val answer = (ops.draftAdd(DraftAddRequest(target, addition)) as? Outcome.Ok)?.value ?: return
        if (answer.refused || answer.draft == null) {
            outbox.trySend(ContactsListEffect.Toast(UiText.Literal(answer.notice)))
            return
        }
        val seed =
            DraftSeed(
                number = addition.numbers.firstOrNull().orEmpty(),
                email = addition.emails.firstOrNull().orEmpty(),
            )
        outbox.trySend(ContactsListEffect.OpenEditor(id, seed))
    }

    private fun askClear() {
        outbox.trySend(ContactsListEffect.ConfirmClearViewed(state.value.viewedClear))
    }

    private suspend fun clearViewed() {
        store.update { it.copy(viewed = emptyList()) }
        outbox.trySend(ContactsListEffect.Toast(UiText.Res(R.string.contacts_recently_viewed_cleared)))
    }

    private fun contactOf(id: Long): WireContact? =
        (book.book.value as? Book.Ready)?.contacts?.firstOrNull {
            it.id ==
                id
        }

    private fun lookupKeyOf(id: Long): String? = contactOf(id)?.lookupKey

    companion object {
        /** The width the favorites grid is laid out for; the Contacts tab does not use the columns. */
        const val DEFAULT_WIDTH_DP = 411.0

        /** Most "In details" rows under a query (`finds::MAX_DEEP`). */
        const val MAX_DEEP = 20
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MS_PER_S = 1_000
    }
}

private fun ContactRow.toItem(marks: Map<Long, String>) =
    ContactItem(
        id = id,
        name = name,
        secondary = subtitle,
        letter = letter,
        header = header,
        starred = starred,
        whatsapp = id in marks,
        hasNumber = numbers.isNotEmpty(),
        photoUri = photoUri,
    )

private fun ContactDraft.toSeed() =
    DraftSeed(
        name = listOf(given, family).filter { it.isNotBlank() }.joinToString(" "),
        number = numbers.firstOrNull()?.value.orEmpty(),
        email = emails.firstOrNull()?.value.orEmpty(),
    )

/**
 * Where each section's header sits among the scrubbable items (the headers and the rows in list order), and how many
 * items that is. The rail scrolls to `index + the items above the first section`.
 */
internal fun sectionIndexOf(rows: List<ContactItem>): Pair<kotlinx.collections.immutable.ImmutableMap<Char, Int>, Int> {
    val map = LinkedHashMap<Char, Int>()
    var position = 0
    for (row in rows) {
        if (row.header) {
            row.letter.firstOrNull()?.let { map.putIfAbsent(it, position) }
            position++
        }
        position++
    }
    return map.toImmutableMap() to position
}
