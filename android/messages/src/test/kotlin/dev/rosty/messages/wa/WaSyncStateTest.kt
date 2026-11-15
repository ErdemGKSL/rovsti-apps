package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.HistoryStatus
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncHistoryState
import dev.rosty.core.model.wa.WaSyncObserveRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.INSTANCE
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync runner and the state of the gateway (E6-WA R2): the status is observed on every catch-up and before it,
 * every event of the socket reaches the store, a gap and another account fetch the chat list again, and the runner
 * says when the history progress moved and which media arrived.
 */
class WaSyncStateTest {
    private val fake = FakeWaSyncOps(INSTANCE)
    private val remote = FakeWaRemote()
    private val changes = mutableListOf<Set<String>?>()
    private val accountChanges = mutableListOf<List<String>>()
    private val media = mutableListOf<WaMediaRef>()
    private var historyReads = 0
    private val other = "905550000009@s.whatsapp.net"

    private suspend fun runner(): Pair<WaSyncStore, WaSyncRunner> {
        val store = WaSyncStore.open(INSTANCE, ops = fake, release = {}).getOrThrow()
        val runner =
            WaSyncRunner(
                store,
                remote,
                { NOW },
                { changes += it },
                onAccountChanged = { accountChanges += it },
                onHistory = { historyReads++ },
                onMedia = { media += it },
            )
        return store to runner
    }

    private fun status(
        epoch: String? = "e1",
        own: String = MERT,
        accountEpoch: Int? = 1,
        history: HistoryStatus? = null,
    ) = InstanceStatus(
        INSTANCE,
        InstanceState.Connected,
        own,
        "Me",
        epoch = epoch,
        accountEpoch = accountEpoch,
        history = history,
    )

    @Test fun theStatusIsObservedOnEveryCatchUpAndBeforeAnythingElse() =
        runTest {
            fake.owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            remote.ownStatus = status()
            remote.chatList = listOf(RichChat(ADA, "Ada", null, 0, false))
            val (_, runner) = runner()

            assertTrue(runner.catchUp())
            assertTrue(runner.catchUp())
            assertTrue(runner.catchUp())

            // Store open, socket connect, reconnect: each asks the gateway again, and first.
            assertEquals(3, remote.order.count { it == "status" })
            assertEquals("status", remote.order.first())
            val observed = fake.writes.filterIsInstance<WaSyncObserveRequest>()
            assertEquals(3, observed.size)
            // The whole answer goes in: the epoch, the account epoch, the own number.
            assertEquals(
                Triple("e1", 1, MERT),
                observed.first().status?.let { Triple(it.epoch, it.accountEpoch, it.ownJid) },
            )
            assertEquals(3, historyReads)
        }

    @Test fun aRestartedGatewayIsAGapThatFetchesTheChatListAgain() =
        runTest {
            remote.ownStatus = status(epoch = "e1")
            val (store, runner) = runner()
            runner.catchUp()
            runner.apply(listOf(WaSyncEvent(40, 10, WaTestKit.incoming(ADA, "a1", 1_000, "one"))))
            fake.owed = WaSyncPlan(afterSeq = 40, refreshChats = false, fetch = emptyList())
            assertEquals(40L, runner.afterSeq())
            val before = remote.chatCalls

            // Nothing is owed by the plan itself; the other epoch alone makes the gap.
            remote.ownStatus = status(epoch = "e2")
            assertTrue(runner.catchUp())

            assertEquals(before + 1, remote.chatCalls)
            // The `seq` of the old process says nothing about the new one: the socket starts from the beginning.
            assertNull(store.plan().getOrThrow().afterSeq)
            assertTrue(accountChanges.isEmpty())
        }

    @Test fun anotherAccountInTheStatusEmptiesTheStoreAndIsReported() =
        runTest {
            remote.ownStatus = status(own = MERT)
            fake.put(ADA, "m1", 1_000, "of the old account")
            val (store, runner) = runner()
            runner.catchUp()
            assertTrue(accountChanges.isEmpty())
            val before = remote.chatCalls

            remote.ownStatus = status(own = other, accountEpoch = 2)
            assertTrue(runner.catchUp())

            assertEquals(1, accountChanges.size)
            assertTrue(
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .isEmpty(),
            )
            // Refreshed like a resync, and every reader is told.
            assertEquals(before + 1, remote.chatCalls)
            assertTrue(null in changes)
        }

    @Test fun anOldGatewayWithoutAStatusChangesNothing() =
        runTest {
            fake.owed = WaSyncPlan(afterSeq = 7, refreshChats = false, fetch = emptyList())
            remote.ownStatus = null
            val (_, runner) = runner()

            assertTrue(runner.catchUp())

            assertTrue(fake.writes.none { it is WaSyncObserveRequest })
            assertEquals(0, remote.chatCalls)
            assertTrue(accountChanges.isEmpty())
        }

