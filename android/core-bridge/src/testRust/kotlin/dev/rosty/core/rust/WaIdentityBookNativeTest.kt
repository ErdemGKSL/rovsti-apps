package dev.rosty.core.rust

import dev.rosty.core.getOrThrow
import dev.rosty.core.model.phone.CallDirection
import dev.rosty.core.model.phone.CallPhase
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMapping
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.ops.NativeWaIdBookOps
import dev.rosty.core.ops.WaIdentityBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** [WaIdentityBook] against the real host library: the table is a file, so it survives a new facade. */
class WaIdentityBookNativeTest {
    private val lid = "777000000000001@lid"
    private val pn = "100000000001@s.whatsapp.net"

    @Before
    fun load() = TestRust.ensure()

    private fun book(dir: String) = WaIdentityBook(NativeWaIdBookOps(TestRust.core), dir)

    @Test
    fun thePairsAndTheCursorSurviveAReopenAndBelongToOneInstance() =
        runBlocking {
            val dir = Files.createTempDirectory("wa-idbook").toFile()
            try {
                val first = book(dir.absolutePath)
                first.open("inst-1").getOrThrow()
                val page = IdentityMappingsAnswer(true, listOf(IdentityMapping(lid, pn, "usync", 1)), "c1")
                assertTrue(first.catchUp(page).getOrThrow().changed)
                val call =
                    CallSummary(
                        "c1",
                        "777000000000002:3@lid",
                        null,
                        CallDirection.In,
                        CallPhase.Ringing,
                        false,
                        1_000,
                        peerAlt = "100000000002@s.whatsapp.net",
                    )
                assertTrue(first.learnCalls(listOf(call)).getOrThrow())

                val again = book(dir.absolutePath)
                again.open("inst-1").getOrThrow()
                assertEquals("c1", again.since())
                assertEquals("+100000000001", again.numberOf("777000000000001:9@lid"))
                assertEquals("+100000000002", again.numberOf("777000000000002@lid"))
                assertEquals(pn, again.fill(call.copy(peer = lid, peerAlt = null)).peerAlt)

                // Another account on the link: the table starts over.
                assertTrue(again.onEvent(GatewayEvent.AccountChanged(newPn = "100000000008@s.whatsapp.net")))
                assertNull(again.numberOf(lid))
                assertNull(again.since())

                again.catchUp(page).getOrThrow()
                again.open("inst-2").getOrThrow()
                assertNull(again.numberOf(lid))
            } finally {
                dir.deleteRecursively()
            }
        }
}
