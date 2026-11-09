package dev.rovsti.contacts.favorites

import dev.rovsti.contacts.HostRust
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.list.SampleBook
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftEvent
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.platform.calllog.FakeCallLogRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The fixed clock of the Favorites and Groups tests: 16 Sep 2026, 12:26 UTC. */
internal const val NOW = 1_789_600_000_000L
internal const val HOUR_MS = 3_600_000L

/** The provider with lookup keys `k-<id>` and sort keys, like `ContactsContract`. */
internal class KeyedContacts(
    val inner: FakeContactsRepository,
) : ContactsRepository by inner {
    override fun all() =
        inner.all().map { list ->
            list.map(::key).sortedWith(compareBy({ !it.displayName.first().isLetter() }, { it.sortKey }))
        }

    private fun key(c: WireContact) = c.copy(lookupKey = "k-${c.id}", sortKey = SampleBook.sortKeyOf(c.displayName))
}

/** The 40 people of [SampleBook] with the dates, e-mail addresses and groups the Favorites and Groups tests need. */
internal object FavSample {
    fun drafts(): List<ContactDraft> =
        SampleBook.drafts().map { d ->
            when (d.id) {
                1L -> {
                    d.copy(
                        events = listOf(DraftEvent("1990-10-03", EventKind.Birthday)),
                        emails = listOf(mail("ayse@example.org")),
                    )
                }

                2L -> {
                    d.copy(events = listOf(DraftEvent("1996-09-18", EventKind.Birthday)))
                }

                3L -> {
                    d.copy(
                        events = listOf(DraftEvent("2015-11-20", EventKind.Anniversary)),
                        emails = listOf(mail("elif@example.org")),
                    )
                }

                4L -> {
                    d.copy(events = listOf(DraftEvent("--12-24", EventKind.Birthday)))
                }

                else -> {
                    d
                }
            }
        }

    private fun mail(value: String) = DraftField(value, FieldKind.Home)

    val groups =
        listOf(
            Group(10, "Family", members = listOf(1, 3, 5)),
            Group(11, "Work", members = listOf(2, 7)),
            Group(12, "Empty"),
        )

    /** Three outgoing calls to Burak (2) and two to Ahmet (7) in the last days, one to Can (8), three to Ayşe (1). */
    fun calls(): List<CallLogEntry> {
        var id = 0L

        fun call(
            number: String,
            hoursAgo: Long,
            kind: CallLogKind = CallLogKind.Outgoing,
        ) = CallLogEntry(++id, number, number.filter { it.isDigit() }, kind, NOW - hoursAgo * HOUR_MS, durationS = 60)
        return listOf(
            call("0532 123 45 67", 2),
            call("0532 123 45 67", 30),
            call("0532 123 45 67", 100, CallLogKind.Incoming),
            call("0505 222 33 44", 5),
            call("0505 222 33 44", 50),
            call("0506 333 44 55", 8),
            call("+90 555 111 22 33", 3),
            call("+90 555 111 22 33", 40),
            call("+90 555 111 22 33", 60),
        )
    }
}

/** The tab models on the REAL Rust core (files in a temp dir) with the in-memory provider and call log. */
internal class FavRig(
    permission: PermissionState = PermissionState.Granted,
    calls: List<CallLogEntry> = FavSample.calls(),
    groups: List<Group> = FavSample.groups,
) {
    val host = HostRust("rovsti-fav")
    val scope = CoroutineScope(Dispatchers.Default)
    val fake = FakeContactsRepository(FavSample.drafts(), groups)
    val repo = KeyedContacts(fake)
    val callPermission = MutableStateFlow(permission)
    val callLog = FakeCallLogRepository(calls)
    val dir = host.storeDir()
    val store = ContactsStore(host.ops, dir) { NOW }
    val book =
        BookRepository(repo, MutableStateFlow(PermissionState.Granted), NativeSearchOps(Rust.core()), host.ops, scope)
    val model = FavoritesViewModel(book, store, host.ops, repo, callLog, callPermission, clock = { NOW })

    /** A second store on the same directory: what a restart would read. */
    fun reloaded() = ContactsStore(host.ops, dir) { NOW }

    fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(TIMEOUT_MS) { block() } }

    fun stateWhere(predicate: (FavoritesUiState) -> Boolean): FavoritesUiState =
        wait { model.state.first { predicate(it) } }

    fun ready(): FavoritesUiState = stateWhere { it.phase == FavoritesPhase.Ready && it.pickables.isNotEmpty() }

    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
