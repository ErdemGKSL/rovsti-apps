package dev.rosty.core

import dev.rosty.core.model.phone.CallDirection
import dev.rosty.core.model.phone.CallPhase
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMapping
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.ops.WaIdentityBook
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WaIdentityBook] over [FakeWaIdBookOps]: the in-memory pairs follow what the table learned. No native library. */
class WaIdentityBookTest {
    private val lid = "777000000000001@lid"
    private val pn = "100000000001@s.whatsapp.net"
    private val instance = "inst-1"

    private fun call(
        peer: String,
        alt: String? = null,
    ) = CallSummary("c1", peer, null, CallDirection.In, CallPhase.Ringing, false, 1_000, peerAlt = alt)

    private fun page(
        next: String?,
        vararg pairs: Pair<String, String>,
    ) = IdentityMappingsAnswer(true, pairs.map { IdentityMapping(it.first, it.second, "usync", 1) }, next)

    @Test
    fun aLidCallerGetsItsNumberOnceTheTableKnowsThePair() =
        runTest {
            val book = WaIdentityBook(FakeWaIdBookOps(), clock = { 5 })
            book.open(instance)
            val unknown = call("777000000000001:4@lid")
            assertNull(book.numberOf(unknown.peer, unknown.peerAlt))
            assertEquals(unknown, book.fill(unknown))

            book.catchUp(page("c1", lid to pn))
            assertEquals("+100000000001", book.numberOf(unknown.peer, unknown.peerAlt))
            assertEquals(pn, book.fill(unknown).peerAlt)
            assertEquals("c1", book.since())
            // A phone-number peer and a LID the gateway already named are left as they came.
            assertEquals(call(pn), book.fill(call(pn)))
            val named = call(lid, "100000000002@s.whatsapp.net")
            assertEquals(named, book.fill(named))
        }

    @Test
    fun aCallThatNamesBothIdsTeachesTheTableOnce() =
        runTest {
            val ops = FakeWaIdBookOps()
            val book = WaIdentityBook(ops)
            book.open(instance)
            assertTrue(book.learnCalls(listOf(call(lid, pn), call(pn), call(lid))).getOrThrow())
            assertEquals("+100000000001", book.numberOf(lid))
            val before = ops.calls.size
            // The same list again: the pair is known, nothing is sent.
            assertFalse(book.learnCalls(listOf(call(lid, pn), call("$pn", lid))).getOrThrow())
            assertEquals(before, ops.calls.size)
        }

    @Test
    fun theEventsOfTheStreamFeedTheTable() =
        runTest {
            val book = WaIdentityBook(FakeWaIdBookOps())
            book.open(instance)
            assertFalse(book.onEvent(GatewayEvent.IdentityMapped(lid, pn, "peer_message", 7)))
            assertEquals("+100000000001", book.numberOf(lid))

            val moved = "100000000002@s.whatsapp.net"
            assertFalse(book.onEvent(GatewayEvent.IdentityNumberChanged(pn, moved, oldLid = lid)))
            assertEquals("+100000000002", book.numberOf(lid))

            val other = "777000000000003@lid"
            book.onEvent(
                GatewayEvent.Call(
                    "c9",
                    other,
                    null,
                    CallDirection.In,
                    CallPhase.Ringing,
                    false,
                    "100000000003@s.whatsapp.net",
                ),
            )
            assertEquals("+100000000003", book.numberOf(other))
        }

    @Test
    fun anotherAccountEmptiesTheTableAndItsCursor() =
        runTest {
            val book = WaIdentityBook(FakeWaIdBookOps())
            book.open(instance)
            val status =
                InstanceStatus(
                    instance,
                    InstanceState.Connected,
                    ownJid = "100000000009@s.whatsapp.net",
                    accountEpoch = 1,
                )
            assertFalse(book.noteAccount(status).getOrThrow())
            book.catchUp(page("c1", lid to pn))
            assertFalse(book.noteAccount(status).getOrThrow())
            assertEquals("+100000000001", book.numberOf(lid))

            assertTrue(book.noteAccount(status.copy(accountEpoch = 2)).getOrThrow())
            assertNull(book.numberOf(lid))
            assertNull(book.since())

            book.catchUp(page("c1", lid to pn))
            assertTrue(book.onEvent(GatewayEvent.AccountChanged(newPn = "100000000008@s.whatsapp.net")))
            assertNull(book.numberOf(lid))
            assertNull(book.since())
        }

    @Test
    fun aClosedBookLearnsNothingAndAnOldGatewayHasNothingToTakeIn() =
        runTest {
            val ops = FakeWaIdBookOps()
            val book = WaIdentityBook(ops)
            assertFalse(book.learnCalls(listOf(call(lid, pn))).getOrThrow())
            assertTrue(ops.calls.isEmpty())

            book.open(instance)
            val done = book.catchUp(IdentityMappingsAnswer(supported = false)).getOrThrow()
            assertFalse(done.more)
            assertEquals(listOf("wa_idbook.snapshot"), ops.calls)

            ops.put(instance, lid, pn)
            book.open(instance)
            assertEquals("+100000000001", book.numberOf(lid))
            book.open(null)
            assertNull(book.numberOf(lid))
        }

    @Test
    fun wipeForgetsTheTable() =
        runTest {
            val ops = FakeWaIdBookOps()
            val book = WaIdentityBook(ops)
            book.open(instance)
            book.catchUp(page("c1", lid to pn))
            book.wipe().getOrThrow()
            assertNull(book.numberOf(lid))
            book.open(instance)
            assertNull(book.numberOf(lid))
            assertNull(book.since())
        }
}
