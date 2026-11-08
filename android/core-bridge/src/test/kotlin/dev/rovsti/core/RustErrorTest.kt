package dev.rovsti.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustErrorTest {
    private fun envelope(
        kind: String,
        detail: String = "",
    ) = """{"err":{"kind":"$kind","message":"m-$kind"$detail}}""".encodeToByteArray()

    @Test
    fun everyKindMapsToItsSubclass() {
        val expected =
            mapOf(
                "invalid" to RustError.Invalid::class,
                "not_found" to RustError.NotFound::class,
                "io" to RustError.Io::class,
                "parse" to RustError.Parse::class,
                "network" to RustError.Network::class,
                "auth" to RustError.Auth::class,
                "unsupported" to RustError.Unsupported::class,
                "panic" to RustError.Panic::class,
            )
        assertEquals(RustErrorKinds.wire.toSet(), expected.keys)
        for ((kind, type) in expected) {
            val error = (Envelope.decode(envelope(kind)) as Outcome.Err).error
            assertEquals(kind, type, error::class)
            assertEquals("m-$kind", error.message)
            assertNull(error.detail)
        }
    }

    @Test
    fun detailIsKept() {
        val error =
            (Envelope.decode(envelope("io", ""","detail":{"path":"x"}""")) as Outcome.Err).error
        assertEquals(JsonObject(mapOf("path" to JsonPrimitive("x"))), error.detail)
    }

    @Test
    fun unknownKindIsUnknownNotACrash() {
        val error = (Envelope.decode(envelope("from_the_future")) as Outcome.Err).error
        assertTrue(error is RustError.Unknown)
        assertTrue(error.message!!.contains("from_the_future"))
        assertEquals("from_the_future", (error as RustError.Unknown).kind)
    }

    @Test
    fun malformedEnvelopesAreParseErrors() {
        for (bad in listOf(
            "",
            "[]",
            "{}",
            """{"ok":1,"err":{}}""",
            """{"ok":1,"x":2}""",
            """{"err":{"kind":"io"}}""",
        )) {
            val outcome = Envelope.decode(bad.encodeToByteArray())
            assertTrue(bad, (outcome as Outcome.Err).error is RustError.Parse)
        }
        assertTrue(((Envelope.decode(null)) as Outcome.Err).error is RustError.Panic)
    }
}
