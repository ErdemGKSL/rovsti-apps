package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncOlderAnswer
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.ops.WaSyncOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync runner makes exactly the gateway calls the store asks for and hands the answers back. */
class WaSyncRunnerTest {
    private val fake = FakeWaSyncOps()
    private val remote = FakeWaRemote()
    private val changes = mutableListOf<Set<String>?>()

    /** The fake with the parts of the plan it does not model: gaps that page, and older history. */
    private class Scripted(
        private val fake: FakeWaSyncOps,
    ) : WaSyncOps by fake {
        var opens: List<WaSyncFetch> = emptyList()
        val next = mutableMapOf<WaSyncFetch, WaSyncFetch>()
        var older: WaSyncFetch? = null

        override suspend fun storeChats(req: WaSyncStoreChatsRequest): Outcome<WaSyncStoredChats> {
            fake.storeChats(req)
            return Outcome.Ok(WaSyncStoredChats(FakeWaSyncOps.HANDLE, req.chats.size, opens))
        }

        override suspend fun storePage(req: WaSyncStorePageRequest): Outcome<WaSyncStoredPage> {
            val stored = fake.storePage(req).getOrThrow()
            return Outcome.Ok(stored.copy(next = next[WaSyncFetch(req.chat, req.before, req.limit)]))
        }

        override suspend fun older(req: WaSyncOlderRequest) = Outcome.Ok(WaSyncOlderAnswer(older))
    }

    private val scripted = Scripted(fake)

    private suspend fun runner(ops: WaSyncOps = scripted): Pair<WaSyncStore, WaSyncRunner> {
        val store = WaSyncStore.open("inst1", ops = ops, release = {}).getOrThrow()
        return store to WaSyncRunner(store, remote, { WaTestKit.NOW }, { changes += it })
    }

    @Test fun aFirstRunFetchesTheChatListAndThePagesItOpens() =
        runTest {
            fake.owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            remote.chatList = listOf(RichChat(ADA, "Ada", null, 0, false))
            val first = WaSyncFetch(ADA, null, 50)
            val second = WaSyncFetch(ADA, 900, 50)
            scripted.opens = listOf(first)
            scripted.next[first] = second
            remote.pages[ADA to null] = listOf(WaTestKit.rich(ADA, "m2", 1_000, "newer"))
            remote.pages[ADA to 900L] = listOf(WaTestKit.rich(ADA, "m1", 800, "older"))
            val (store, runner) = runner()

            assertTrue(runner.catchUp())

            assertEquals(1, remote.chatCalls)
            // Page after page until the store names no next one.
            assertEquals(listOf(first, second), remote.pageCalls)
            assertEquals(
                listOf("m2", "m1"),
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )
            assertEquals(listOf(null, setOf(ADA), setOf(ADA)), changes)
        }

    @Test fun withNothingOwedOnlyTheOpenGapsAreFetched() =
        runTest {
            val gap = WaSyncFetch(MERT, 500, 50)
            fake.owed = WaSyncPlan(afterSeq = 41, refreshChats = false, fetch = listOf(gap))
            val (_, runner) = runner()

            assertEquals(41L, runner.afterSeq())
            assertTrue(runner.catchUp())

            assertEquals(0, remote.chatCalls)
            assertEquals(listOf(gap), remote.pageCalls)
        }

    @Test fun aGatewayThatIsAwayLeavesTheStoreAsItIs() =
        runTest {
            fake.owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            fake.put(ADA, "m1", 1_000, "kept")
            remote.down = true
            val (store, runner) = runner()

            assertFalse(runner.catchUp())

            assertEquals(
                listOf("m1"),
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )
            assertTrue(fake.writes.isEmpty())
        }

    @Test fun eventsAreAppliedInOneCallAndNameTheirChats() =
        runTest {
            val (store, runner) = runner()
            val events =
                listOf(
                    WaSyncEvent(1, 10, WaTestKit.incoming(ADA, "a1", 1_000, "one")),
                    WaSyncEvent(2, 20, WaTestKit.incoming(MERT, "b1", 2_000, "two")),
                )

            val applied = runner.apply(events)

            assertEquals(2, applied?.applied)
            assertEquals(1, fake.writes.filterIsInstance<WaSyncEventsRequest>().size)
            assertEquals(listOf(setOf(ADA, MERT)), changes)
            assertEquals(
                "one",
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .single()
                    .text,
            )
            assertNull(runner.apply(emptyList()))
        }

    @Test fun olderHistoryComesFromTheGatewayAndStaysInTheStore() =
        runTest {
            fake.put(ADA, "m2", 2_000, "stored")
            val (store, runner) = runner()

            // The store knows nothing older to ask for.
            assertEquals(OlderResult.ReachedStart, runner.older(ADA))

            val before = WaSyncFetch(ADA, 2_000, 50)
            scripted.older = before
            remote.down = true
            assertEquals(OlderResult.Offline, runner.older(ADA))

            remote.down = false
            remote.pages[ADA to 2_000L] = listOf(WaTestKit.rich(ADA, "m1", 1_000, "older"))
            assertEquals(OlderResult.Stored, runner.older(ADA))
            assertEquals(listOf(before), remote.pageCalls)
            // Readable offline from now on.
            remote.down = true
            assertEquals(
                listOf("m2", "m1"),
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )

            // An empty page: that was the start of the chat.
            remote.down = false
            scripted.older = WaSyncFetch(ADA, 1_000, 50)
            assertEquals(OlderResult.ReachedStart, runner.older(ADA))
        }
}
