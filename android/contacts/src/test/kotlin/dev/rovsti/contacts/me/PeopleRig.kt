package dev.rovsti.contacts.me

import dev.rovsti.contacts.HostRust
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.data.RecentTracker
import dev.rovsti.contacts.emergency.EmergencyViewModel
import dev.rovsti.contacts.favorites.FavSample
import dev.rovsti.contacts.favorites.NOW
import dev.rovsti.contacts.recent.RecentListsViewModel
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal const val DAY_MS = 86_400_000L

/**
 * The provider of the E4.14 tests: lookup keys `k-<id>` and sort keys like `ContactsContract`, and an "updated" time per
 * contact from [updated] (the in-memory fake has none).
 */
internal class StampedContacts(
    val inner: FakeContactsRepository,
    private val updated: Map<Long, Long>,
) : ContactsRepository by inner {
    override fun all(): Flow<List<WireContact>> =
        inner.all().map { list ->
            list
                .map { c ->
                    c.copy(
                        lookupKey = "k-${c.id}",
                        sortKey =
                            dev.rovsti.contacts.list.SampleBook
                                .sortKeyOf(c.displayName),
                        updatedMs = updated[c.id],
                    )
                }.sortedWith(compareBy({ !it.displayName.first().isLetter() }, { it.sortKey }))
        }
}

/**
 * My card, Emergency Contacts and Recent on the REAL Rust core (files in a temp directory) over the 40 people of the
 * Favorites tests. [now] is the clock all three read; tests move it to age marks.
 */
internal class PeopleRig(
    permission: PermissionState = PermissionState.Granted,
    updated: Map<Long, Long> = DEFAULT_UPDATED,
) {
    val host = HostRust("rovsti-people")
    val scope = CoroutineScope(Dispatchers.Default)
    val fake = FakeContactsRepository(FavSample.drafts(), FavSample.groups)
    val repo = StampedContacts(fake, updated)
    val dir = host.storeDir()
    var now = NOW
    val store = ContactsStore(host.ops, dir) { now }
    val book =
        BookRepository(repo, MutableStateFlow(permission), NativeSearchOps(Rust.core()), host.ops, scope)
    val me = MeViewModel(book, store, host.ops)
    val emergency = EmergencyViewModel(book, store, host.ops)
    val recent = RecentListsViewModel(book, store, host.ops, clock = { now })
    val tracker = RecentTracker(book, store, host.ops, scope) { now }

    /** A second store on the same directory: what a restart would read. */
    fun reloaded() = ContactsStore(host.ops, dir) { now }

    fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(TIMEOUT_MS) { block() } }

    fun waitBook() = wait { book.book.first { it is dev.rovsti.contacts.data.Book.Ready } }

    /** Adds a person to the provider and answers the id. */
    fun add(
        given: String,
        family: String,
        number: String? = null,
    ): Long {
        val draft =
            ContactDraft(
                given = given,
                family = family,
                numbers =
                    listOfNotNull(
                        number?.let {
                            dev.rovsti.core.model.contacts.DraftField(
                                it,
                                dev.rovsti.core.model.contacts.FieldKind.Mobile,
                            )
                        },
                    ),
            )
        val saved = wait { fake.save(draft) } as dev.rovsti.platform.contacts.ContactResult.Ok<Long>
        return saved.value
    }

    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        const val TIMEOUT_MS = 20_000L

        /**
         * Burak (2) edited 3 hours ago, Elif (3) 2 days ago, Ahmet (7) on 12 Aug (35 days: outside the 30-day window),
         * Can (8) 9 days ago.
         */
        val DEFAULT_UPDATED: Map<Long, Long> =
            mapOf(
                2L to NOW - 3 * 3_600_000L,
                3L to NOW - 2 * DAY_MS,
                7L to NOW - 35 * DAY_MS,
                8L to NOW - 9 * DAY_MS,
            )
    }
}
