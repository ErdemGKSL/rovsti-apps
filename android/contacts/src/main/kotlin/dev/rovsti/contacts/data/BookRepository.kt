package dev.rovsti.contacts.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustNative
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactIndexHandle
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.DeepIndexHandle
import dev.rovsti.core.ops.SearchOps
import dev.rovsti.platform.contacts.ContactsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The address book as the Contacts screens see it. */
sealed interface Book {
    /** Nothing was read yet. */
    data object Loading : Book

    /** READ_CONTACTS is not held (never asked, denied, or revoked in Settings): the gate shows its row. */
    data object NoPermission : Book

    /**
     * Every visible contact. [drafts] are the same people with all their fields, the input of the detail search
     * ([BookRepository.deep]).
     */
    data class Ready(
        val contacts: ImmutableList<WireContact>,
        val drafts: ImmutableList<ContactDraft>,
    ) : Book
}

/**
 * The address book and its search indexes. [book] follows [ContactsRepository.all] (and reads [ContactsRepository.full] with it) while
 * [permission] says Granted, and is [Book.NoPermission] otherwise. The indexes are kept in step before each new
 * [Book.Ready] is published, so a screen that sees a book can already query it:
 *
 * - [index] is the name, number and email index (`search.index_new`); a provider change swaps its contacts with one
 *   `search.index_replace`, never a rebuild.
 * - [deep] is the detail-search index (`contacts.deep_index_new`); it is rebuilt on a change and the previous handle is
 *   closed (the native object is released).
 *
 * Changes arriving while an index is updating are conflated: only the newest book is indexed. Both handles are closed
 * when the permission goes away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepository(
    private val contacts: ContactsRepository,
    private val permission: Flow<PermissionState>,
    private val search: SearchOps,
    private val ops: ContactsOps,
    scope: CoroutineScope,
    private val nameFormat: () -> NameFormat? = { null },
    private val release: (Long) -> Unit = RustNative::release,
) {
    private val bookState = MutableStateFlow<Book>(Book.Loading)
    private val indexState = MutableStateFlow<ContactIndexHandle?>(null)
    private val deepState = MutableStateFlow<DeepIndexHandle?>(null)

    val book: StateFlow<Book> = bookState.asStateFlow()

    /** The name/number/email index of the current [Book.Ready]; null before it and without permission. */
    val index: StateFlow<ContactIndexHandle?> = indexState.asStateFlow()

    /** The detail-search index of the current [Book.Ready]. */
    val deep: StateFlow<DeepIndexHandle?> = deepState.asStateFlow()

    init {
        scope.launch {
            permission
                .map { it == PermissionState.Granted }
                .distinctUntilChanged()
                .flatMapLatest { granted ->
                    if (granted) {
                        // One emission per provider change: the drafts are read when the list changes, not as a second
                        // stream (two streams would emit twice per change and index twice).
                        contacts.all().conflate().map { all ->
                            Book.Ready(all.toImmutableList(), contacts.full().first().toImmutableList()) as Book
                        }
                    } else {
                        flowOf(Book.NoPermission)
                    }
                }.conflate()
                .collect { next ->
                    when (next) {
                        is Book.Ready -> refresh(next)
                        else -> dropIndexes()
                    }
                    bookState.value = next
                }
        }
    }

    private suspend fun refresh(next: Book.Ready) {
        val open = indexState.value
        if (open == null) {
            when (val built = ContactIndexHandle.open(next.contacts, nameFormat(), search, release)) {
                is Outcome.Ok -> indexState.value = built.value
                is Outcome.Err -> Unit
            }
        } else {
            open.replace(next.contacts)
        }
        val fresh = (DeepIndexHandle.open(next.drafts, ops, release) as? Outcome.Ok)?.value
        val old = deepState.value
        deepState.value = fresh
        old?.close()
    }

    private fun dropIndexes() {
        indexState.value?.close()
        indexState.value = null
        deepState.value?.close()
        deepState.value = null
    }
}