    @Test fun helloAndAccountChangedGoToTheStoreLikeEveryOtherEvent() =
        runTest {
            remote.ownStatus = status(own = MERT)
            fake.put(ADA, "m1", 1_000, "of the old account")
            val (store, runner) = runner()
            runner.catchUp()
            changes.clear()
            val events =
                listOf(
                    WaSyncEvent(0, 5, GatewayEvent.Hello("e1")),
                    WaSyncEvent(1, 10, GatewayEvent.AccountChanged(MERT, other)),
                    WaSyncEvent(2, 20, WaTestKit.incoming(ADA, "n1", 2_000, "of the new account")),
                )

            val applied = runner.apply(events)

            // Handed in as they came: nothing is filtered on this side.
            assertEquals(
                events,
                fake.writes
                    .filterIsInstance<WaSyncEventsRequest>()
                    .single()
                    .events,
            )
            assertEquals(true, applied?.accountChanged)
            assertEquals(1, accountChanges.size)
            assertEquals(
                listOf("n1"),
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )
            assertEquals(listOf<Set<String>?>(null), changes)
        }

    @Test fun aHelloOfAnotherGatewayProcessAsksForARefresh() =
        runTest {
            remote.ownStatus = status(epoch = "e1")
            val (_, runner) = runner()
            runner.catchUp()
            runner.apply(listOf(WaSyncEvent(3, 10, WaTestKit.incoming(ADA, "a1", 1_000, "one"))))

            val same = runner.apply(listOf(WaSyncEvent(3, 20, GatewayEvent.Hello("e1"))))
            val restarted = runner.apply(listOf(WaSyncEvent(0, 30, GatewayEvent.Hello("e2"))))

            assertEquals(false, same?.resync)
            assertEquals(true, restarted?.resync)
            assertEquals(false, restarted?.accountChanged)
            assertTrue(accountChanges.isEmpty())
        }

    @Test fun theHistoryProgressIsReadAgainAfterStateAndHistoryEventsOnly() =
        runTest {
            val (store, runner) = runner()
            runner.apply(listOf(WaSyncEvent(1, 10, WaTestKit.incoming(ADA, "a1", 1_000, "one"))))
            assertEquals(0, historyReads)

            val syncing = HistoryStatus(HistoryStatus.SYNCING, progress = 40, chats = 12, messages = 3_400)
            runner.apply(
                listOf(
                    WaSyncEvent(
                        2,
                        20,
                        GatewayEvent.State(INSTANCE, InstanceState.Connected, MERT, "Me", history = syncing),
                    ),
                ),
            )
            assertEquals(1, historyReads)
            assertEquals(WaSyncHistoryState.Syncing, store.history().getOrThrow()?.state)

            runner.apply(listOf(WaSyncEvent(3, 30, GatewayEvent.HistorySynced(5, progress = 60, messages = 100))))
            assertEquals(2, historyReads)
            assertEquals(60, store.history().getOrThrow()?.progress)
            assertEquals(3_500L, store.history().getOrThrow()?.messages)
        }

    @Test fun mediaThatArrivesOnTheStreamAndInPagesIsNamed() =
        runTest {
            val gap = WaSyncFetch(ADA, null, 50)
            fake.owed = WaSyncPlan(afterSeq = 1, refreshChats = false, fetch = listOf(gap))
            val picture = MessageBody.Media(MediaKind.Image, null, "image/jpeg", 3)
            remote.pages[ADA to null] =
                listOf(
                    RichMessage("p-new", ADA, ADA, false, NOW - 1_000, picture, emptyList()),
                    RichMessage(
                        "p-old",
                        ADA,
                        ADA,
                        false,
                        NOW - WaDownloads.AUTO_RECENT_MS - 1_000,
                        picture,
                        emptyList(),
                    ),
                    WaTestKit.rich(ADA, "t1", NOW, "text"),
                )
            val (_, runner) = runner()

            runner.catchUp()
            runner.apply(
                listOf(
                    WaSyncEvent(2, 10, GatewayEvent.Message("e-pic", ADA, ADA, false, NOW, picture, emptyList())),
                    WaSyncEvent(3, 20, WaTestKit.incoming(ADA, "e-text", NOW, "text")),
                ),
            )

            // Pictures of old history are not fetched by themselves; they download on a tap.
            assertEquals(listOf(WaMediaRef(ADA, "p-new"), WaMediaRef(ADA, "e-pic")), media)
            assertFalse(accountChanges.isNotEmpty())
        }
}
