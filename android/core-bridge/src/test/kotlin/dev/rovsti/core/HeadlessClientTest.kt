package dev.rovsti.core

import dev.rovsti.core.model.headless.SmsClassifyAnswer
import dev.rovsti.core.model.headless.TripEndAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2.8 AC2, Kotlin half: no decision (`null`) for a missing library, a panic, an empty answer; the caller then posts the plain notification. */
class HeadlessClientTest {
    private class Scripted(
        private val answer: (String, String) -> String,
    ) : RustCore {
        override suspend fun version(): Outcome<String> = Outcome.Ok("fake")

        override suspend fun call(
            op: String,
            request: JsonElement,
        ): Outcome<JsonElement> = Outcome.Err(RustError.Unsupported("not used"))

        override suspend fun headless(
            kind: String,
            json: String,
        ): String = answer(kind, json)
    }

    private fun client(
        load: () -> Unit = {},
        answer: (String, String) -> String,
    ) = HeadlessClient(Scripted(answer), load)

    @Test
    fun anAnswerIsReturnedAndDecodes() =
        runBlocking {
            val answer =
                client {
                    _,
                    _,
                    ->
                    """{"spam":true,"reason":"keyword_rule","otp":null}"""
                }.ask("sms_classify", "{}")
            assertNotNull(answer)
            assertEquals(SmsClassifyAnswer(true, "keyword_rule", null), answer!!.decode(SmsClassifyAnswer.serializer()))
        }

    @Test
    fun theKindAndJsonReachTheCore() =
        runBlocking {
            var seen: Pair<String, String>? = null
            client { kind, json ->
                seen = kind to json
                "{\"state\":\"ended\"}"
            }.ask("trip_end", """{"dir":"/d"}""")
            assertEquals("trip_end" to """{"dir":"/d"}""", seen)
        }

    @Test
    fun libraryMissingGivesNull() =
        runBlocking {
            var asked = false
            val none =
                client(load = { throw UnsatisfiedLinkError("no rovsti_ffi") }) { _, _ ->
                    asked = true
                    "{}"
                }.ask("sms_classify", "{}")
            assertNull(none)
            assertFalse("the core is not called without a library", asked)
        }

    @Test
    fun aLinkErrorAtCallTimeGivesNull() =
        runBlocking {
            assertNull(client { _, _ -> throw UnsatisfiedLinkError("symbol not found") }.ask("trip_end", "{}"))
        }

    @Test
    fun panicIsAnEmptyAnswerAndGivesNull() =
        runBlocking {
            assertNull(client { _, _ -> "" }.ask("trip_fix", "{}"))
        }

    @Test
    fun anEmptyAnswerGivesNull() =
        runBlocking {
            assertNull(client { _, _ -> "" }.ask("unknown_kind", ""))
        }

    @Test
    fun aRustErrorOrAnyRuntimeErrorGivesNull() =
        runBlocking {
            assertNull(client { _, _ -> throw RustError.Panic("boom") }.ask("trip_fix", "{}"))
            assertNull(client { _, _ -> throw IllegalStateException("main thread") }.ask("trip_fix", "{}"))
        }

    @Test
    fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking { client { _, _ -> throw CancellationException("stop") }.ask("trip_fix", "{}") }
        }
    }

    @Test
    fun anAnswerOfAnotherShapeDecodesToNull() =
        runBlocking {
            val answer = client { _, _ -> """{"unexpected":1}""" }.ask("trip_end", "{}")!!
            assertNull(answer.decodeOrNull(TripEndAnswer.serializer()))
            assertTrue(runCatching { answer.decode(TripEndAnswer.serializer()) }.isFailure)
        }
}
