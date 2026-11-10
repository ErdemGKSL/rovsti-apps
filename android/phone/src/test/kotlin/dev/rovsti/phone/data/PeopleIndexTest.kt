package dev.rovsti.phone.data

import dev.rovsti.core.RustNative
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.phone.HostRust
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The contacts index with its real search, T9 and number match. */
class PeopleIndexTest {
    private val host by lazy { HostRust("rovsti-people-index") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val released = AtomicInteger()

    @After fun stop() = scope.cancel()

    private fun person(
        id: Long,
        given: String,
        family: String,
        number: String,
    ) = ContactDraft(id = id, given = given, family = family, numbers = listOf(DraftField(number)))

    private val book =
        listOf(
            person(1, "Ayse", "Yilmaz", "+90 555 111 22 33"),
            person(2, "Mert", "Kaya", "+90 555 999 88 77"),
            person(3, "Deniz", "Aksoy", "+90 555 333 44 55"),
        )

    private val permission = MutableStateFlow<PermissionState>(PermissionState.Granted)

    private fun index(repo: FakeContactsRepository = FakeContactsRepository(book)) =
        PeopleIndex(
            contacts = repo,
            permission = permission,
            search = NativeSearchOps(host.core),
            ops = NativePhoneOps(host.core),
            scope = scope,
            release = {
                released.incrementAndGet()
                RustNative.release(it)
            },
        )

    @Test fun searchFindsByNameAndByNumber() =
        runBlocking {
            val people = index()
            eventually("index built") { people.index.value != null }
            assertEquals(listOf("Ayse Yilmaz"), people.search("ayse").map { it.displayName })
            assertEquals("a part of the number", listOf("Mert Kaya"), people.search("99988").map { it.displayName })
            assertEquals("initials", listOf("Deniz Aksoy"), people.search("da").map { it.displayName })
            assertTrue(people.search("zzz").isEmpty())
        }

    @Test fun t9SpellsNamesFromTheKeypadDigits() =
        runBlocking {
            val people = index()
            eventually("index built") { people.index.value != null }
            // A-Y-S-E is 2-9-7-3 on the keypad; M-E-R-T is 6-3-7-8.
            assertEquals(listOf("Ayse Yilmaz"), people.t9("2973").map { it.name })
            assertEquals(listOf("Mert Kaya"), people.t9("6378").map { it.name })
            val byNumber = people.t9("5553334")
            assertEquals("Deniz Aksoy", byNumber.first().name)
            assertTrue("no digits, no strip", people.t9("").isEmpty())
        }

    @Test fun theNameOrderFollowsTheSetting() =
        runBlocking {
            val people = index()
            eventually("index built") { people.index.value != null }
            // The provider sends both orders; the core picks the one the setting names.
            val ayse = people.search("ayse").single().copy(displayNameAlt = "Yilmaz, Ayse")
            assertEquals("Ayse Yilmaz", people.displayName(ayse, NameFormat.FirstLast))
            assertEquals("Yilmaz, Ayse", people.displayName(ayse, NameFormat.LastFirst))
        }

    @Test fun aNumberFindsItsOwner() =
        runBlocking {
            val people = index()
            eventually("index built") { people.index.value != null }
            assertEquals("Ayse Yilmaz", people.matchNumber("0555 111 22 33")?.displayName)
            assertNull(people.matchNumber("+905550000000"))
        }

    @Test fun aChangedBookIsSwappedIntoTheSameIndex() =
        runBlocking {
            val repo = FakeContactsRepository(book)
            val people = index(repo)
            eventually("index built") { people.index.value != null }
            val handle = people.index.value
            repo.save(person(0, "Selin", "Demir", "+90 555 777 66 55"))
            eventually("selin found") { (people.people.value as? People.Ready)?.contacts?.size == 4 }
            eventually("index follows") { runBlocking { people.search("selin").isNotEmpty() } }
            assertTrue("same handle, replaced in place", people.index.value === handle)
        }

    @Test fun withoutPermissionThereIsNoIndexAndTheHandleIsReleased() =
        runBlocking {
            val people = index()
            eventually("index built") { people.index.value != null }
            permission.value = PermissionState.DeniedForGood
            eventually("dropped") { people.people.value == People.NoPermission }
            assertNull(people.index.value)
            assertEquals(1, released.get())
            assertTrue(people.search("ayse").isEmpty())
            assertTrue(people.t9("2973").isEmpty())
        }

    @Test fun callsPerContactRankTheStrip() =
        runBlocking {
            val people = index()
            eventually("index built") { people.index.value != null }
            // Mert (id 2) was called three times, Ayse once: on the key 5, whoever has more calls goes first.
            val log =
                (1..3).map {
                    logEntry(
                        it.toLong(),
                        "+905559998877",
                        CallLogKind.Outgoing,
                        ago = it.toLong(),
                        contactId = 2,
                    )
                } +
                    logEntry(9, "+905551112233", CallLogKind.Outgoing, ago = 4, contactId = 1)
            people.rankByCalls(log, T0 + 60_000)
            assertEquals("Mert Kaya", people.t9("555").first().name)
        }
}
