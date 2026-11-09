package dev.rovsti.contacts.dupes

import dev.rovsti.contacts.HostRust
import dev.rovsti.contacts.card.SampleCards
import dev.rovsti.contacts.cleanup.CleanupViewModel
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A 12-contact address book for the Duplicates and Clean up tests and shots: three groups of look-alikes (one number and
 * one e-mail make a group of THREE, a number written two ways, the same name without numbers), two nameless contacts
 * and three that stand alone. [photos] are the ids the provider says have a picture.
 */
internal object SampleTidy {
    val photos = setOf(1L, 4L)

    fun drafts(): List<ContactDraft> =
        listOf(
            ContactDraft(
                id = 1,
                given = "Ayşe",
                family = "Yılmaz",
                numbers = listOf(DraftField("+90 555 111 22 33", FieldKind.Mobile)),
                emails = listOf(DraftField("ayse@acme.example", FieldKind.Work)),
                groups = listOf(1),
                starred = true,
            ),
            ContactDraft(
                id = 2,
                given = "A.",
                family = "Yılmaz",
                numbers =
                    listOf(
                        DraftField("0555 111 22 33", FieldKind.Mobile),
                        DraftField("0212 000 00 01", FieldKind.Work),
                    ),
            ),
            ContactDraft(
                id = 3,
                given = "Ayşe",
                org = "Acme Yazılım",
                emails = listOf(DraftField("ayse@acme.example", FieldKind.Work)),
            ),
            ContactDraft(
                id = 4,
                given = "Burak",
                family = "Demir",
                numbers = listOf(DraftField("0532 123 45 67", FieldKind.Mobile)),
                groups = listOf(1),
            ),
            ContactDraft(
                id = 5,
                given = "Burak",
                family = "D.",
                numbers = listOf(DraftField("+90 532 123 45 67", FieldKind.Mobile)),
            ),
            ContactDraft(id = 6, given = "Elif", family = "Kaya"),
            ContactDraft(id = 7, given = "Elif", family = "Kaya", org = "Kaya Ltd"),
            ContactDraft(
                id = 8,
                given = "Can",
                family = "Aydın",
                numbers = listOf(DraftField("0506 333 44 55", FieldKind.Mobile)),
            ),
            ContactDraft(
                id = 9,
                given = "Deniz",
                family = "Korkmaz",
                numbers = listOf(DraftField("0507 444 55 66", FieldKind.Mobile)),
            ),
            ContactDraft(
                id = 10,
                given = "Zeynep",
                family = "Arslan",
                org = "Acme Yazılım",
                numbers = listOf(DraftField("+1 415 555 0132", FieldKind.Mobile)),
            ),
            ContactDraft(id = 11, numbers = listOf(DraftField("0501 111 11 11", FieldKind.Mobile))),
            ContactDraft(id = 12, emails = listOf(DraftField("zed@example.test", FieldKind.Home))),
        )
}

/** The provider with the keys the search index wants and a picture for some contacts. */
private class Photoed(
    private val inner: FakeContactsRepository,
    private val photos: Set<Long>,
) : ContactsRepository by inner {
    override fun all() =
        inner.all().map { list ->
            list.map { c: WireContact ->
                c.copy(
                    lookupKey = "k-${c.id}",
                    sortKey = c.displayName.lowercase(),
                    photoUri = if (c.id in photos) "content://contacts/photo/${c.id}" else null,
                )
            }
        }
}

/**
 * Duplicates and Clean up on the REAL Rust core (files in a temp data directory) over the in-memory provider and the
 * real [BookRepository].
 */
internal class TidyRig(
    drafts: List<ContactDraft> = SampleTidy.drafts(),
    photos: Set<Long> = SampleTidy.photos,
) {
    val host = HostRust("rovsti-tidy")
    val scope = CoroutineScope(Dispatchers.Default)
    val repo = FakeContactsRepository(drafts, listOf(Group(id = 1, title = "Family")))
    private val provider: ContactsRepository = Photoed(repo, photos)
    val store = ContactsStore(host.ops, host.storeDir()) { SampleCards.NOW }
    val trash = ContactTrash(provider, store)
    val book =
        BookRepository(
            provider,
            MutableStateFlow(PermissionState.Granted),
            NativeSearchOps(Rust.core()),
            host.ops,
            scope,
        )
    val clipboard = FakeClipboardRepository()

    fun dupes() = DupesViewModel(book, provider, host.ops, trash, scope)

    fun cleanup() = CleanupViewModel(book, provider, host.ops, trash, clipboard, scope, clock = { SampleCards.NOW })

    fun contacts(): List<ContactDraft> = runBlocking { repo.full().first() }

    fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(TIMEOUT_MS) { block() } }

    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
