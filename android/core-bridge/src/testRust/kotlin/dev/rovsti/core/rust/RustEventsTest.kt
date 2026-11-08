package dev.rovsti.core.rust

import dev.rovsti.core.RustEvent
import dev.rovsti.core.RustEvents
import dev.rovsti.core.getOrThrow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RustEventsTest {
    @Test
    fun aThousandEventsFromARustThreadArriveInOrder() =
        runBlocking {
            TestRust.ensure()
            val collector =
                async(start = CoroutineStart.UNDISPATCHED) {
                    TestRust.events.events
                        .filterIsInstance<RustEvent.Message>()
                        .take(1000)
                        .toList()
                }
            TestRust.core
                .call(
                    "core.emit_test",
                    buildJsonObject {
                        put("kind", "tick")
                        put("count", 1000)
                    },
                ).getOrThrow()
            val got = withTimeout(10_000) { collector.await() }
            assertEquals(1000, got.size)
            got.forEachIndexed { n, event ->
                assertEquals("tick", event.kind)
                assertEquals(n, ((event.body() as JsonObject)["n"] as JsonPrimitive).content.toInt())
            }
        }

    @Test
    fun overflowPublishesOneResyncAndResumes() =
        runBlocking {
            val events = RustEvents()
            val collector =
                async(start = CoroutineStart.UNDISPATCHED) { events.events.take(2).toList() }
            // No suspension point in the loop: the collector cannot drain, so the buffer fills.
            repeat(RustEvents.BUFFER + 500) { events.onEvent("e", "$it".encodeToByteArray()) }
            val first = withTimeout(5_000) { collector.await() }
            assertEquals(2, first.size)
            val later = async(start = CoroutineStart.UNDISPATCHED) { events.events.take(2).toList() }
            events.onEvent("after", ByteArray(0))
            val got = withTimeout(5_000) { later.await() }
            assertTrue(got.first() is RustEvent.Resync)
            assertEquals("after", (got[1] as RustEvent.Message).kind)
        }
}
