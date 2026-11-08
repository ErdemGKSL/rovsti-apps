package dev.rovsti.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs under Robolectric in the same Gradle invocation as `testRustBridge`, which loads the real library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeRustCoreTest {
    @Test
    fun scriptedAnswersAndCallLog() =
        runBlocking {
            val core = FakeRustCore("9.9.9")
            core.on("demo.echo") { Outcome.Ok(it) }
            assertEquals("9.9.9", core.version().getOrThrow())
            assertEquals(JsonPrimitive("x"), core.call("demo.echo", JsonPrimitive("x")).getOrThrow())
            assertEquals(listOf("demo.echo"), core.calls.map { it.op })
        }

    @Test
    fun unscriptedOpIsUnsupportedAndHeadlessIsEmpty() =
        runBlocking {
            val core = FakeRustCore()
            val outcome = core.call("nope.nope")
            assertTrue((outcome as Outcome.Err).error is RustError.Unsupported)
            assertEquals("", core.headless("unknown", "{}"))
            core.onHeadless("k") { "[$it]" }
            assertEquals("[{}]", core.headless("k", "{}"))
        }

    @Test
    fun mainThreadGuardFiresOnTheRobolectricMainThread() {
        RustThreading.enable()
        try {
            assertThrows(IllegalStateException::class.java) { RustThreading.assertNotMain() }
        } finally {
            RustThreading.disable()
        }
    }
}
