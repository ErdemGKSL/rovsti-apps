package dev.rosty.phone.data

import dev.rosty.core.model.contacts.ContactDraft
import dev.rosty.core.model.contacts.DraftField
import dev.rosty.core.model.phone.CallDirection
import dev.rosty.core.model.phone.CallLogKind
import dev.rosty.core.model.phone.CallPhase
import dev.rosty.core.model.phone.DateOrder
import dev.rosty.core.model.phone.ExtraCall
import dev.rosty.core.model.phone.LocaleTime
import dev.rosty.core.model.phone.TimeFormat
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.ops.NativePhoneOps
import dev.rosty.core.ops.NativeSearchOps
import dev.rosty.phone.HostRust
import dev.rosty.phone.wa.dialPeer
import dev.rosty.platform.calllog.FakeCallLogRepository
import dev.rosty.platform.contacts.FakeContactsRepository
import dev.rosty.platform.identity.CallerIds
import dev.rosty.platform.identity.DeviceContactsSource
import dev.rosty.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Recents, favorites, statistics and the WhatsApp calls name a number by the current contacts (F28, F29), through the
 * real core's number book: the owner's report was dozens of saved contacts showing as bare numbers.
 */
class LiveNamesTest {
    private val host by lazy { HostRust("rosty-live-names") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private fun person(
        id: Long,
        given: String,
        vararg numbers: String,
        starred: Boolean = false,
    ) = ContactDraft(id = id, given = given, numbers = numbers.map { DraftField(it) }, starred = starred)

    private val repo =
        FakeContactsRepository(
            listOf(
                // Saved the national way, as most people in Turkey save numbers.
                person(1, "Ayşe", "0532 123 45 67"),
                person(2, "Mert", "+90 555 999 88 77"),
                person(3, "Bank", "444 0 123"),
            ),
        )

    private val people =
        PeopleIndex(
            contacts = repo,
            permission = MutableStateFlow(PermissionState.Granted),
            search = NativeSearchOps(host.core),
            ops = NativePhoneOps(host.core),
            scope = scope,
        )

    private val ids =
        CallerIds(listOf(DeviceContactsSource(people, e164 = { null }, homeCc = { "90" })))

    private suspend fun ready() = eventually("index built") { people.ready }

    @Test fun everyFormOfASavedNumberIsTheContact() =
        runBlocking {
            ready()
            val log =
                listOf(
                    "+905321234567",
                    "05321234567",
                    "5321234567",
                    "905321234567",
                    "0090 532 123 45 67",
                    "0532-123-45-67",
                ).mapIndexed { i, n -> logEntry(i.toLong(), n, CallLogKind.Incoming) }
            val named = ids.liveEntries(log)
            named.forEach {
                assertEquals(it.numberRaw, "Ayşe", it.cachedName)
                assertEquals(it.numberRaw, 1L, it.contactId)
            }
        }

    @Test fun theCachedNameNamesOnlyAStranger() =
        runBlocking {
            ready()
            val log =
                listOf(
                    // A renamed contact: the log cached the old name and a link to the contact.
                    logEntry(1, "+905559998877", CallLogKind.Outgoing, name = "Mert eski", contactId = 2),
                    // A number nobody owns any more: the cached name stays, the stale link goes.
                    logEntry(2, "+905550001122", CallLogKind.Missed, name = "Silinen", contactId = 9),
                    // Withheld and short codes match nobody by accident.
                    logEntry(3, "-2", CallLogKind.Incoming),
                    logEntry(4, "4440123", CallLogKind.Outgoing),
                    logEntry(5, "123", CallLogKind.Outgoing),
                )
            val named = ids.liveEntries(log)
            assertEquals("Mert", named[0].cachedName)
            assertEquals("Silinen", named[1].cachedName)
            assertNull(named[1].contactId)
            assertNull(named[2].cachedName)
            assertNull(named[2].contactId)
            assertEquals("Bank", named[3].cachedName)
            assertNull(named[4].contactId)
        }

    @Test fun aRenameOrADeleteShowsAtTheNextResolve() =
        runBlocking {
            ready()
            val log = listOf(logEntry(1, "05321234567", CallLogKind.Incoming))
            assertEquals("Ayşe", ids.liveEntries(log)[0].cachedName)
            val before = people.revision.value
            repo.save(person(1, "Ayşe Kaya", "0532 123 45 67"))
            eventually("rebuilt") { people.revision.value > before }
            assertEquals("Ayşe Kaya", ids.liveEntries(log)[0].cachedName)
            val again = people.revision.value
            repo.delete(1)
            eventually("rebuilt") { people.revision.value > again }
            assertNull(ids.liveEntries(log)[0].contactId)
        }

    @Test fun twoContactsWithOneNumberAlwaysNameTheStarredOne() =
        runBlocking {
            repo.save(person(0, "Ayşe iş", "+90 532 123 45 67", starred = true))
            ready()
            eventually("both read") { (people.people.value as? People.Ready)?.contacts?.size == 4 }
            val log = listOf(logEntry(1, "05321234567", CallLogKind.Incoming))
            assertEquals("Ayşe iş", ids.liveEntries(log)[0].cachedName)
        }

    @Test fun whatsappCallsNameTheContactThenThePushName() =
        runBlocking {
            ready()

            fun call(
                peer: String,
                push: String?,
            ) = CallSummary("c-$peer", peer, push, CallDirection.In, CallPhase.Ringing, false, T0)
            val calls =
                ids.liveCalls(
                    listOf(
                        call("905321234567:3@s.whatsapp.net", "Ayse WA"),
                        call("905550001122@s.whatsapp.net", "Can"),
                        call("4242@lid", "Lid kişi"),
                    ),
                )
            assertEquals(listOf("Ayşe", "Can", "Lid kişi"), calls.map { it.peerName })
            // A caller the gateway names by a LID, with the number next to it: the contact that owns the number.
            val byLid =
                ids.liveCalls(
                    listOf(
                        call("4242@lid", "Ayse WA").copy(peerAlt = "905321234567@s.whatsapp.net"),
                        // The digits of a LID are not a number: Ayşe's digits under `@lid` name nobody.
                        call("905321234567@lid", "Lid kişi"),
                    ),
                )
            assertEquals(listOf("Ayşe", "Lid kişi"), byLid.map { it.peerName })
            assertEquals("905321234567@s.whatsapp.net", byLid[0].dialPeer())
            assertEquals("905321234567@lid", byLid[1].dialPeer())
            assertEquals("+905321234567", RecentsRepository.dialNumber(byLid[0]))
            val extras =
                ids.liveExtras(
                    listOf(
                        ExtraCall("+905321234567", T0, 10, false, false, "Ayse WA", "c1"),
                        ExtraCall("+905550001122", T0, 10, false, false, "Can", "c2"),
                    ),
                )
            assertEquals(listOf("Ayşe", "Can"), extras.map { it.label })
        }

    @Test fun recentsNameRowsByTheContactsAndFollowAnEdit() =
        runBlocking {
            ready()
            val log =
                listOf(
                    // The dialer that wrote these had no contacts: no cached name, no link.
                    logEntry(1, "05321234567", CallLogKind.Missed, ago = 5),
                    logEntry(2, "+905559998877", CallLogKind.Outgoing, ago = 60, name = "Mert eski", contactId = 2),
                    logEntry(3, "+905550001122", CallLogKind.Incoming, ago = 90, name = "Eski numara"),
                )
            val recents =
                RecentsRepository(
                    callLog = FakeCallLogRepository(log),
                    ops = NativePhoneOps(host.core),
                    scope = scope,
                    locale = { LocaleTime(0, TimeFormat(hour12 = false, dateOrder = DateOrder.DayMonthYear)) },
                    clock = { T0 + 60_000 },
                    windowMs = 12,
                    ids = ids,
                    idsChanged = people.revision,
                ).also { it.start() }
            eventually("named rows") {
                recents.model.value.views
                    .firstOrNull()
                    ?.title == "Ayşe"
            }
            assertEquals(
                listOf("Ayşe", "Mert", "Eski numara"),
                recents.model.value.views
                    .map { it.title },
            )
            assertEquals("Mert eski", recents.savedNameOf(2))
            assertEquals("Mert", recents.callEntries.single { it.id == 2L }.cachedName)
            repo.save(person(1, "Ayşe Kaya", "0532 123 45 67"))
            eventually("renamed row") {
                recents.model.value.views
                    .firstOrNull()
                    ?.title == "Ayşe Kaya"
            }
        }
}
