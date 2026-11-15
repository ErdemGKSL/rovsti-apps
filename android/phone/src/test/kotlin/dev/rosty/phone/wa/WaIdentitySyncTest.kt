package dev.rosty.phone.wa

import dev.rosty.core.FakeWaIdBookOps
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.phone.CallDirection
import dev.rosty.core.model.phone.CallPhase
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMapping
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.ops.WaIdentityBook
import dev.rosty.phone.data.RecentsRepository
import dev.rosty.phone.data.eventually
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaLinkState
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WA-R2: Phone's identity table follows the gateway. A caller that arrives as a LID with no number next to it gets
 * the number the table learned (from the gateway's pairs, its events, or an earlier call), and a LID nobody maps
 * reads "WhatsApp user". The table itself is `rosty_app_core::phone::wa_idbook` (tested in Rust); here it is the fake.
 */
class WaIdentitySyncTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val ops = FakeWaIdBookOps()
    private val book = WaIdentityBook(ops, clock = { NOW })
    private val status = InstanceStatus(INSTANCE, InstanceState.Connected, ownJid = OWN, accountEpoch = 1)
    private val wa = FakeWaRepository()
    private val calls = MutableStateFlow<List<CallSummary>>(emptyList())

    /** The gateway's pages by the cursor they are asked with, and the cursors that were asked. */
    private val pages = HashMap<String?, IdentityMappingsAnswer>()
    private val asked = CopyOnWriteArrayList<String?>()

    @Volatile private var instance: Outcome<String?> = Outcome.Ok(INSTANCE)

    private fun sync(over: WaIdentityBook = book) =
        WaIdentitySync(
            wa = wa,
            book = over,
            instanceId = { instance },
            fetch = { since ->
                asked += since
                Outcome.Ok(pages[since] ?: IdentityMappingsAnswer(supported = true))
            },
            calls = calls,
            scope = scope,
        )

    private fun page(
        next: String?,
        vararg pairs: Pair<String, String>,
    ) = IdentityMappingsAnswer(true, pairs.map { IdentityMapping(it.first, it.second, "usync", 1) }, next)

    private fun call(
        peer: String,
        alt: String? = null,
        name: String? = null,
    ) = CallSummary("c-$peer", peer, name, CallDirection.In, CallPhase.Ringing, false, NOW, peerAlt = alt)

    @After fun stop() {
        scope.cancel()
    }

    @Test fun aConnectedLinkFetchesThePairsPageByPageAndALidCallerGetsItsNumber() =
        runBlocking {
            pages[null] = page("c1", LID_A to PN_A)
            pages["c1"] = page("c2", LID_B to PN_B)
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("the pairs arrived") { book.numberOf(LID_B) != null }
            eventually("the empty last page was asked") { asked.toList() == listOf(null, "c1", "c2") }
            assertEquals("+100000000001", book.numberOf("777000000000001:4@lid"))

            // The call the gateway names by a LID alone now dials, and is looked up, by that number.
            val resolved = resolveWaCalls(listOf(call(LID_A)), book, UNKNOWN).single()
            assertEquals(PN_A, resolved.peerAlt)
            assertEquals("+100000000001", RecentsRepository.dialNumber(resolved))
            assertEquals(PN_A, resolved.dialPeer())
        }

    @Test fun aNewProcessGoesOnFromTheStoredCursor() =
        runBlocking {
            pages[null] = page("c1", LID_A to PN_A)
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("first run done") { asked.toList() == listOf(null, "c1") }

            // The process restarts: a new facade over the same table.
            asked.clear()
            pages["c1"] = page("c2", LID_B to PN_B)
            val again = WaIdentityBook(ops, clock = { NOW })
            sync(again).start()
            eventually("resumed") { again.numberOf(LID_B) != null }
            assertEquals("c1", asked.first())
            assertTrue("nothing was fetched from the start again", null !in asked)
            assertEquals("+100000000001", again.numberOf(LID_A))
        }

    @Test fun aReconnectAndAGapCatchUpAgainFromTheCursor() =
        runBlocking {
            pages[null] = page("c1", LID_A to PN_A)
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("first run done") { asked.toList() == listOf(null, "c1") }

            pages["c1"] = page("c2", LID_B to PN_B)
            wa.emit(GatewayEvent.Hello("epoch-2"))
            eventually("the socket's new start caught up") { book.numberOf(LID_B) != null }
            val before = asked.size
            wa.emit(GatewayEvent.Resync())
            eventually("a gap asks again") { asked.size > before }
            assertEquals("c2", asked.last())
        }

    @Test fun theEventsTeachTheTableWithoutAFetch() =
        runBlocking {
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("opened") { asked.isNotEmpty() }
            wa.emit(GatewayEvent.IdentityMapped(LID_A, PN_A, "peer_message", NOW))
            eventually("mapped") { book.numberOf(LID_A) == "+100000000001" }
            wa.emit(GatewayEvent.IdentityNumberChanged(PN_A, PN_B, oldLid = LID_A))
            eventually("moved") { book.numberOf(LID_A) == "+100000000002" }
        }

    @Test fun aCallThatNamesBothIdsIsRememberedForTheNextOneThatDoesNot() =
        runBlocking {
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            calls.value = listOf(call(LID_A, alt = PN_A))
            eventually("the pair of the call was learned") { book.numberOf(LID_A) != null }
            // The next call from the same person comes with the LID only.
            val next = resolveWaCalls(listOf(call("777000000000001:2@lid")), book, UNKNOWN).single()
            assertEquals("+100000000001", RecentsRepository.dialNumber(next))
        }

    @Test fun anotherAccountEmptiesTheTableAndFetchesFromTheStart() =
        runBlocking {
            pages[null] = page("c1", LID_A to PN_A)
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("first run done") { asked.toList() == listOf(null, "c1") }

            asked.clear()
            pages[null] = page("d1", LID_B to PN_B)
            wa.emit(GatewayEvent.AccountChanged(oldPn = OWN, newPn = "100000000008@s.whatsapp.net"))
            eventually("the new account's pairs arrived") { book.numberOf(LID_B) != null }
            assertNull("the old account's pair is gone", book.numberOf(LID_A))
            assertNull("the catch-up started over", asked.first())
        }

    @Test fun theStatusOfAnotherAccountEmptiesTheTableToo() =
        runBlocking {
            pages[null] = page("c1", LID_A to PN_A)
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("first run done") { book.numberOf(LID_A) != null }
            pages.clear()
            wa.setState(WaLinkState.Connected(status.copy(ownJid = "100000000008@s.whatsapp.net", accountEpoch = 2)))
            eventually("emptied") { book.numberOf(LID_A) == null }
        }

    @Test fun removingTheLinkRemovesTheTable() =
        runBlocking {
            pages[null] = page("c1", LID_A to PN_A)
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("filled") { book.numberOf(LID_A) != null }
            instance = Outcome.Ok(null)
            wa.setState(WaLinkState.None)
            eventually("wiped") { "wa_idbook.wipe" in ops.calls }
            assertNull(book.numberOf(LID_A))
        }

    @Test fun aLinkFileThatCannotBeReadKeepsTheTable() =
        runBlocking {
            pages[null] = page("c1", LID_A to PN_A)
            wa.setState(WaLinkState.Connected(status))
            sync().start()
            eventually("filled") { book.numberOf(LID_A) != null }
            instance = Outcome.Err(RustError.Invalid("damaged"))
            wa.setState(WaLinkState.None)
            wa.setState(WaLinkState.Connected(status))
            assertEquals("+100000000001", book.numberOf(LID_A))
            assertTrue("wa_idbook.wipe" !in ops.calls)
        }

    @Test fun aLidNobodyMapsReadsWhatsAppUserNeverItsDigits() {
        val unmapped = "777000000000009@lid"
        val out =
            resolveWaCalls(
                listOf(
                    call(unmapped),
                    call(unmapped, name = "777000000000009"),
                    call(unmapped, name = "+777000000000009"),
                    call(unmapped, name = " "),
                    call(unmapped, name = "Deniz"),
                    call(PN_A),
                    call("777000000000008@lid", alt = PN_B, name = null),
                ),
                book,
                UNKNOWN,
            )
        assertEquals(listOf(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, "Deniz", null, null), out.map { it.peerName })
        // No number was invented for it: Recents leaves a call it cannot dial out, as before.
        assertNull(RecentsRepository.dialNumber(out[0]))
        assertEquals(UNKNOWN, WaRingNotifier.titleOf(out[0]))
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
        const val INSTANCE = "inst-1"
        const val OWN = "100000000009@s.whatsapp.net"
        const val LID_A = "777000000000001@lid"
        const val PN_A = "100000000001@s.whatsapp.net"
        const val LID_B = "777000000000002@lid"
        const val PN_B = "100000000002@s.whatsapp.net"
        const val UNKNOWN = "WhatsApp user"
    }
}
