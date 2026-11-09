package dev.rovsti.core.rust

import dev.rovsti.core.MainThreadCheck
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustThreading
import dev.rovsti.core.ops.CallAudio
import dev.rovsti.core.ops.NativeWaOps
import dev.rovsti.core.ops.WaGateway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * E2.14: call audio through JNI (`audioPush` / `audioPull`) against a loopback gateway whose socket repeats every
 * frame it receives, so what goes in comes back out.
 */
class CallAudioTest {
    private val key = "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"
    private lateinit var fake: FakeGateway

    @Before
    fun start() {
        TestRust.ensure()
        fake = FakeGateway()
    }

    @After
    fun stop() = fake.close()

    private fun open(callId: String = "call1"): Pair<WaGateway, CallAudio> =
        runBlocking {
            val wa = WaGateway.open(fake.url, key, null, NativeWaOps(TestRust.core)).let { (it as Outcome.Ok).value }
            val audio = wa.openCallAudio(callId)
            wa to ((audio as? Outcome.Ok)?.value ?: error("audio failed: $audio"))
        }

    private fun frame(seed: Int) = ByteArray(CallAudio.FRAME_BYTES) { (seed * 31 + it * 7).toByte() }

    private fun pullUntil(
        audio: CallAudio,
        count: Int,
    ): List<ByteArray> {
        val got = mutableListOf<ByteArray>()
        val out = ByteArray(CallAudio.FRAME_BYTES)
        val until = System.nanoTime() + 10_000_000_000L
        while (got.size < count && System.nanoTime() < until) {
            when (val n = audio.pull(out)) {
                CallAudio.FRAME_BYTES -> got += out.copyOf()
                CallAudio.NO_FRAME -> Thread.sleep(5)
                else -> error("pull answered $n")
            }
        }
        return got
    }

    @Test
    fun framesPushedComeBackIdenticalAndInOrder() {
        val (wa, audio) = open()
        try {
            assertEquals("/v1/calls/call1/audio", fake.seen.single().path)
            // Anything but a whole frame is dropped by Rust.
            audio.push(ByteArray(10))
            audio.push(ByteArray(CallAudio.FRAME_BYTES + 1))
            val sent = (1..8).map { frame(it) }
            for (pcm in sent) {
                audio.push(pcm)
                Thread.sleep(5)
            }
            val got = pullUntil(audio, sent.size)
            assertEquals(sent.size, got.size)
            sent.zip(got).forEach { (a, b) -> assertArrayEquals(a, b) }
            // Nothing else is queued, and a buffer smaller than a frame consumes nothing.
            assertEquals(CallAudio.NO_FRAME, audio.pull(ByteArray(100)))
            assertEquals(CallAudio.NO_FRAME, audio.pull(ByteArray(CallAudio.FRAME_BYTES)))
        } finally {
            audio.close()
            wa.close()
        }
    }

    @Test
    fun aHungUpCallEndsTheStream() {
        val (wa, audio) = open()
        try {
            fake.hangUp()
            val out = ByteArray(CallAudio.FRAME_BYTES)
            val until = System.nanoTime() + 10_000_000_000L
            var last = CallAudio.NO_FRAME
            while (last != CallAudio.ENDED && System.nanoTime() < until) {
                last = audio.pull(out)
                if (last == CallAudio.NO_FRAME) Thread.sleep(5)
            }
            assertEquals(CallAudio.ENDED, last)
        } finally {
            audio.close()
            wa.close()
        }
    }

    @Test
    fun aClosedAudioHandleIsEndedWithoutANativeCall() {
        val (wa, audio) = open()
        audio.close()
        assertEquals(CallAudio.ENDED, audio.pull(ByteArray(CallAudio.FRAME_BYTES)))
        audio.push(frame(1))
        wa.close()
    }

    @Test
    fun aRefusedSocketIsAnAuthOrInvalidErrorNotAnOpenCall() =
        runBlocking {
            fake.wsRefusals["/v1/calls/call1"] =
                FakeGateway.Reply(401, """{"code":"unauthorized","message":"revoked"}""")
            fake.wsRefusals["/v1/calls/call9"] =
                FakeGateway.Reply(404, """{"code":"not_found","message":"no such call"}""")
            val wa = (WaGateway.open(fake.url, key, null, NativeWaOps(TestRust.core)) as Outcome.Ok).value
            try {
                assertTrue((wa.openCallAudio("call1") as Outcome.Err).error is RustError.Auth)
                val missing = (wa.openCallAudio("call9") as Outcome.Err).error
                assertTrue(missing is RustError.Invalid)
                assertEquals("no such call", missing.message)
            } finally {
                wa.close()
            }
        }

    @Test
    fun audioRefusesTheMainThread() {
        val (wa, audio) = open()
        try {
            RustThreading.enable(MainThreadCheck { true })
            try {
                audio.pull(ByteArray(CallAudio.FRAME_BYTES))
                fail("audio on the main thread must throw")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("main thread"))
            }
        } finally {
            RustThreading.enable(MainThreadCheck { false })
            audio.close()
            wa.close()
        }
    }
}
