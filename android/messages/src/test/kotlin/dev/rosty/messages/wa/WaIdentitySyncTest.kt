package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMapping
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaSyncCatchUpRequest
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncObserveRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncRekeyed
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.ADA_LID
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync follows identity (E6-WA R1): the gateway's LID and number pairs come before the chat list, an old
 * gateway is asked once, every answer that names a merge is followed, and what reaches the app outside the store is
 * named by the store's keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaIdentitySyncTest {
    private val fake = FakeWaSyncOps()
    private val remote = FakeWaRemote()

    /** What the runner told its owner, in order: `rekeyed <from> <into>` and `changed <chats>`. */
    private val told = mutableListOf<String>()

    private suspend fun runner(): Pair<WaSyncStore, WaSyncRunner> {
        val store = WaSyncStore.open("inst1", ops = fake, release = {}).getOrThrow()
        val runner =
            WaSyncRunner(
                store,
                remote,
                { NOW },
                onChange = { told += "changed ${it?.sorted()}" },
                onRekeyed = { steps -> steps.forEach { told += "rekeyed ${it.fromKey} ${it.intoKey}" } },
            )
        return store to runner
    }

    private fun pair(
        lid: String = ADA_LID,
        pn: String = ADA,
    ) = IdentityMapping(lid, pn, "usync", NOW - 1_000)

    /** Two pages: the pair of Ada, then an empty one that ends the catch-up. */
    private fun adaIsListed() {
        remote.mappings =
            mapOf(
                null to IdentityMappingsAnswer(supported = true, items = listOf(pair()), next = "41"),
                "41" to IdentityMappingsAnswer(supported = true),
            )
    }

    private suspend fun WaSyncStore.ids(chat: String): List<String> = thread(chat, 50).getOrThrow().items.map { it.id }

    @Test fun thePairsAndTheOwnIdsComeBeforeTheChatList() =
        runTest {
            fake.owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            fake.put(ADA_LID, "L1", 1_000, "under the LID")
            fake.put(ADA, "P1", 2_000, "under the number")
            adaIsListed()
            remote.ownStatus =
                InstanceStatus("inst1", InstanceState.Connected, MERT, "Me", ownLid = "777000000000009@lid")
            remote.chatList = listOf(RichChat(ADA, "Ada", null, 0, false))
            val (store, runner) = runner()

            assertTrue(runner.catchUp())

            // Page after page from the stored cursor, then the account's own ids, and only then the chat list.
            assertEquals(listOf("identity", "identity", "status", "chats"), remote.order)
            assertEquals(listOf(null, "41"), remote.mappingCalls)
            // The two chats of Ada were one before the list was stored, and the merge was followed first.
            assertEquals(listOf("P1", "L1"), store.ids(ADA))
            assertEquals("rekeyed $ADA_LID $ADA", told.first())
            val writes = fake.writes.map { it::class }
            assertTrue(
                "pairs, own ids, then the chat list: $writes",
                writes.indexOf(WaSyncCatchUpRequest::class) < writes.indexOf(WaSyncObserveRequest::class) &&
                    writes.indexOf(WaSyncObserveRequest::class) < writes.indexOf(WaSyncStoreChatsRequest::class),
            )
            val observed = fake.writes.filterIsInstance<WaSyncObserveRequest>().single()
            assertEquals(MERT to "777000000000009@lid", observed.status?.ownJid to observed.status?.ownLid)
        }

    @Test fun aMergeAloneFetchesTheChatListAgain() =
        runTest {
            // The plan owes nothing, but the catch-up moved rows: the list is refreshed all the same.
            fake.owed = WaSyncPlan(afterSeq = 7, refreshChats = false, fetch = emptyList())
            fake.put(ADA_LID, "L1", 1_000, "under the LID")
            adaIsListed()
            val (_, runner) = runner()

            assertTrue(runner.catchUp())
            assertEquals(1, remote.chatCalls)

            // Nothing new the next time: the cursor is kept, the list is not asked for again.
            remote.order.clear()
            assertTrue(runner.catchUp())
            assertEquals(listOf("identity"), remote.order)
            assertEquals("41", remote.mappingCalls.last())
        }

    @Test fun anOldGatewayIsAskedOnceAndTheSyncGoesOn() =
        runTest {
            fake.owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            remote.mappings = null
            remote.chatList = listOf(RichChat(ADA, "Ada", null, 0, false))
            val (_, runner) = runner()

            assertTrue(runner.catchUp())
            assertEquals(listOf("identity", "chats"), remote.order)

            // A reconnect and a resync later: the gateway that lists no pairs is not asked again.
            assertTrue(runner.catchUp())
            assertTrue(runner.refresh())
            assertEquals(1, remote.mappingCalls.size)
            assertEquals(2, remote.chatCalls)
            assertTrue(fake.writes.none { it is WaSyncCatchUpRequest })
            assertTrue(told.none { it.startsWith("rekeyed") })
        }

    @Test fun aRefreshCatchesUpOnThePairsFirst() =
        runTest {
            adaIsListed()
            fake.put(ADA_LID, "L1", 1_000, "under the LID")
            val (store, runner) = runner()

            assertTrue(runner.refresh())

            assertEquals(listOf("identity", "identity", "chats"), remote.order)
            assertEquals(listOf("L1"), store.ids(ADA))
        }

    @Test fun theOwnIdsAreHandedInOncePerOpenStore() =
        runTest {
            remote.ownStatus = InstanceStatus("inst1", InstanceState.Connected, MERT, "Me")
            val (_, runner) = runner()
            runner.catchUp()
            runner.catchUp()
            assertEquals(1, fake.writes.count { it is WaSyncObserveRequest })
            assertEquals(1, remote.order.count { it == "status" })
        }

    @Test fun aGatewayThatCannotBeReachedForThePairsDoesNotStopTheRest() =
        runTest {
            fake.owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            remote.down = true
            val (_, runner) = runner()
            assertFalse(runner.catchUp())
            // Back again: the pairs are asked from the start, then the list.
            remote.down = false
            adaIsListed()
            assertTrue(runner.catchUp())
            assertEquals(listOf("identity", "identity", "chats"), remote.order)
        }

    @Test fun aMergeAnEventCausesIsFollowedBeforeTheReadersAreTold() =
        runTest {
            fake.put(ADA_LID, "L1", 1_000, "under the LID")
            val (store, runner) = runner()

            val applied =
                runner.apply(
                    listOf(
                        WaSyncEvent(1, 10, GatewayEvent.IdentityMapped(ADA_LID, ADA, "peer_message", NOW)),
                        WaSyncEvent(2, 20, WaTestKit.incoming(ADA_LID, "L2", 2_000, "a late one")),
                    ),
                )

            assertEquals(listOf(WaSyncRekeyed(1, ADA_LID, ADA)), applied?.rekeyed)
            assertEquals(listOf("rekeyed $ADA_LID $ADA", "changed null", "changed [$ADA]"), told)
            assertEquals(listOf("L2", "L1"), store.ids(ADA))
        }

    @Test fun profileAndGroupAnswersFetchedElsewhereTeachTheStore() =
        runTest {
            fake.put(ADA_LID, "L1", 1_000, "under the LID")
            val (store, runner) = runner()

            // Nothing to hand in: nothing is asked of the store.
            runner.observe()
            assertTrue(fake.writes.isEmpty())

            runner.observe(profiles = listOf(WaProfile(ADA, pushName = "ada", lid = ADA_LID)))

            assertEquals("rekeyed $ADA_LID $ADA", told.first())
            assertEquals(listOf("L1"), store.ids(ADA))
            // The merge moved rows: the chat list is fetched again (after the pairs).
            assertEquals(1, remote.chatCalls)
        }

    // ---- the offline hub

    @Test fun mergesLoggedBeforeTheStoreOpenedAreFollowedWhenItOpens() =
        runTest {
            val moved = ArrayList<Rekey>()
            val kit = WaTestKit(backgroundScope, rekeys = WaRekeys(movers = listOf(RekeyMover { moved += it })))
            kit.ops.put(ADA_LID, "L1", 1_000, "under the LID")
            // The pair was learned while the app was not running (the store's open, the outbox job).
            kit.ops.merge(ADA_LID, ADA)

            kit.offline.start()
            settle()

            assertEquals(listOf(Rekey(ADA_LID, ADA)), moved)
            assertEquals(ADA, kit.offline.keyOf("$ADA_LID "))
            assertEquals(ADA, kit.offline.keyOf("777000000000001:4@lid"))
        }

    @Test fun aMergeTheStreamCausesMovesWhatIsKeptOutsideTheStore() =
        runTest {
            val moved = ArrayList<Rekey>()
            val kit = WaTestKit(backgroundScope, rekeys = WaRekeys(movers = listOf(RekeyMover { moved += it })))
            kit.ops.put(ADA_LID, "L1", 1_000, "under the LID")
            val told = ArrayList<Rekey>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                kit.offline.rekeys.rekeys.collect {
                    told +=
                        it
                }
            }
            kit.offline.start()
            settle()
            kit.connect()
            settle()
            assertTrue(moved.isEmpty())

            kit.remote.emit(5, GatewayEvent.IdentityMapped(ADA_LID, ADA, "peer_message", NOW))
            settle()

            assertEquals(listOf(Rekey(ADA_LID, ADA)), moved)
            assertEquals(moved, told)
        }

    @Test fun liveEventsNameTheirChatAndPeopleByTheStoresKeys() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.ops.merge(ADA_LID, ADA)
            val live = ArrayList<GatewayEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { kit.offline.live.collect { live += it } }
            kit.offline.start()
            settle()
            kit.connect()
            settle()

            // The gateway still names Ada by her LID (typing and presence carry no other form).
            kit.remote.emit(5, GatewayEvent.Typing(ADA_LID, "777000000000001:3@lid", TypingState.Typing))
            kit.remote.emit(6, GatewayEvent.Presence("777000000000001:3@lid", available = true))
            kit.remote.emit(7, GatewayEvent.Typing(GROUP, ADA_LID, TypingState.Recording))
            kit.remote.emit(8, WaTestKit.incoming(ADA_LID, "L9", NOW, "hello"))
            settle()

            val typing = live.filterIsInstance<GatewayEvent.Typing>()
            assertEquals(listOf(ADA to ADA, GROUP to ADA), typing.map { it.chat to it.jid })
            assertEquals(ADA, live.filterIsInstance<GatewayEvent.Presence>().single().jid)
            val message = live.filterIsInstance<GatewayEvent.Message>().single()
            assertEquals(ADA to ADA, message.chat to message.sender)
        }

    @Test fun withoutAnOpenStoreAnIdIsOnlyStrippedOfItsDevice() =
        runTest {
            val kit = WaTestKit(backgroundScope, linked = false)
            kit.offline.start()
            settle()
            assertEquals(ADA_LID, kit.offline.keyOf("777000000000001:4@lid"))
            assertEquals("not a jid", kit.offline.keyOf("not a jid"))
            assertEquals(listOf("+905550000001"), kit.offline.numbersOf("905550000001:2@s.whatsapp.net"))
            assertTrue(kit.offline.numbersOf(ADA_LID).isEmpty())
        }

    @Test fun theNumbersOfAPersonComeFromTheStore() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.ops.merge(ADA_LID, ADA)
            kit.offline.start()
            settle()
            assertEquals(listOf("+905550000001"), kit.offline.numbersOf(ADA_LID))
            // A contact changed number: the current one first, then the one a contact may still hold.
            kit.ops.merge(ADA, MERT)
            assertEquals(listOf("+905550000002", "+905550000001"), kit.offline.numbersOf(ADA_LID))
            assertTrue(kit.offline.numbersOf(GROUP).isEmpty())
        }
}
