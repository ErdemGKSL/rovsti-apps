package dev.rovsti.contacts.list

import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.data.WaMarks
import dev.rovsti.contacts.en
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.SelectAction
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeContactsWaOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Select mode (E4.8) on the REAL Rust core and the in-memory provider: entering from a row, the bar words and the
 * actions the core offers, select all, the share text, Copy Numbers, Star, add to a group and a multi-delete whose ONE
 * undo brings every contact back.
 */
class SelectModeViewModelTest {
    private lateinit var repo: FakeContactsRepository
    private lateinit var clipboard: FakeClipboardRepository
    private lateinit var model: ContactsListViewModel

    private class Keyed(
        private val inner: FakeContactsRepository,
    ) : ContactsRepository by inner {
        override fun all() =
            inner.all().map { list ->
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
            val root = Files.createTempDirectory("rovsti-select").toFile().apply { deleteOnExit() }
            val started =
                Rust.init(
                    File(root, "data"),
                    File(root, "cache"),
                    "dev.rovsti.contacts",
                    35,
                    CoroutineScope(Dispatchers.Default),
                )
            assertTrue("Rust.init", started.await() is Outcome.Ok)
            val scope = CoroutineScope(Dispatchers.Default)
            repo = FakeContactsRepository(SampleBook.drafts(), groups = listOf(Group(id = 1, title = "Family")))
            clipboard = FakeClipboardRepository()
            val wa = FakeWaRepository(WaLinkState.None)
            val ops = NativeContactsOps(Rust.core())
            val store = ContactsStore(ops, File(root, "store").apply { mkdirs() }.absolutePath) { 1_789_600_000_000L }
            val provider = Keyed(repo)
            val book =
                BookRepository(
                    provider,
                    MutableStateFlow(PermissionState.Granted),
                    NativeSearchOps(Rust.core()),
                    ops,
                    scope,
                )
            val marks = WaMarks(wa, book.book, NativeContactsWaOps(Rust.core()), scope)
            val shortcuts =
                object : ShortcutsRepository {
                    override fun canPin() = true

                    override fun pinContact(spec: dev.rovsti.core.model.contacts.PinShortcut) = true

                    override fun pinStop(spec: dev.rovsti.core.model.stops.ShortcutAnswer) = false
                }
            model =
                ContactsListViewModel(
                    book = book,
                    store = store,
                    ops = ops,
                    waMarks = marks,
                    contacts = provider,
                    clipboard = clipboard,
                    shortcuts = shortcuts,
                    trash = ContactTrash(provider, store),
                    clock = { 1_789_600_000_000L },
                )
            model.state.first { it.phase == ListPhase.Ready && it.rows.isNotEmpty() }
        }

    private suspend fun stateWhere(predicate: (ContactsListUiState) -> Boolean) =
        withTimeout(TIMEOUT_MS) { model.state.first(predicate) }

    private suspend fun effect(): ContactsListEffect = withTimeout(TIMEOUT_MS) { model.effects.first() }

    @Test fun enteringFromARowSelectsItAndTheCoreWritesTheBar() =
        runBlocking<Unit> {
            assertNull(model.state.value.selectMode)
            model.onAction(ContactsListAction.EnterSelect(7))
            val select = stateWhere { it.selectMode?.selected == setOf(7L) }.selectMode!!
            assertEquals("1 selected", (select.title).en())
            assertTrue(SelectAction.Share in select.actions && SelectAction.Delete in select.actions)
            assertTrue("the phone has a group", SelectAction.AddToGroup in select.actions)
            assertFalse(select.allSelected)
        }

    @Test fun enteringFromTheToolbarSelectsNothingAndOffersNoBulkAction() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(null))
            val select = stateWhere { it.selectMode != null }.selectMode!!
            assertEquals("Select contacts", (select.title).en())
            assertTrue(select.actions.isEmpty())
        }

    @Test fun togglingAndSelectAllUpdateTheTitleAndTheCount() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(null))
            model.onAction(ContactsListAction.ToggleSelected(1))
            model.onAction(ContactsListAction.ToggleSelected(2))
            assertEquals("2 selected", (stateWhere { it.selectMode?.selected?.size == 2 }.selectMode!!.title).en())
            model.onAction(ContactsListAction.ToggleSelected(1))
            assertEquals(setOf(2L), stateWhere { it.selectMode?.selected?.size == 1 }.selectMode!!.selected)
            model.onAction(ContactsListAction.ToggleSelectAll)
            val all = stateWhere { it.selectMode?.allSelected == true }
            assertEquals(40, all.selectMode!!.selected.size)
            assertEquals("40 selected", (all.selectMode!!.title).en())
            model.onAction(ContactsListAction.ToggleSelectAll)
            assertEquals(0, stateWhere { it.selectMode?.selected?.isEmpty() == true }.selectMode!!.selected.size)
            model.onAction(ContactsListAction.ExitSelect)
            assertNull(stateWhere { it.selectMode == null }.selectMode)
        }

    @Test fun shareSendsThePlainTextOfTheSelection() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(1))
            model.onAction(ContactsListAction.ToggleSelected(2))
            stateWhere { it.selectMode?.selected?.size == 2 }
            model.onAction(ContactsListAction.ShareSelected)
            val shared = effect() as ContactsListEffect.Share
            assertTrue(shared.text, SampleBook.people.first { it.id == 1L }.name in shared.text)
            assertTrue(shared.text, SampleBook.people.first { it.id == 2L }.name in shared.text)
        }

    @Test fun exportVcfCarriesTheFileNameAndTheCards() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(1))
            model.onAction(ContactsListAction.ToggleSelected(2))
            stateWhere { it.selectMode?.selected?.size == 2 }
            model.onAction(ContactsListAction.ExportSelected)
            val save = effect() as ContactsListEffect.SaveVcf
            assertEquals(2, save.count)
            assertTrue(save.fileName, save.fileName.endsWith(".vcf"))
            assertEquals(2, Regex("BEGIN:VCARD").findAll(save.text).count())
        }

    @Test fun copyNumbersPutsTheNumbersOnTheClipboard() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(1))
            model.onAction(ContactsListAction.ToggleSelected(2))
            stateWhere { it.selectMode?.selected?.size == 2 }
            model.onAction(ContactsListAction.CopySelectedNumbers)
            val toast = effect() as ContactsListEffect.Toast
            assertTrue(toast.text.en(), toast.text.en().startsWith("Copied"))
            val copied = clipboard.primaryText().orEmpty()
            assertTrue(copied, SampleBook.people.first { it.id == 1L }.number in copied)
        }

    @Test fun copyEmailsSaysSoWhenNoOneHasOne() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(1))
            stateWhere { it.selectMode != null }
            model.onAction(ContactsListAction.CopySelectedEmails)
            val toast = effect() as ContactsListEffect.Toast
            assertTrue(toast.text.en(), toast.text.en().isNotBlank())
        }

    @Test fun starStarsThemAllThenUnstarsWhenAllAreStarred() =
        runBlocking<Unit> {
            // 1 is starred, 2 is not: stars both.
            model.onAction(ContactsListAction.EnterSelect(1))
            model.onAction(ContactsListAction.ToggleSelected(2))
            stateWhere { it.selectMode?.selected?.size == 2 }
            model.onAction(ContactsListAction.StarSelected)
            assertTrue((effect() as ContactsListEffect.Toast).text.en().startsWith("Starred"))
            assertTrue(
                repo
                    .full()
                    .first()
                    .filter { it.id in setOf(1L, 2L) }
                    .all { it.starred },
            )
            assertNull(stateWhere { it.selectMode == null }.selectMode)
            // both starred now: unstars both.
            model.onAction(ContactsListAction.EnterSelect(1))
            model.onAction(ContactsListAction.ToggleSelected(2))
            stateWhere { it.selectMode?.selected?.size == 2 }
            model.onAction(ContactsListAction.StarSelected)
            assertTrue((effect() as ContactsListEffect.Toast).text.en().startsWith("Unstarred"))
            assertTrue(
                repo
                    .full()
                    .first()
                    .filter { it.id in setOf(1L, 2L) }
                    .none { it.starred },
            )
        }

    @Test fun addToGroupOffersTheGroupsAndAddsTheSelection() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(4))
            model.onAction(ContactsListAction.ToggleSelected(5))
            stateWhere { it.selectMode?.selected?.size == 2 }
            model.onAction(ContactsListAction.AskAddToGroup)
            val choose = effect() as ContactsListEffect.ChooseGroup
            assertEquals(listOf("Family"), choose.groups.map { it.title })
            model.onAction(ContactsListAction.AddSelectedToGroup(choose.groups.single().id))
            val toast = effect() as ContactsListEffect.Toast
            assertTrue(toast.text.en(), "Family" in toast.text.en())
            assertEquals(
                setOf(4L, 5L),
                repo
                    .groups()
                    .first()
                    .single()
                    .members
                    .toSet(),
            )
            assertNull(stateWhere { it.selectMode == null }.selectMode)
        }

    @Test fun deleteAsksOnceThenOneUndoRestoresEveryContact() =
        runBlocking<Unit> {
            model.onAction(ContactsListAction.EnterSelect(3))
            model.onAction(ContactsListAction.ToggleSelected(6))
            model.onAction(ContactsListAction.ToggleSelected(9))
            stateWhere { it.selectMode?.selected?.size == 3 }
            model.onAction(ContactsListAction.AskDeleteSelected)
            val ask = effect() as ContactsListEffect.ConfirmDeleteSelected
            assertEquals("Delete 3 contacts?", ask.confirm.title)
            model.onAction(ContactsListAction.DeleteSelected)
            val deleted = effect() as ContactsListEffect.Deleted
            assertEquals("3 contacts deleted.", (deleted.notice).en())
            assertEquals(3, deleted.keys.size)
            val after = stateWhere { s -> s.rows.none { it.id in setOf(3L, 6L, 9L) } && s.selectMode == null }
            assertEquals(37, after.rows.size)
            model.onAction(ContactsListAction.UndoDelete(deleted.keys))
            val restored = effect() as ContactsListEffect.Toast
            assertTrue(restored.text.en(), restored.text.en().isNotBlank())
            val back = stateWhere { it.rows.size == 40 }
            val names = SampleBook.people.filter { it.id in setOf(3L, 6L, 9L) }.map { it.name }
            assertTrue(names.all { name -> back.rows.any { it.name.contains(name.substringBefore(' ')) } })
        }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
