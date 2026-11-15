package dev.rosty.core

import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMapping
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.WaIdKind
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRekeyed
import dev.rosty.core.model.wa.WaSyncResolved
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WaSyncStore] over [FakeWaSyncOps]: the handle wrapper and the fake the screen tests build on. No native library. */
class WaSyncStoreTest {
    private val chat = "100000000001@s.whatsapp.net"

    /** The LID of the person behind [chat]. */
    private val lid = "777000000000001@lid"

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

    private suspend fun store(fake: FakeWaSyncOps): WaSyncStore =
        WaSyncStore.open("inst1", ops = fake, release = {}).getOrThrow()

    private fun page(
        next: String?,
        vararg pairs: Pair<String, String>,
    ) = IdentityMappingsAnswer(true, pairs.map { (l, pn) -> IdentityMapping(l, pn, "usync", 1) }, next)

    @Test
    fun aScriptedMergeIsReportedAndReadsUnderTheOldKeyFollowIt() =
        runTest {
            val fake = FakeWaSyncOps()
            fake.put(lid, "L1", 1_000, "lid'den")
            fake.put(chat, "A1", 2_000, "numaradan")
            val store = store(fake)

            val mapped = GatewayEvent.IdentityMapped(lid, chat, "peer_message", learnedMs = 2_500)
            val applied = store.applyEvents(listOf(WaSyncEvent(1, 3_000, mapped))).getOrThrow()
            assertEquals(listOf(WaSyncRekeyed(1, lid, chat)), applied.rekeyed)
            assertEquals(listOf(chat), applied.chats)
            // Reported once; a consumer that missed it asks by its own cursor.
            assertTrue(
                store
                    .applyEvents(emptyList())
                    .getOrThrow()
                    .rekeyed
                    .isEmpty(),
            )
            assertEquals(applied.rekeyed, store.rekeyedSince().getOrThrow())
            assertTrue(store.rekeyedSince(seq = 1).getOrThrow().isEmpty())

            val rows = store.thread(lid, limit = 50).getOrThrow().items
            assertEquals(listOf("A1", "L1"), rows.map { it.id })
            assertTrue(rows.all { it.chat == chat })
            assertEquals(lid, rows[1].wireSender)
            assertEquals("lid'den", store.message(lid, "L1").getOrThrow()?.text)
            val resolved = store.resolve("777000000000001:3@lid").getOrThrow()
            assertEquals(WaSyncResolved(key = chat, kind = WaIdKind.Lid, wire = lid), resolved)
            assertNull(store.resolve("merhaba").getOrThrow())
            assertEquals("+100000000001", store.parseId("100000000001:2@s.whatsapp.net").getOrThrow()?.number)
        }

    @Test
    fun aCatchUpPagesFromTheStoredCursorUntilTheGatewayHasNoMore() =
        runTest {
            val fake = FakeWaSyncOps()
            fake.put(lid, "L1", 1_000, "lid'den")
            fake.owed = fake.owed.copy(identitySince = "7")
            val store = store(fake)
            val asked = mutableListOf<String?>()
            val pages = mapOf("7" to page("8", lid to chat), "8" to page("9"), "9" to page(null))

            val caught =
                store
                    .identityCatchUp { since ->
                        asked += since
                        Outcome.Ok(pages.getValue(checkNotNull(since)))
                    }.getOrThrow()
            assertEquals(listOf<String?>("7", "8", "9"), asked)
            assertTrue(caught.supported && caught.complete && caught.resync)
            assertEquals(1, caught.pairs)
            assertEquals(listOf(WaSyncRekeyed(1, lid, chat)), caught.rekeyed)
            assertEquals("9", store.plan().getOrThrow().identitySince)
            assertEquals(
                listOf("L1"),
                store
                    .thread(chat, limit = 50)
                    .getOrThrow()
                    .items
                    .map { it.id },
            )
        }

    @Test
    fun anOldGatewayIsAskedOnceForTheLifeOfTheStore() =
        runTest {
            val fake = FakeWaSyncOps()
            val store = store(fake)
            var calls = 0
            val old: suspend (String?) -> Outcome<IdentityMappingsAnswer> = {
                calls++
                Outcome.Ok(IdentityMappingsAnswer(supported = false))
            }
            val first = store.identityCatchUp(old).getOrThrow()
            assertFalse(first.supported)
            assertTrue(first.complete)
            assertFalse(store.identityCatchUp(old).getOrThrow().supported)
            assertEquals(1, calls)
            assertTrue(fake.writes.isEmpty())
            // Another store (the next session) asks again: the gateway may have been updated.
            store(fake).identityCatchUp(old).getOrThrow()
            assertEquals(2, calls)
        }

    @Test
    fun aCatchUpThatCannotReachTheGatewayStopsAndGoesOnLater() =
        runTest {
            val fake = FakeWaSyncOps()
            val store = store(fake)
            var online = false
            val fetch: suspend (String?) -> Outcome<IdentityMappingsAnswer> = { since ->
                when {
                    since == null -> Outcome.Ok(page("5", lid to chat))
                    online -> Outcome.Ok(page(null))
                    else -> Outcome.Err(RustError.Network("offline"))
                }
            }
            val cut = store.identityCatchUp(fetch).getOrThrow()
            assertTrue(cut.supported)
            assertFalse(cut.complete)
            assertTrue(cut.error is RustError.Network)
            // What was stored before the failure is kept and reported.
            assertEquals(1, cut.rekeyed.size)
            assertEquals("5", store.plan().getOrThrow().identitySince)

            online = true
            val rest = store.identityCatchUp(fetch).getOrThrow()
            assertTrue(rest.complete && rest.rekeyed.isEmpty())

            store.close()
            assertTrue((store.identityCatchUp(fetch) as Outcome.Err).error is RustError.NotFound)
        }

    @Test
    fun aCursorThatDoesNotMoveEndsTheCatchUp() =
        runTest {
            val store = store(FakeWaSyncOps())
            var calls = 0
            val stuck =
                store
                    .identityCatchUp {
                        calls++
                        Outcome.Ok(page("5"))
                    }.getOrThrow()
            assertTrue(stuck.complete)
            assertEquals(2, calls)
        }
}
