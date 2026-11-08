package dev.rovsti.core.rust

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustNative
import dev.rovsti.core.getOrThrow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The real host `librovsti_ffi` through the real Kotlin bridge. */
class RustBridgeTest {
    private val core get() = TestRust.core

    @Before
    fun load() = TestRust.ensure()

    @Test
    fun versionRoundTrip() =
        runBlocking {
            val version = core.version().getOrThrow()
            assertTrue(version, version.matches(Regex("""\d+\.\d+\.\d+.*""")))
        }

    @Test
    fun oneMegabyteEcho() =
        runBlocking {
            val text = "aé中".repeat(350_000).take(1_000_000)
            val back = core.call("core.echo", JsonPrimitive(text)).getOrThrow()
            assertEquals(text, (back as JsonPrimitive).content)
        }

    @Test
    fun everyErrorKindMapsToItsSubclass() =
        runBlocking {
            val expected =
                mapOf<String, Class<out RustError>>(
                    "invalid" to RustError.Invalid::class.java,
                    "not_found" to RustError.NotFound::class.java,
                    "io" to RustError.Io::class.java,
                    "parse" to RustError.Parse::class.java,
                    "network" to RustError.Network::class.java,
                    "auth" to RustError.Auth::class.java,
                    "unsupported" to RustError.Unsupported::class.java,
                    "panic" to RustError.Panic::class.java,
                )
            for ((kind, type) in expected) {
                val body =
                    buildJsonObject {
                        put("kind", kind)
                        put("message", "m-$kind")
                        put("detail", buildJsonObject { put("k", 1) })
                    }
                val error = (core.call("core.fail", body) as Outcome.Err).error
                assertEquals(kind, type, error.javaClass)
                assertEquals("m-$kind", error.message)
                assertEquals(buildJsonObject { put("k", 1) }, error.detail)
            }
        }

    @Test
    fun panicMapsToPanicAndTheNextCallWorks() =
        runBlocking {
            val error = (core.call("core.panic") as Outcome.Err).error
            assertTrue(error is RustError.Panic)
            assertTrue(error.message, error.message!!.contains("core.panic"))
            assertTrue(core.version() is Outcome.Ok)
        }

    @Test
    fun unknownOpIsUnsupported() =
        runBlocking {
            val error = (core.call("nope.nope") as Outcome.Err).error
            assertTrue(error is RustError.Unsupported)
        }

    @Test
    fun dataDirIsWhatInitReceived() =
        runBlocking {
            val body = core.call("core.data_dir").getOrThrow() as JsonObject
            assertEquals("dev.rovsti.test", (body["app_id"] as JsonPrimitive).content)
            assertEquals(34, (body["sdk"] as JsonPrimitive).content.toInt())
        }

    @Test
    fun headlessUnknownKindIsEmpty() =
        runBlocking {
            assertEquals("", core.headless("", "{}"))
            assertEquals("", core.headless("no_such_kind", "{}"))
        }

    @Test
    fun smsClassifyEqualsTheStep5Golden() =
        runBlocking {
            val fixtures = File(System.getProperty("rovsti.fixtures") ?: error("rovsti.fixtures not set"))
            val dir = File(fixtures, "dirs/messages")
            val tmp =
                kotlin.io.path
                    .createTempDirectory("rovsti-golden")
                    .toFile()
            dir.copyRecursively(tmp, overwrite = true)
            val request =
                File(fixtures, "headless/sms_classify.request.json")
                    .readText()
                    .trim()
                    .replace("\$DIR", tmp.absolutePath)
            val golden = File(fixtures, "headless/sms_classify.answer.json").readText()
            assertEquals(golden, core.headless("sms_classify", request))
            tmp.deleteRecursively()
            Unit
        }

    @Test
    fun nativeTileAndAudioStubsDoNotCrash() {
        RustNative.audioPush(1L, ByteArray(4))
        assertEquals(0, RustNative.audioPull(1L, ByteArray(4)))
        RustNative.release(12345L)
        assertTrue(RustNative.tile(1L, 0, 0, 0, 0) != null)
    }
}
