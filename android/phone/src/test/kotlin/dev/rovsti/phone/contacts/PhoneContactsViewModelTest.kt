package dev.rovsti.phone.contacts

import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.favorites.PeopleRig
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.5 Contacts tab: sections and rail letters from `contacts.view`, search over the people index, no permission. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneContactsViewModelTest {
    private val host by lazy { HostRust("rovsti-people-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() =
        dev.rovsti.phone.UnconfinedMain
            .install()

    @After fun stop() = scope.cancel()

    private suspend fun rig(permission: PermissionState = PermissionState.Granted) =
        PeopleRig(host, scope, permission = permission).also { it.ready() }

    private fun PhoneContactsViewModel.watch() = state.onEach { }.launchIn(scope)

    private suspend fun PhoneContactsViewModel.where(check: (PeopleUi) -> Boolean): PeopleUi =
        withTimeout(10_000) { state.first(check) }

    @Test fun peopleAreSortedIntoLetterSectionsWithARail() =
        runBlocking<Unit> {
            val model = rig().contacts().also { it.watch() }
            val ui = model.where { it.phase == PeoplePhase.Ready && it.rows.size == ALL }
            val names = ui.rows.map { it.name }
            assertEquals("sorted by first name", names.sorted(), names)
            val headers = ui.rows.filter { it.header }.map { it.letter }
            assertEquals("one header per letter", headers.distinct(), headers)
            assertEquals(names.map { it.first().toString() }.distinct(), headers)
            assertTrue("the rail shows when the list is long enough", ui.showRail)
            assertTrue(ui.letters.containsAll(headers.map { it.first() }))
            // The rail's index points at each header among the scrubbable items: headers and rows in list order.
            assertEquals(0, ui.sectionIndex['A'])
            val inA = ui.rows.takeWhile { it.letter == "A" }.size
            assertEquals("the A header, its rows, then the next header", 1 + inA, ui.sectionIndex[headers[1].first()])
            assertEquals(headers.map { it.first() }, ui.sectionIndex.keys.toList())
            assertEquals(ui.rows.size + headers.size, ui.scrubItems)
            assertTrue("starred people are marked", ui.rows.first { it.name == "Ayse Yilmaz" }.starred)
        }

    @Test fun theSortSettingChangesTheOrder() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.contacts().also { it.watch() }
            model.where { it.rows.size == ALL }
            r.store.updatePrefs { it.copy(settings = it.settings.copy(sortBy = SortBy.LastName)) }
            // Last-name order starts with Acar (Nehir) then Aksoy (Deniz).
            val ui = model.where { it.rows.firstOrNull()?.name == "Nehir Acar" }
            assertEquals("Deniz Aksoy", ui.rows[1].name)
        }

    @Test fun searchKeepsTheIndexRankingWithoutSectionsOrRail() =
        runBlocking {
            val model = rig().contacts().also { it.watch() }
            model.where { it.rows.size == ALL }
            model.onAction(PeopleAction.SetQuery("ay"))
            val ui = model.where { it.query == "ay" && it.rows.size < ALL }
            assertTrue(ui.rows.map { it.name }.containsAll(listOf("Ayse Yilmaz", "Kerem Aydin")))
            assertTrue("no sections while searching", ui.rows.none { it.header })
            assertFalse("no rail while searching", ui.showRail)
            assertEquals(PeopleEmpty.None, ui.empty)
        }

    @Test fun searchFindsByNumberAndSaysWhenNothingMatches() =
        runBlocking {
            val model = rig().contacts().also { it.watch() }
            model.where { it.rows.size == ALL }
            model.onAction(PeopleAction.SetQuery("99988"))
            assertEquals(
                listOf("Mert Kaya"),
                model.where { it.query == "99988" && it.rows.size == 1 }.rows.map { it.name },
            )
            model.onAction(PeopleAction.SetQuery("zzzz"))
            val none = model.where { it.query == "zzzz" && it.rows.isEmpty() }
            assertEquals(PeopleEmpty.NoResults, none.empty)
            model.onAction(PeopleAction.SetQuery(""))
            assertEquals(ALL, model.where { it.query.isEmpty() && it.rows.size == ALL }.rows.size)
        }

    @Test fun aRowOpensItsContact() =
        runBlocking {
            val model = rig().contacts().also { it.watch() }
            model.where { it.rows.size == ALL }
            val contact = model.contact(2)
            assertNotNull(contact)
            assertEquals("Mert Kaya", contact!!.displayName)
        }

    @Test fun withoutPermissionTheTabAsksForIt() =
        runBlocking {
            val model = rig(PermissionState.DeniedForGood).contacts().also { it.watch() }
            assertEquals(PeoplePhase.NoPermission, model.where { it.phase != PeoplePhase.Loading }.phase)
        }

    private companion object {
        const val ALL = 25
    }
}
