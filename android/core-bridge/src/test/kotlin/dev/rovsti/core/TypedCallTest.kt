package dev.rovsti.core

import dev.rovsti.core.model.shared.ParseNumberRequest
import dev.rovsti.core.model.shared.ParsedNumber
import dev.rovsti.core.model.shared.SameNumberAnswer
import dev.rovsti.core.model.shared.SameNumberRequest
import dev.rovsti.core.ops.NativeSharedOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic typed call, the shared facade over a fake core, and [RustHandle]. Plain JVM, no native library. */
class TypedCallTest {
    @Test
    fun typedCallEncodesTheRequestAndDecodesTheAnswer() =
        runBlocking {
            val core = FakeRustCore()
            core.on("phone_number.parse") { request ->
                assertEquals("0532", (request as JsonObject).getValue("input").toString().trim('"'))
                Outcome.Ok(
                    buildJsonObject {
                        put("canonical", "0532")
                        put("international", false)
                        put("whatsapp_user", null as String?)
                    },
                )
            }
            val out = NativeSharedOps(core).parseNumber(ParseNumberRequest("0532"))
            assertEquals(ParsedNumber("0532", false, null), out.getOrThrow())
        }

    @Test
    fun anAnswerOfTheWrongShapeIsAParseErrorNamingTheOp() =
        runBlocking {
            val core = FakeRustCore()
            core.on("phone_number.same") { Outcome.Ok(JsonPrimitive("yes")) }
            val out = NativeSharedOps(core).sameNumber(SameNumberRequest("1", "2"))
            val error = (out as Outcome.Err).error
            assertTrue(error is RustError.Parse)
            assertTrue(error.message, error.message!!.contains("phone_number.same"))
        }

    @Test
    fun anUnknownFieldInAnAnswerIsAParseError() =
        runBlocking {
            val core = FakeRustCore()
            core.on("phone_number.same") {
                Outcome.Ok(
                    buildJsonObject {
                        put("same", true)
                        put("extra", 1)
                    },
                )
            }
            val out = NativeSharedOps(core).sameNumber(SameNumberRequest("1", "2"))
            assertTrue((out as Outcome.Err).error is RustError.Parse)
        }

    @Test
    fun rustErrorsPassThroughUnchanged() =
        runBlocking {
            val core = FakeRustCore()
            core.on("phone_number.parse") { Outcome.Err(RustError.Invalid("phone number is empty")) }
            val out = NativeSharedOps(core).parseNumber(ParseNumberRequest(" "))
            assertTrue((out as Outcome.Err).error is RustError.Invalid)
        }

    @Test
    fun fakeCoreAnswersFromATypedLambdaAndRejectsABadRequest() =
        runBlocking {
            val core = FakeRustCore()
            core.onTyped("phone_number.same", SameNumberRequest.serializer(), SameNumberAnswer.serializer()) {
                Outcome.Ok(SameNumberAnswer(it.a == it.b))
            }
            val facade = NativeSharedOps(core)
            assertTrue(facade.sameNumber(SameNumberRequest("a", "a")).getOrThrow().same)
            assertFalse(facade.sameNumber(SameNumberRequest("a", "b")).getOrThrow().same)
            val bad = core.call("phone_number.same", JsonPrimitive(3))
            assertTrue((bad as Outcome.Err).error is RustError.Parse)
        }

    @Test
    fun aHandleReleasesOnceNoMatterHowOftenItIsClosed() {
        val released = mutableListOf<Long>()
        val handle = RustHandle(42L) { released += it }
        assertFalse(handle.released)
        handle.close()
        handle.close()
        handle.use { }
        assertTrue(handle.released)
        assertEquals(listOf(42L), released)
    }
}
