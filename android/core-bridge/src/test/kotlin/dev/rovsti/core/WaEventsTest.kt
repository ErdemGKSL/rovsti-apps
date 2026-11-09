package dev.rovsti.core

import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.HandleAnswer
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.WaStreamEvent
import dev.rovsti.core.ops.NativeWaOps
import dev.rovsti.core.ops.WaEvents
import dev.rovsti.core.ops.WaFeed
import dev.rovsti.core.ops.WaGateway
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The `wa.event` feed and the gateway facade over fakes. Plain JVM, no native library. */
class WaEventsTest {
    private fun wire(
        instance: String,
        event: String,
    ) = """{"instance":"$instance","event":$event}""".encodeToByteArray()

    private val connected = """{"kind":"connected"}"""
    private val keyRevoked = """{"kind":"event","seq":4,"at_ms":9,"event":{"type":"key_revoked"}}"""

    @Test
    fun theFeedDecodesAndFiltersByInstance() =
        runBlocking {
            val rust = RustEvents()
            val feed = async(start = CoroutineStart.UNDISPATCHED) { WaEvents(rust).feed("a").take(2).toList() }
            rust.onEvent("tick", ByteArray(0))
            rust.onEvent("wa.event", wire("b", connected))
            rust.onEvent("wa.event", wire("a", connected))
            rust.onEvent("wa.event", wire("a", keyRevoked))
            val got = withTimeout(5_000) { feed.await() }
            assertEquals(WaFeed.Stream("a", WaStreamEvent.Connected), got[0])
            assertEquals(WaFeed.Stream("a", WaStreamEvent.Event(4, 9, GatewayEvent.KeyRevoked)), got[1])
        }

    @Test
    fun aSinkOverflowBecomesOneSyntheticResync() =
        runBlocking {
            val rust = RustEvents()
            val seen = CopyOnWriteArrayList<WaFeed>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { WaEvents(rust).feed().collect { seen += it } }
            // The collector cannot run while this thread keeps emitting, so its buffer fills and the newest are dropped.
            repeat(RustEvents.BUFFER + 50) { rust.onEvent("wa.event", wire("a", connected)) }
            withTimeout(5_000) { while (seen.size < RustEvents.BUFFER) delay(10) }
            // The sink owes one resync and publishes it before the next event that fits.
            rust.onEvent("wa.event", wire("a", keyRevoked))
            withTimeout(5_000) { while (seen.last() !is WaFeed.Stream || seen.none { it == WaFeed.Resync }) delay(10) }
            collector.cancel()
            assertEquals(1, seen.count { it == WaFeed.Resync })
            val at = seen.indexOf(WaFeed.Resync)
            assertTrue("resync after the buffered events", at >= RustEvents.BUFFER)
            assertEquals(WaFeed.Stream("a", WaStreamEvent.Event(4, 9, GatewayEvent.KeyRevoked)), seen.last())
            assertEquals(at + 2, seen.size)
        }

    @Test
    fun anEventThatDoesNotDecodeIsReportedNotThrown() =
        runBlocking {
            val rust = RustEvents()
            val feed = async(start = CoroutineStart.UNDISPATCHED) { WaEvents(rust).feed().take(1).toList() }
            rust.onEvent(
                "wa.event",
                wire("a", """{"kind":"event","seq":1,"at_ms":1,"event":{"type":"from_the_future"}}"""),
            )
            val got = withTimeout(5_000) { feed.await() }.single()
            assertTrue(got is WaFeed.Undecodable)
        }

    @Test
    fun theGatewayFacadeSendsTheHandleAndRefusesAfterClose() =
        runBlocking {
            val core = FakeRustCore()
            core.on("wa.open") { Outcome.Ok(buildJsonObject { put("handle", 7L) }) }
            core.on("wa.status") { request ->
                assertEquals(7L, ((request as JsonObject)["handle"] as JsonPrimitive).content.toLong())
                Outcome.Ok(
                    buildJsonObject {
                        put("instance_id", "i")
                        put("state", "linking")
                    },
                )
            }
            core.on("wa.events_start") { Outcome.Ok(buildJsonObject { put("handle", 8L) }) }
            val released = mutableListOf<Long>()
            val wa =
                (
                    WaGateway.open("https://wa.example.com", "rwk_x", ops = NativeWaOps(core), release = {
                        released +=
                            it
                    }) as Outcome.Ok
                ).value
            assertEquals(InstanceStatus("i", InstanceState.Linking), wa.status().getOrThrow())
            val stream = (wa.startEvents("i") as Outcome.Ok).value
            stream.close()
            stream.close()
            assertEquals(listOf(8L), released)
            wa.close()
            assertEquals(listOf(8L, 7L), released)
            val closed = wa.status() as Outcome.Err
            assertTrue(closed.error is RustError.NotFound)
            assertEquals(1, core.calls.count { it.op == "wa.status" })
        }
}
