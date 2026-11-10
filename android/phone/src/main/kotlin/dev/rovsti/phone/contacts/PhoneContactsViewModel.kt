package dev.rovsti.phone.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactRow
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.phone.data.People
import dev.rovsti.phone.data.PeopleIndex
import dev.rovsti.phone.data.PhoneStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the tab shows while the contacts are not ready. */
enum class PeoplePhase { Loading, NoPermission, Ready }

/** Why the list is empty. */
enum class PeopleEmpty { None, NoContacts, NoResults }

/** One contact row. [subtitle] is the one line under the name (the number's type and the number, from the core). */
@Immutable
data class PersonRowUi(
    val id: Long,
    val name: String,
    val subtitle: String?,
    val photoUri: String?,
    val starred: Boolean,
    val letter: String,
    /** First row of its letter section (never while searching). */
    val header: Boolean,
)

/** The whole Contacts tab as one value. */
@Immutable
data class PeopleUi(
    val phase: PeoplePhase = PeoplePhase.Loading,
    val rows: ImmutableList<PersonRowUi> = persistentListOf(),
    /** The letters of the alphabet rail. */
    val letters: ImmutableList<Char> = persistentListOf(),
    val showRail: Boolean = false,
    /** Where each letter's header sits among the scrubbable items (the headers and the rows in list order). */
    val sectionIndex: ImmutableMap<Char, Int> = persistentMapOf(),
    /** How many items the rail scrubs over. */
    val scrubItems: Int = 0,
    val query: String = "",
    val empty: PeopleEmpty = PeopleEmpty.None,
)

/** What the screen asks of the model. */
sealed interface PeopleAction {
    data class SetQuery(
        val text: String,
    ) : PeopleAction
}

/**
 * The Contacts tab (E5.5): the people with a number in sections by their first letter. The order, the letters and the
 * rail come from `contacts.view` (sort and name format from the Phone settings); a search takes the matches of the
 * search index (`search.query_loose`, name hits first, then initials, then numbers) and keeps that ranking, without
 * sections.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneContactsViewModel(
    private val people: PeopleIndex,
    private val store: PhoneStore,
    private val ops: ContactsOps,
    private val widthDp: Double = DEFAULT_WIDTH_DP,
    worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<PeopleUi> =
        combine(people.people, people.index, store.prefs, query) { p, _, prefs, q -> Triple(p, prefs, q) }
            .conflate()
            .mapLatest { (p, prefs, q) -> build(p, prefs.settings.nameFormat, prefs.settings.sortBy, q) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PeopleUi())

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: PeopleAction) {
        when (action) {
            is PeopleAction.SetQuery -> query.value = action.text
        }
    }

    /** The contact behind a row, for the Person sheet. */
    fun contact(id: Long): WireContact? = (people.people.value as? People.Ready)?.contacts?.firstOrNull { it.id == id }

    private suspend fun build(
        p: People,
        nameFormat: dev.rovsti.core.model.search.NameFormat,
        sortBy: dev.rovsti.core.model.phone.SortBy,
        text: String,
    ): PeopleUi {
        val ready =
            when (p) {
                People.Loading -> return PeopleUi(PeoplePhase.Loading, query = text)
                People.NoPermission -> return PeopleUi(PeoplePhase.NoPermission, query = text)
                is People.Ready -> p
            }
        val searching = text.isNotBlank()
        val matched = if (searching) people.search(text.trim()) else ready.contacts
        val answer =
            (
                ops.view(
                    ViewRequest(
                        contacts = matched,
                        query = text.trim(),
                        nameFormat = nameFormat,
                        sortBy = sortBy,
                        widthDp = widthDp,
                    ),
                ) as? Outcome.Ok
            )?.value
        if (answer == null) {
            return PeopleUi(PeoplePhase.Ready, query = text, empty = emptyKind(searching))
        }
        // The view orders by name; a search keeps the index's ranking.
        val rank = matched.withIndex().associate { it.value.id to it.index }
        val ordered = if (searching) answer.rows.sortedBy { rank[it.id] ?: Int.MAX_VALUE } else answer.rows
        val rows = ordered.map { it.toUi() }.toImmutableList()
        val (index, items) = sectionIndexOf(rows)
        return PeopleUi(
            phase = PeoplePhase.Ready,
            rows = rows,
            letters = answer.scrubberLetters.mapNotNull { it.firstOrNull() }.toImmutableList(),
            showRail = answer.showScrubber && !searching,
            sectionIndex = index,
            scrubItems = items,
            query = text,
            empty = if (rows.isEmpty()) emptyKind(searching) else PeopleEmpty.None,
        )
    }

    private fun emptyKind(searching: Boolean) = if (searching) PeopleEmpty.NoResults else PeopleEmpty.NoContacts

    companion object {
        const val DEFAULT_WIDTH_DP = 411.0
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun ContactRow.toUi() =
    PersonRowUi(
        id = id,
        name = name,
        subtitle = subtitle,
        photoUri = photoUri,
        starred = starred,
        letter = letter,
        header = header,
    )

/**
 * Where each section's header sits among the scrubbable items (the headers and the rows in list order), and how many
 * items that is. The rail scrolls to `index + the items above the first section`.
 */
internal fun sectionIndexOf(rows: List<PersonRowUi>): Pair<ImmutableMap<Char, Int>, Int> {
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
