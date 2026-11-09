package dev.rovsti.contacts.list

import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.data.WaMarks
import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeContactsWaOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import dev.rovsti.core.model.contacts.PinShortcut as PinSpec

/**
 * The Contacts list model on the REAL Rust core (the host `librovsti_ffi` that `:core-bridge:cargoBuildHost` builds):
 * sections, fuzzy search, the "In details" section, the empty-state offers, Recently viewed and the row actions. The
 * book is the 40-contact [SampleBook]; the provider is the in-memory fake (with lookup keys, like the real one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsListViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var repo: FakeContactsRepository
    private lateinit var clipboard: FakeClipboardRepository
    private lateinit var store: ContactsStore
    private lateinit var wa: FakeWaRepository
    private lateinit var model: ContactsListViewModel
    private val pinned = mutableListOf<PinSpec>()

    /** The fake provider with lookup keys, like `ContactsContract`. */
    private class Keyed(
        private val inner: FakeContactsRepository,
    ) : ContactsRepository by inner {
        override fun all() =
            inner.all().map { list ->
                // The provider answers in sort-key order, letters before digits.
                list.map(::key).sortedWith(compareBy({ !it.displayName.first().isLetter() }, { it.sortKey }))
            }

        private fun key(c: WireContact) = c.copy(lookupKey = "k-${c.id}", sortKey = SampleBook.sortKeyOf(c.displayName))
    }

    @Before
    fun setUp() =
        runBlocking<Unit> {
            val lib = File(System.getProperty("rovsti.hostLib").orEmpty())
            assumeTrue(
                "host librovsti_ffi not built (run :core-bridge:cargoBuildHost)",
                lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rovsti_ffi") },
            )
            val root = Files.createTempDirectory("rovsti-list").toFile().apply { deleteOnExit() }
            val started =
                Rust.init(
                    File(root, "data"),
                    File(root, "cache"),
                    "dev.rovsti.contacts",
                    35,
                    CoroutineScope(Dispatchers.Default),
                )
            assertTrue("Rust.init", started.await() is Outcome.Ok)
            scope = CoroutineScope(Dispatchers.Default)
            repo = FakeContactsRepository(SampleBook.drafts())
            clipboard = FakeClipboardRepository()
            wa = FakeWaRepository(WaLinkState.None)
            val ops = NativeContactsOps(Rust.core())
            store = ContactsStore(ops, File(root, "store").apply { mkdirs() }.absolutePath) { 1_789_600_000_000L }
            val book =
                BookRepository(
                    Keyed(repo),
                    MutableStateFlow(PermissionState.Granted),
                    NativeSearchOps(Rust.core()),
                    ops,
                    scope,
                )
            val marks = WaMarks(wa, book.book, NativeContactsWaOps(Rust.core()), scope)
            val shortcuts =
                object : ShortcutsRepository {
                    override fun canPin() = true

                    override fun pinContact(spec: PinSpec): Boolean = pinned.add(spec)

                    override fun pinStop(spec: dev.rovsti.core.model.stops.ShortcutAnswer) = false
                }
            model =
                ContactsListViewModel(
                    book = book,
                    store = store,
                    ops = ops,
                    waMarks = marks,
                    contacts = Keyed(repo),
                    clipboard = clipboard,
                    shortcuts = shortcuts,
                    clock = { 1_789_600_000_000L },
                )
            // The state only runs while somebody collects it.
            model.state.first { it.phase == ListPhase.Ready && it.rows.isNotEmpty() }
        }

    private suspend fun stateWhere(predicate: (ContactsListUiState) -> Boolean) =
        withTimeout(TIMEOUT_MS) { model.state.first(predicate) }

    private suspend fun search(text: String): ContactsListUiState {
        model.onAction(ContactsListAction.QueryChanged(text))
        return stateWhere { it.query == text }
    }

    private suspend fun effect(): ContactsListEffect = withTimeout(TIMEOUT_MS) { model.effects.first() }

    @Test fun theBookIsSectionedByLetterWithOneHeaderPerLetter() =
        runBlocking<Unit> {
            val state = stateWhere { it.rows.size == 40 }
            val headers = state.rows.filter { it.header }.map { it.letter }
            assertEquals("each letter once", headers.distinct(), headers)
            assertEquals("A", state.rows.first().letter)
            assertEquals("the emergency number sorts under #", "#", state.rows.last().letter)
            assertEquals(headers.map { it.single() }, state.scrubberLetters.toList())
            assertTrue(state.showScrubber)
            // The rail scrubs over headers and rows: the header of "B" sits after A's header and A's rows.
            val aRows = state.rows.count { it.letter == "A" }
            assertEquals(0, state.sectionIndex['A'])
            assertEquals(1 + aRows, state.sectionIndex['B'])
            assertEquals(40 + headers.size, state.scrubItems)
        }

    @Test fun searchingByInitialsFindsAdaLovelace() =
        runBlocking<Unit> {
            val state = search("al")
            assertTrue("Ada Lovelace by her initials", state.rows.any { it.name == "Ada Lovelace" })
            assertTrue("Alper by name", state.rows.any { it.name == "Alper Tekin" })
            assertFalse("no sections while searching", state.rows.any { it.header })
            assertFalse("no rail while searching", state.showScrubber)
            assertTrue(state.deepRows.isEmpty())
        }

    @Test fun aNumberFragmentShowsTheMatchingNumberAsTheSecondLine() =
        runBlocking<Unit> {
            val state = search("0555")
            val gokhan = state.rows.single { it.name == "Gökhan Işık" }
            assertEquals("0555 180 90 10", gokhan.secondary)
            assertEquals("0555 180 90 10", gokhan.secondary)
        }

    @Test fun contactsFoundOnlyByTheirDetailsGoUnderInDetails() =
        runBlocking<Unit> {
            val state = search("acme")
            assertTrue("nobody is named Acme", state.rows.isEmpty())
            assertEquals("In details", state.deepTitle)
            val hit = state.deepRows.single()
            assertEquals("Zeynep Arslan", hit.name)
            assertTrue(hit.secondary.orEmpty().contains("Acme"))
            assertEquals("the page is not empty", EmptyKind.None, state.emptyKind)
        }

    @Test fun noResultsOffersCreateAndAddForANumber() =
        runBlocking<Unit> {
            val state = search("0599 111 22 33")
            assertEquals(EmptyKind.NoResults, state.emptyKind)
            assertEquals("0599 111 22 33", state.queryEcho)
            val seed = state.offer.create!!
            assertEquals("05991112233", seed.number)
            assertNotNull("a number can be added to a contact", state.offer.addition)
            assertEquals("the picker lists the whole book", 40, state.pickables.size)
        }

    @Test fun noResultsForANameOffersCreateOnly() =
        runBlocking<Unit> {
            val state = search("Zzyzx Qwerty")
            assertEquals(EmptyKind.NoResults, state.emptyKind)
            assertEquals("Zzyzx Qwerty", state.offer.create?.name)
            assertNull(state.offer.addition)
            assertTrue(state.pickables.isEmpty())
        }

    @Test fun openingACardRecordsItAndRecentlyViewedClears() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.Open(11))
            assertEquals(ContactsListEffect.OpenCard(11), effect())
            val state = stateWhere { it.viewed.isNotEmpty() }
            assertEquals(listOf("Ada Lovelace"), state.viewed.map { it.name })
            assertEquals(listOf("k-11"), store.viewed)
            // Searching hides the section; clearing asks first, then empties it.
            model.onAction(ContactsListAction.AskClearViewed)
            val ask = effect() as ContactsListEffect.ConfirmClearViewed
            assertTrue(ask.confirm.title.isNotBlank())
            model.onAction(ContactsListAction.ClearViewed)
            assertEquals(ContactsListEffect.Toast("Recently viewed cleared"), effect())
            stateWhere { it.viewed.isEmpty() }
            assertEquals(emptyList<String>(), store.viewed)
        }

    @Test fun theLongPressMenuComesFromTheQuickActions() =
        runBlocking<Unit> {
            val state = stateWhere { it.quick.isNotEmpty() }
            val plain = state.rows.first { !it.starred && !it.whatsapp }
            val ids = state.quick.getValue(plain.menuKey).map { it.id }
            assertEquals(listOf("call", "message", "copy_number", "share", "star", "add_to_home"), ids)
            val starred = state.rows.first { it.starred && !it.whatsapp }
            assertTrue("unstar", state.quick.getValue(starred.menuKey).any { it.id == "unstar" })
        }

    @Test fun callMessageCopyAndShareUseTheDefaultNumber() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.Quick(2, "call"))
            assertEquals(ContactsListEffect.Dial("0532 123 45 67"), effect())
            model.onAction(ContactsListAction.Quick(2, "message"))
            assertEquals(ContactsListEffect.Sms("0532 123 45 67"), effect())
            model.onAction(ContactsListAction.Quick(2, "copy_number"))
            assertEquals(ContactsListEffect.Toast("Number copied."), effect())
            assertEquals(listOf("0532 123 45 67" to false), clipboard.copies)
            model.onAction(ContactsListAction.Quick(2, "share"))
            val share = effect() as ContactsListEffect.Share
            assertTrue(share.text.contains("Burak"))
        }

    @Test fun twoNumbersAndNoDefaultOpenTheCardInsteadOfGuessing() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.Quick(3, "call"))
            assertEquals(ContactsListEffect.OpenCard(3), effect())
            assertEquals(ContactsListEffect.Toast("Choose a number to call."), effect())
        }

    @Test fun starAndAddToHomeActOnTheProviderAndTheLauncher() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.Quick(2, "star"))
            withTimeout(TIMEOUT_MS) { repo.all().first { list -> list.first { it.id == 2L }.starred } }
            model.onAction(ContactsListAction.Quick(2, "add_to_home"))
            assertEquals(ContactsListEffect.Toast("Added to Home screen"), effect())
            assertEquals(2L, pinned.single().contactId)
        }

    @Test fun whatsAppMarksTheRowAndHandsOffTheChat() =
        runBlocking<Unit> {
            wa.setState(WaLinkState.Connected(InstanceStatus("inst", InstanceState.Connected)))
            wa.setChats(listOf(RichChat(jid = "905551112233@s.whatsapp.net", unread = 0, isGroup = false)))
            // Only international numbers match: Ayşe's +90 555 111 22 33 is the chat user.
            val state = stateWhere { s -> s.rows.any { it.id == 1L && it.whatsapp } }
            assertEquals(1, state.rows.count { it.whatsapp })
            model.onAction(ContactsListAction.Quick(1, "whatsapp"))
            assertEquals(ContactsListEffect.WhatsApp("905551112233@s.whatsapp.net", "Ayşe Yılmaz"), effect())
            model.onAction(ContactsListAction.Quick(2, "whatsapp"))
            assertEquals(ContactsListEffect.Toast("This contact isn't on WhatsApp."), effect())
        }

    @Test fun selectIsRecordedForTheSelectStory() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(7))
            assertEquals(SelectMode(7), stateWhere { it.selectMode != null }.selectMode)
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
