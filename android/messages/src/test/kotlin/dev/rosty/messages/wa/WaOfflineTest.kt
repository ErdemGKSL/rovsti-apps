package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncDueRequest
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.ops.WaSyncOps
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.INSTANCE
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline hub: one store per linked instance, the socket after the store's `seq`, the outbox without polling. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaOfflineTest {
    @Test fun theStoreOfTheLinkedInstanceIsOpenedAndClosedOnUnlink() =
        runTest {
            val kit = WaTestKit(backgroundScope, linked = false)
            kit.offline.start()
            settle()
            // Not linked: settled, with no store.
            assertTrue(kit.offline.settled.value)
            assertNull(kit.offline.session.value)

            kit.link.value = WaStoreLink(INSTANCE)
            settle()
            val first = kit.offline.session.value
            assertNotNull(first)
            assertEquals(listOf(INSTANCE), kit.opened)

            // The same instance with another key: the socket is reopened, the store kept.
            kit.link.value = WaStoreLink(INSTANCE, socketKey = "new key")
            settle()
            assertEquals(listOf(INSTANCE), kit.opened)
            assertTrue(kit.closed.isEmpty())
            assertEquals(2, kit.remote.socketsAfter.size)

            // Another instance never shares a store.
            kit.link.value = WaStoreLink("inst2")
            settle()
            assertEquals(listOf(INSTANCE, "inst2"), kit.opened)
            assertEquals(1, kit.closed.size)

            kit.link.value = null
            settle()
            assertNull(kit.offline.session.value)
            assertEquals(2, kit.closed.size)
            assertFalse(kit.offline.online.value)
        }

    @Test fun theSocketResumesAfterTheStoresSeqAndTheOwedChatListIsFetched() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.ops.owed = WaSyncPlan(afterSeq = 41, refreshChats = true, fetch = emptyList())
            kit.remote.chatList = listOf(RichChat(ADA, "Ada", null, 0, false))
            kit.offline.start()
            settle()

            assertEquals(listOf<Long?>(41), kit.remote.socketsAfter)
            assertEquals(1, kit.remote.chatCalls)
            assertEquals(
                1,
                kit.ops.writes
                    .filterIsInstance<WaSyncStoreChatsRequest>()
                    .size,
            )
        }

    @Test fun aReplayIsAppliedInBatchesAndReadersHearTheChats() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.offline.start()
            settle()
            val heard = mutableListOf<Set<String>?>()
            backgroundScope.launch { kit.offline.changes.collect { heard += it } }
            val live = mutableListOf<GatewayEvent>()
            backgroundScope.launch { kit.offline.live.collect { live += it } }
            settle()

            kit.connect()
            kit.remote.emit(1, WaTestKit.incoming(ADA, "a1", 1_000, "one"))
            kit.remote.emit(2, WaTestKit.incoming(ADA, "a2", 2_000, "two"))
            kit.remote.emit(3, WaTestKit.incoming(MERT, "b1", 3_000, "three"))
            kit.remote.emit(4, GatewayEvent.Typing(ADA, ADA, TypingState.Typing))
            settle()

            assertTrue(kit.offline.online.value)
            val applied = kit.ops.writes.filterIsInstance<WaSyncEventsRequest>()
            assertEquals(1, applied.size)
            assertEquals(listOf(1L, 2L, 3L, 4L), applied.single().events.map { it.seq })
            assertEquals(listOf(setOf(ADA, MERT)), heard)
            // What is not stored (typing) still reaches the screens.
            assertTrue(live.last() is GatewayEvent.Typing)
            val store =
                kit.offline.session.value!!
                    .store
            assertEquals(
                listOf("two", "one"),
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .map { it.text },
            )
        }

    /** A store that answers `resync` for an event of that kind, as the real one does. */
    private class Resyncing(
        private val fake: FakeWaSyncOps,
    ) : WaSyncOps by fake {
        override suspend fun applyEvents(req: WaSyncEventsRequest): Outcome<WaSyncApplied> {
            val applied = fake.applyEvents(req).getOrThrow()
            return Outcome.Ok(applied.copy(resync = req.events.any { it.event is GatewayEvent.Resync }))
        }
    }

    @Test fun aResyncEventAndDroppedEventsFetchTheChatListAgain() =
        runTest {
            val fake = FakeWaSyncOps(INSTANCE)
            val kit = WaTestKit(backgroundScope, fake, Resyncing(fake))
            kit.offline.start()
            settle()
            assertEquals(0, kit.remote.chatCalls)

            kit.connect()
            kit.remote.emit(7, GatewayEvent.Resync)
            settle()
            assertEquals(1, kit.remote.chatCalls)

            // Events lost on this side: the recently active chats get a page again.
            kit.remote.emit(WaRemoteEvent.Dropped)
            settle()
            assertEquals(2, kit.remote.chatCalls)
            assertEquals(
                WaOffline.RECENT_MS,
                kit.ops.writes
                    .filterIsInstance<WaSyncStoreChatsRequest>()
                    .last()
                    .refreshRecentMs,
            )
        }

    @Test fun aMessageWrittenOfflineWaitsForTheWakeJobAndGoesOutOnReconnect() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.offline.start()
            settle()

            assertTrue(kit.offline.enqueue(ADA, WaSyncAction.SendText("written on a plane")))
            settle()

            // Not connected: nothing is sent, the job constrained on a network is asked for, nothing polls.
            assertTrue(kit.remote.performed.isEmpty())
            assertEquals(listOf(0L), kit.wakes)
            val store =
                kit.offline.session.value!!
                    .store
            assertEquals(
                WaSyncRowState.Waiting,
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .single()
                    .state,
            )

            kit.connect()
            settle()

            assertEquals(listOf(ADA to WaSyncAction.SendText("written on a plane")), kit.remote.performed)
            assertEquals(
                WaSyncRowState.Sent,
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .single()
                    .state,
            )
        }

    @Test fun connectedAMessageGoesOutAtOnceAndAFailureSchedulesTheRetry() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.offline.start()
            settle()
            kit.connect()
            settle()

            kit.offline.enqueue(ADA, WaSyncAction.SendText("hi"))
            settle()
            assertEquals(1, kit.remote.performed.size)
            assertTrue(kit.wakes.isEmpty())

            // The gateway stops answering: the row waits and the job is scheduled for the time the store named.
            kit.remote.down = true
            kit.offline.enqueue(ADA, WaSyncAction.SendText("again"))
            settle()
            assertEquals(listOf(0L), kit.wakes)
            assertEquals(1, kit.remote.performed.size)

            // The wake job (a network is there): it drains with the backoffs ended.
            kit.remote.down = false
            kit.offline.runFromWork()
            settle()
            assertEquals(2, kit.remote.performed.size)
        }

    @Test fun notLinkedNothingIsQueued() =
        runTest {
            val kit = WaTestKit(backgroundScope, linked = false)
            kit.offline.start()
            settle()
            assertFalse(kit.offline.enqueue(ADA, WaSyncAction.SendText("hi")))
            assertEquals(OlderResult.Offline, kit.offline.older(ADA))
        }

    @Test fun theChatsOfTheInboxComeFromTheStoreWithTheGatewayDown() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.remote.down = true
            kit.chat(ADA, name = "Ada", unread = 2)
            kit.ops.chats[0] = kit.ops.chats[0].copy(last = WaTestKit.text(ADA, "m1", 1_000, "see you"), lastMs = 1_000)
            val chats = StoreWaChats(kit.offline, kit.remote)
            kit.offline.start()
            settle()

            val listed = chats.chats.first()

            assertEquals(listOf(ADA), listed.map { it.jid })
            assertEquals("Ada", listed.single().name)
            assertEquals(2, listed.single().unread)
            assertEquals("m1", listed.single().last?.id)
            // The global search reads the stored messages of the chats it names.
            kit.ops.put(ADA, "m1", 1_000, "see you")
            kit.ops.put(ADA, "m0", 900, "gone", deleted = true)
            val hits = chats.searchCandidates(listOf(ADA, MERT))
            assertEquals(listOf("wa:$ADA" to "m1"), hits?.map { it.chatKey to it.messageId })
            assertEquals("Ada", hits?.single()?.chatTitle)
            assertEquals("see you", hits?.single()?.text)

            // A chat action needs the gateway: offline it reports that it did not happen.
            assertFalse(chats.act(ADA, ChatActionKind.Pin(true)))
            kit.remote.down = false
            assertTrue(chats.act(ADA, ChatActionKind.Pin(true)))
            assertEquals(listOf(ADA to ChatActionKind.Pin(true)), kit.remote.chatActions)

            // Unlinked: no chats.
            kit.link.value = null
            settle()
            assertTrue(chats.chats.first().isEmpty())
            assertNull(chats.searchCandidates(listOf(ADA)))
        }

    @Test fun theDrainAfterAReconnectEndsTheBackoffs() =
        runTest {
            val fake = FakeWaSyncOps(INSTANCE)
            val dues = mutableListOf<WaSyncDueRequest>()
            val recording =
                object : WaSyncOps by fake {
                    override suspend fun outboxDue(req: WaSyncDueRequest) = fake.outboxDue(req).also { dues += req }
                }
            val kit = WaTestKit(backgroundScope, fake, recording)
            kit.offline.start()
            settle()
            kit.connect()
            settle()

            assertTrue(dues.first().networkBack)
        }
}
