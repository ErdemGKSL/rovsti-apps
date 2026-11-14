package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncOutboxState
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outbox: queued at once, sent in the order written, settled with the right kind of failure. */
class WaOutboxTest {
    private val fake = FakeWaSyncOps()
    private val remote = FakeWaRemote()
    private val changes = mutableListOf<Set<String>?>()

    private suspend fun outbox(): Pair<WaSyncStore, WaOutbox> {
        val store = WaSyncStore.open("inst1", ops = fake, release = {}).getOrThrow()
        return store to WaOutbox(store, remote, { WaTestKit.NOW }, { changes += it })
    }

    private fun api(
        code: String,
        retryAfterMs: Long? = null,
    ) = RustError.Invalid(
        code,
        JsonObject(
            buildMap {
                put("code", JsonPrimitive(code))
                retryAfterMs?.let { put("retry_after_ms", JsonPrimitive(it)) }
            },
        ),
    )

    @Test fun failuresSettleAsTheStoreExpects() {
        // No answer: the gateway may have sent it.
        assertEquals(WaSyncErrorKind.Network, waFailure(RustError.Network("timed out")).error)
        assertEquals(WaSyncErrorKind.Network, waFailure(RustError.Parse("bad answer")).error)
        // The gateway said it did not send.
        assertEquals(WaSyncErrorKind.Gateway, waFailure(api("not_connected")).error)
        assertEquals(WaSyncErrorKind.Gateway, waFailure(api("internal")).error)
        val limited = waFailure(api("rate_limited", retryAfterMs = 61_000))
        assertEquals(WaSyncErrorKind.Gateway, limited.error)
        assertEquals(61_000L, limited.retryAfterMs)
        assertNull(waFailure(api("rate_limited")).retryAfterMs)
        // It could not be asked: no open gateway, the key refused.
        assertEquals(WaSyncErrorKind.Gateway, waFailure(RustError.NotFound("not linked")).error)
        assertEquals(WaSyncErrorKind.Gateway, waFailure(RustError.Auth("key refused")).error)
        // The request itself was refused: not retried on its own.
        assertEquals(WaSyncErrorKind.Rejected, waFailure(api("bad_request")).error)
        assertEquals(WaSyncErrorKind.Rejected, waFailure(api("not_found")).error)
        assertEquals(WaSyncErrorKind.Rejected, waFailure(RustError.Invalid("file too large")).error)
    }

    @Test fun aQueuedMessageShowsAtOnceAndGoesOutInTheOrderWritten() =
        runTest {
            val (store, outbox) = outbox()
            outbox.enqueue("c1", ADA, WaSyncAction.SendText("first"))
            outbox.enqueue("c2", ADA, WaSyncAction.SendText("second"))
            outbox.enqueue("c3", MERT, WaSyncAction.SendText("other chat"))

            val waiting = store.thread(ADA, 50).getOrThrow().items
            assertEquals(listOf("second", "first"), waiting.map { it.text })
            assertTrue(waiting.all { it.state == WaSyncRowState.Waiting })
            assertTrue(remote.performed.isEmpty())

            val result = outbox.drain()

            assertEquals(3, result.sent)
            assertNull(result.nextWakeMs)
            // One row per chat at a time, each chat in the order written.
            assertEquals(
                listOf("first", "second"),
                remote.performed.filter { it.first == ADA }.map { (it.second as WaSyncAction.SendText).text },
            )
            assertEquals(listOf("other chat"), remote.performed.filter { it.first == MERT }.map { textOf(it.second) })
            val sent = store.thread(ADA, 50).getOrThrow().items
            assertTrue(sent.all { it.state == WaSyncRowState.Sent })
            assertTrue(fake.queue.isEmpty())
        }

    private fun textOf(action: WaSyncAction) = (action as WaSyncAction.SendText).text

    @Test fun withoutAConnectionTheMessageWaitsAndTheDrainDoesNotSpin() =
        runTest {
            val (store, outbox) = outbox()
            outbox.enqueue("c1", ADA, WaSyncAction.SendText("hello"))
            remote.down = true

            val result = outbox.drain()

            assertEquals(0, result.sent)
            // The store named the time of the next try: that is when the wake job comes back.
            assertEquals(WaTestKit.NOW, result.nextWakeMs)
            assertEquals(
                WaSyncRowState.Waiting,
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .single()
                    .state,
            )

            remote.down = false
            assertEquals(1, outbox.drain(networkBack = true).sent)
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

    @Test fun aRefusedMessageFailsAndCanBeRetriedOrCancelled() =
        runTest {
            val (store, outbox) = outbox()
            outbox.enqueue("c1", ADA, WaSyncAction.SendText("too long"))
            remote.answer = { _, _ -> Outcome.Err(api("bad_request")) }

            assertEquals(0, outbox.drain().sent)
            val failed =
                store
                    .thread(ADA, 50)
                    .getOrThrow()
                    .items
                    .single()
            assertEquals(WaSyncRowState.Failed, failed.state)
            assertEquals(WaSyncOutboxState.Failed, fake.queue.single().state)
            // A failed row is not sent again on its own.
            assertEquals(0, outbox.drain().sent)
            assertEquals(1, remote.performed.size)

            remote.answer = { _, _ ->
                Outcome.Ok(
                    dev.rosty.core.model.wa.WaSyncOutcome
                        .Sent("m9", 5),
                )
            }
            assertTrue(outbox.retry(ADA, "c1"))
            assertEquals(1, outbox.drain().sent)

            outbox.enqueue("c2", ADA, WaSyncAction.SendText("never mind"))
            assertTrue(outbox.cancel(ADA, "c2"))
            assertFalse(outbox.cancel(ADA, "c2"))
            assertEquals(0, outbox.drain().sent)
        }

    @Test fun editsDeletesReactionsAndReadMarksAreQueuedToo() =
        runTest {
            val (_, outbox) = outbox()
            val actions =
                listOf(
                    WaSyncAction.Edit("m1", "fixed"),
                    WaSyncAction.Revoke("m2"),
                    WaSyncAction.React("m3", fromMe = false, sender = ADA, emoji = "👍"),
                    WaSyncAction.MarkRead(null, listOf("m3")),
                )
            actions.forEachIndexed { at, action -> outbox.enqueue("c$at", ADA, action) }

            assertEquals(4, outbox.drain().sent)
            assertEquals(actions, remote.performed.map { it.second })
        }
}
