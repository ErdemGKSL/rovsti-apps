package dev.rosty.messages.data

import dev.rosty.core.Outcome
import dev.rosty.core.RustNative
import dev.rosty.core.model.search.WireContact
import dev.rosty.core.ops.ContactIndexHandle
import dev.rosty.core.ops.SearchOps
import dev.rosty.platform.contacts.ContactsRepository
import dev.rosty.platform.system.permissions.PermissionState
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

/** The address book as Messages sees it: the contacts with a number and their index in the Rust core. */
data class Book(
    /** The open index the inbox merge and the name lookups ask (empty without contacts permission); null until open. */
    val index: ContactIndexHandle?,
    val contacts: Map<Long, WireContact>,
    /** Bumped on every change, so a list that merged against an older book merges again. */
    val revision: Long,
) {
    companion object {
        val Empty = Book(null, emptyMap(), 0)
    }
}

/**
 * Follows `READ_CONTACTS` and the contacts provider and keeps one Rust contact index open for them (`search.index_new`,
 * replaced on change). Without the permission the index is empty: names stay numbers, the inbox still shows every
 * conversation (a missing permission degrades one feature, ARCH 7.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactBook(
    private val contacts: ContactsRepository,
    permission: Flow<PermissionState>,
    private val search: SearchOps,
    scope: CoroutineScope,
    private val release: (Long) -> Unit = RustNative::release,
) {
    private val state = MutableStateFlow(Book.Empty)

    val book: StateFlow<Book> = state.asStateFlow()

    init {
        scope.launch {
            permission
                .map { it == PermissionState.Granted }
                .distinctUntilChanged()
                .flatMapLatest { granted -> if (granted) contacts.withNumbers().conflate() else flowOf(null) }
                .conflate()
                .collect { list -> refresh(list.orEmpty()) }
        }
    }

    /** The contact that owns [number], or null (a stranger, or no book). */
    suspend fun owner(number: String): WireContact? {
        val current = state.value
        val index = current.index ?: return null
        val id = (index.matchNumber(number) as? Outcome.Ok)?.value?.id ?: return null
        return current.contacts[id]
    }

    private suspend fun refresh(list: List<WireContact>) {
        val current = state.value
        val byId = list.associateBy { it.id }
        val index =
            current.index?.also { it.replace(list) }
                ?: (ContactIndexHandle.open(list, ops = search, release = release) as? Outcome.Ok)?.value
        state.value = Book(index, byId, current.revision + 1)
    }
}
