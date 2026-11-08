package dev.rovsti.core

import dev.rovsti.core.contracts.AllContracts
import dev.rovsti.core.model.core.VersionInfo
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.SearchOps
import dev.rovsti.core.ops.SharedOps
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * E2.7: every file under `crates/rovsti_ffi/fixtures` decodes strictly into its Kotlin model and re-encodes to the
 * same JSON. A fixture without a model fails here, so an op or kind cannot be added to Rust without a model.
 * Plain JVM test: no native library.
 */
class ContractFixturesTest {
    private val dir = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))

    private val strict =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = true
        }

    /** fixture stem (`core.version.request`) to the serializer of its model, from the per-domain `ContractModels`. */
    private val models: Map<String, KSerializer<*>> = AllContracts.models

    private fun fixtures(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    /** The stems the Rust fixture test wrote to `INDEX`, one per line (`format.render`, `format.render.bad_form`). */
    private fun indexStems(): List<String> = File(dir, "INDEX").readLines().filter { it.isNotBlank() }

    @Test
    fun fixturesExist() {
        val stems = indexStems()
        assertEquals("INDEX lists a stem twice", stems.size, stems.toSet().size)
        val expected = stems.flatMap { listOf("$it.request", "$it.answer") }.sorted()
        assertEquals(expected, fixtures().map { it.name.removeSuffix(".json") }.sorted())
    }

    /** Every op a Kotlin facade names has a contract fixture, so no facade method can exist without Rust coverage. */
    @Test
    fun everyFacadeOpHasAFixture() {
        val facades =
            mapOf(
                "SharedOps" to SharedOps.ops,
                "SearchOps" to SearchOps.ops,
                "PhoneOps" to PhoneOps.ops,
            )
        val missing =
            facades.flatMap { (facade, ops) ->
                ops
                    .filter { !File(dir, "$it.request.json").isFile || !File(dir, "$it.answer.json").isFile }
                    .map { "$facade: $it" }
            }
        assertEquals("facade ops without a fixture", emptyList<String>(), missing)
        for ((facade, ops) in facades) assertEquals("$facade lists an op twice", ops.size, ops.toSet().size)
    }

    @Test
    fun everyFixtureHasAModel() {
        val missing = fixtures().map { it.name.removeSuffix(".json") }.filter { it !in models }
        assertTrue("fixtures without a Kotlin model: $missing (add one to dev.rovsti.core.model)", missing.isEmpty())
    }

    @Test
    fun everyModelHasAFixture() {
        val present = fixtures().map { it.name.removeSuffix(".json") }.toSet()
        assertEquals(emptyList<String>(), models.keys.filter { it !in present })
    }

    @Test
    fun everyFixtureDecodesStrictlyAndReEncodesToTheSameJson() {
        val failures = mutableListOf<String>()
        for (file in fixtures()) {
            val stem = file.name.removeSuffix(".json")
            val serializer = models[stem] ?: continue
            val text = file.readText()
            try {
                val original = strict.parseToJsonElement(text)

                @Suppress("UNCHECKED_CAST")
                val model = strict.decodeFromString(serializer as KSerializer<Any?>, text)
                val again = strict.encodeToJsonElement(serializer, model)
                if (!same(original, again)) failures += "$stem: re-encoded to $again, fixture is $original"
            } catch (e: Exception) {
                failures += "$stem: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun errorAnswersMapToRustErrorThroughTheEnvelope() {
        for (kind in RustErrorKinds.wire) {
            val bytes = File(dir, "core.fail.$kind.answer.json").readBytes()
            val outcome = Envelope.decode(bytes) as Outcome.Err
            assertEquals(kind, RustErrorKinds.nameOf(outcome.error))
            assertEquals("requested $kind failure", outcome.error.message)
        }
        val io = (Envelope.decode(File(dir, "core.fail.io.answer.json").readBytes()) as Outcome.Err).error
        assertEquals(13, ((io.detail as JsonObject)["os_error"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun okAnswersDecodeThroughTheEnvelope() {
        val version =
            Envelope.decode(File(dir, "core.version.answer.json").readBytes(), VersionInfo.serializer())
        assertEquals(1, version.getOrThrow().abi)
    }

    /** JSON equality with numbers compared as numbers (1.0 and 1, long double renderings). */
    private fun same(
        a: JsonElement,
        b: JsonElement,
    ): Boolean =
        when {
            a is JsonObject && b is JsonObject -> {
                a.keys == b.keys &&
                    a.keys.all { same(a.getValue(it), b.getValue(it)) }
            }

            a is JsonArray && b is JsonArray -> {
                a.size == b.size && a.indices.all { same(a[it], b[it]) }
            }

            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString -> {
                a.content == b.content || a.content.toDoubleOrNull()?.let { it == b.content.toDoubleOrNull() } == true
            }

            else -> {
                a == b
            }
        }
}

/** The wire names of the eight error kinds, in the order of the Rust `ErrorKind`. */
internal object RustErrorKinds {
    val wire = listOf("invalid", "not_found", "io", "parse", "network", "auth", "unsupported", "panic")

    fun nameOf(error: RustError): String =
        when (error) {
            is RustError.Invalid -> "invalid"
            is RustError.NotFound -> "not_found"
            is RustError.Io -> "io"
            is RustError.Parse -> "parse"
            is RustError.Network -> "network"
            is RustError.Auth -> "auth"
            is RustError.Unsupported -> "unsupported"
            is RustError.Panic -> "panic"
            is RustError.Unknown -> error.kind
        }
}
