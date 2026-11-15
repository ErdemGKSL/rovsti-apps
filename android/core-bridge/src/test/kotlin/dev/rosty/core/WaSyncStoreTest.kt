package dev.rosty.core

import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WaSyncStore] over [FakeWaSyncOps]: the handle wrapper and the fake the screen tests build on. No native library. */
class WaSyncStoreTest {
    private val chat = "100000000001@s.whatsapp.net"

    @Test
    fun aThreadReadsStoredRowsWithWaitingSendsOnTop() =
        runTest {
            val fake = FakeWaSyncOps()
            fake.put(chat, "A1", 1_000, "merhaba")
            fake.put(chat, "A2", 2_000, "silindi", deleted = true)
            val released = mutableListOf<Long>()
            val store = WaSyncStore.open("inst1", ops = fake, release = { released += it }).getOrThrow()

            store.enqueue("c1", chat, WaSyncAction.SendText("yoldayım"), nowMs = 3_000).getOrThrow()
            val rows = store.thread(chat, limit = 50).getOrThrow().items
            assertEquals(listOf("c1", "A2", "A1"), rows.map { it.id })
            assertEquals(WaSyncRowState.Waiting, rows[0].state)
            assertTrue(rows[1].deleted)
            assertEquals("silindi", rows[1].text)

            val due = store.due(nowMs = 4_000, networkBack = true).getOrThrow()
            assertEquals(listOf("c1"), due.items.map { it.clientId })
            store.settle("c1", WaSyncOutcome.Sent("S1", 4_100), nowMs = 4_200).getOrThrow()
            assertEquals(
                listOf("S1", "A2", "A1"),
                store
                    .thread(chat, limit = 50)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )

            val first = store.thread(chat, limit = 2).getOrThrow()
            assertEquals(listOf("S1", "A2"), first.items.map { it.id })
            assertEquals(
                listOf("A1"),
                store
                    .thread(chat, limit = 2, before = first.next)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )

            store.close()
            store.close()
            assertEquals(listOf(FakeWaSyncOps.HANDLE), released)
            assertTrue((store.chats() as Outcome.Err).error is RustError.NotFound)
        }
}
