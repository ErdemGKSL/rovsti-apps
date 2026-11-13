package dev.rovsti.contacts.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.WaMarksRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.ops.ContactsWaOps
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Which contacts carry the WhatsApp mark in the list (AD-CT6). The marks come from the 1:1 chats the instance already
 * has: [WaRepository.chats] and the [BookRepository.book] go to `contacts.wa_marks`, nothing is looked up and the
 * address book is never sent to the gateway. Without a link ([WaLinkState.Connected]) there are no chats to match and
 * [marks] stays empty. The chats refetch on every resync because the repository's flow does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaMarks(
    wa: WaRepository,
    book: StateFlow<Book>,
    private val ops: ContactsWaOps,
    scope: CoroutineScope,
    /**
     * The home calling code of the book (`format.infer_home_cc`), so a contact saved as `0532 …` gets the mark of the
     * chat `90532…`: the shared caller-ID rules compare numbers in E.164 (F39).
     */
    private val homeCc: suspend (List<WireContact>) -> String? = { null },
) {
    private val state = MutableStateFlow<ImmutableMap<Long, String>>(persistentMapOf())

    /** Contact id to the JID of its chat. */
    val marks: StateFlow<ImmutableMap<Long, String>> = state.asStateFlow()

    init {
        scope.launch {
            wa.linkState
                .map { it is WaLinkState.Connected }
                .distinctUntilChanged()
                .flatMapLatest { linked ->
                    if (linked) combine(wa.chats(), book) { chats, now -> chatUsers(chats) to now } else flowOf(null)
                }.conflate()
                .collect { input ->
                    state.value = if (input == null) persistentMapOf() else marksOf(input.first, input.second)
                }
        }
    }

    private suspend fun marksOf(
        users: List<String>,
        book: Book,
    ): ImmutableMap<Long, String> {
        if (users.isEmpty() || book !is Book.Ready) return persistentMapOf()
        return when (val answer = ops.marks(WaMarksRequest(book.contacts, users, homeCc(book.contacts)))) {
            is Outcome.Ok -> answer.value.marks.toImmutableMap()
            is Outcome.Err -> persistentMapOf()
        }
    }

    companion object {
        private const val PERSON_SUFFIX = "@s.whatsapp.net"

        /** The user parts of the 1:1 chats (groups and statuses are never matched), as `chat_users` in Rust. */
        fun chatUsers(chats: List<RichChat>): List<String> =
            chats
                .filter { !it.isGroup && it.jid.endsWith(PERSON_SUFFIX) }
                .map { it.jid.substringBefore('@') }
                .filter { it.isNotEmpty() }
    }
}
