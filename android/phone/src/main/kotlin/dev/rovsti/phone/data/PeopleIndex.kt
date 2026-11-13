package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustNative
import dev.rovsti.core.model.phone.CallCountsRequest
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.DisplayNameRequest
import dev.rovsti.core.model.phone.ExtraCall
import dev.rovsti.core.model.phone.IdsForNumbersAnswer
import dev.rovsti.core.model.phone.IdsForNumbersRequest
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.search.T9Row
import dev.rovsti.core.model.search.WantedNumber
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactIndexHandle
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.SearchOps
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.identity.ContactBook
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The people with a phone number, as the Phone screens see them. */
sealed interface People {
    /** Nothing was read yet. */
    data object Loading : People

    /** READ_CONTACTS is not held: the Contacts tab and the T9 strip show their permission row. */
    data object NoPermission : People

    data class Ready(
        val contacts: ImmutableList<WireContact>,
    ) : People
}

/**
 * The contacts with a number and their search index. [people] follows [ContactsRepository.withNumbers] while
 * [permission] says Granted; the index (`search.index_new`) is built from the first list and swapped in place on a
 * provider change (`search.index_replace`), and the handle is closed when the permission goes. [search] and [t9] run
 * on it; before it exists they answer nothing. Changes that arrive while the index updates are conflated.
 *
 * Name order ([nameFormat]) comes from the Phone settings; T9 spells the names in that order. The other helpers are
 * the core's own rules: [displayName] (`phone.display_name`), [idsForNumbers] (`recents.ids_for_numbers`) and
 * [rankByCalls] (`recents.call_counts` into `search.set_call_counts`, so the T9 strip ranks callers first).
 *
 * It is also the [ContactBook] of the caller identity resolver ([owners], `search.match_numbers`): [revision] changes
 * after every rebuild, so names follow contact edits (the contacts flow re-reads on the provider's change notification,
 * off the main thread).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleIndex(
    private val contacts: ContactsRepository,
    permission: Flow<PermissionState>,
    private val search: SearchOps,
    private val ops: PhoneOps,
    scope: CoroutineScope,
    private val nameFormat: () -> NameFormat? = { null },
    private val release: (Long) -> Unit = RustNative::release,
    /** A number's E.164 form in the home region (Android's), so [matchNumber] keys it as Recents does (F39). */
    private val e164: (String) -> String? = { null },
    /** The home calling code for national numbers Android could not normalize. */
    private val homeCc: suspend () -> String? = { null },
) : ContactBook {
    private val peopleState = MutableStateFlow<People>(People.Loading)
    private val indexState = MutableStateFlow<ContactIndexHandle?>(null)
    private val revisionState = MutableStateFlow(0L)

    @Volatile private var byId: Map<Long, WireContact> = emptyMap()

    override val revision: StateFlow<Long> = revisionState.asStateFlow()

    override val ready: Boolean get() = indexState.value != null

    val people: StateFlow<People> = peopleState.asStateFlow()

    /** The index of the current [People.Ready]; null before it and without permission. */
    val index: StateFlow<ContactIndexHandle?> = indexState.asStateFlow()

    init {
        scope.launch {
            permission
                .map { it == PermissionState.Granted }
                .distinctUntilChanged()
                .flatMapLatest { granted ->
                    if (granted) {
                        contacts.withNumbers().conflate().map { People.Ready(it.toImmutableList()) as People }
                    } else {
                        flowOf(People.NoPermission)
                    }
                }.conflate()
                .collect { next ->
                    when (next) {
                        is People.Ready -> refresh(next)
                        else -> drop()
                    }
                    peopleState.value = next
                    revisionState.value += 1
                }
        }
    }

    /** Contacts whose name, number or email contains [text]; the loose pass adds word prefixes and initials. */
    suspend fun search(
        text: String,
        limit: Int? = null,
        loose: Boolean = true,
    ): List<WireContact> {
        val open = indexState.value ?: return emptyList()
        val out = if (loose) open.queryLoose(text, limit) else open.query(text, limit)
        return (out as? Outcome.Ok)?.value?.rows.orEmpty()
    }

    /** The T9 suggestions for the keypad [digits] (at most 50, best first); empty for no digits. */
    suspend fun t9(digits: String): List<T9Row> {
        val open = indexState.value ?: return emptyList()
        if (digits.isEmpty()) return emptyList()
        return (open.t9(digits, nameFormat()) as? Outcome.Ok)?.value?.hits.orEmpty()
    }

    /**
     * The contact that owns [number], null for a stranger: the number book with the number's E.164 form and the home
     * code, exactly as the caller-ID resolver asks it (F39), so the keypad and Recents agree on who a number is.
     */
    suspend fun matchNumber(number: String): WireContact? =
        owners(listOf(WantedNumber(number, e164(number))), homeCc())?.firstOrNull()

    override suspend fun owners(
        numbers: List<WantedNumber>,
        homeCc: String?,
    ): List<WireContact?>? {
        val open = indexState.value ?: return null
        val contacts = byId
        val ids = (open.matchNumbers(numbers, homeCc) as? Outcome.Ok)?.value?.ids ?: return null
        return ids.map { id -> id?.let(contacts::get) }
    }

    /** The name to show for [contact] in the chosen order; empty when it has nothing to show. */
    suspend fun displayName(
        contact: WireContact,
        format: NameFormat = nameFormat() ?: NameFormat.FirstLast,
    ): String = (ops.displayName(DisplayNameRequest(contact, format)) as? Outcome.Ok)?.value?.name.orEmpty()

    /** The contact ids behind [numbers] in the log and the WhatsApp calls (for per-person actions on a row). */
    suspend fun idsForNumbers(
        entries: List<CallLogEntry>,
        extras: List<ExtraCall>,
        numbers: List<String>,
    ): IdsForNumbersAnswer? = (ops.idsForNumbers(IdsForNumbersRequest(entries, extras, numbers)) as? Outcome.Ok)?.value

    /** Ranks the T9 strip by how often each contact was called in the log's window. */
    suspend fun rankByCalls(
        entries: List<CallLogEntry>,
        nowMs: Long,
    ) {
        val open = indexState.value ?: return
        val counts = (ops.callCounts(CallCountsRequest(entries, nowMs)) as? Outcome.Ok)?.value ?: return
        open.setCallCounts(counts.counts.associate { it.id to it.count })
    }

    private suspend fun refresh(next: People.Ready) {
        val open = indexState.value
        byId = next.contacts.associateBy { it.id }
        if (open == null) {
            when (val built = ContactIndexHandle.open(next.contacts, nameFormat(), search, release)) {
                is Outcome.Ok -> indexState.value = built.value
                is Outcome.Err -> Unit
            }
        } else {
            open.replace(next.contacts)
        }
    }

    private fun drop() {
        byId = emptyMap()
        indexState.value?.close()
        indexState.value = null
    }
}
