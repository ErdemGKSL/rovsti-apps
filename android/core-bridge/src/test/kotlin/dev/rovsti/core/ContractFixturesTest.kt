package dev.rovsti.core

import dev.rovsti.core.model.core.DataDirInfo
import dev.rovsti.core.model.core.EchoProbe
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.FailRequest
import dev.rovsti.core.model.core.OkEnvelope
import dev.rovsti.core.model.core.VersionInfo
import dev.rovsti.core.model.headless.MmsNotificationAnswer
import dev.rovsti.core.model.headless.MmsNotificationRequest
import dev.rovsti.core.model.headless.MmsRetrievedAnswer
import dev.rovsti.core.model.headless.MmsRetrievedRequest
import dev.rovsti.core.model.headless.MmsSendReqAnswer
import dev.rovsti.core.model.headless.MmsSendReqRequest
import dev.rovsti.core.model.headless.MsgRemindAnswer
import dev.rovsti.core.model.headless.MsgRemindRequest
import dev.rovsti.core.model.headless.SmsClassifyAnswer
import dev.rovsti.core.model.headless.SmsClassifyRequest
import dev.rovsti.core.model.headless.TripAlarmAnswer
import dev.rovsti.core.model.headless.TripAlarmRequest
import dev.rovsti.core.model.headless.TripEndAnswer
import dev.rovsti.core.model.headless.TripEndRequest
import dev.rovsti.core.model.headless.TripFixAnswer
import dev.rovsti.core.model.headless.TripFixRequest
import dev.rovsti.core.model.headless.WaDigestAnswer
import dev.rovsti.core.model.headless.WaDigestRequest
import dev.rovsti.core.model.headless.WaReplyAnswer
import dev.rovsti.core.model.headless.WaReplyRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
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

    /** fixture stem (`core.version.request`) to the serializer of its model. */
    private val models: Map<String, KSerializer<*>> =
        buildMap {
            put("core.version.request", JsonNull.serializer())
            put("core.version.answer", OkEnvelope.serializer(VersionInfo.serializer()))
            put("core.data_dir.request", JsonNull.serializer())
            put("core.data_dir.answer", OkEnvelope.serializer(DataDirInfo.serializer()))
            put("core.echo.request", EchoProbe.serializer())
            put("core.echo.answer", OkEnvelope.serializer(EchoProbe.serializer()))
            for (kind in RustErrorKinds.wire) {
                put("core.fail.$kind.request", FailRequest.serializer())
                put("core.fail.$kind.answer", ErrEnvelope.serializer())
            }
            pair<SmsClassifyRequest, SmsClassifyAnswer>("sms_classify")
            pair<MmsNotificationRequest, MmsNotificationAnswer>("mms_notification")
            pair<MmsRetrievedRequest, MmsRetrievedAnswer>("mms_retrieved")
            pair<MmsSendReqRequest, MmsSendReqAnswer>("mms_send_req")
            pair<WaDigestRequest, WaDigestAnswer>("wa_digest")
            pair<WaReplyRequest, WaReplyAnswer>("wa_reply")
            pair<MsgRemindRequest, MsgRemindAnswer>("msg_remind")
            pair<TripFixRequest, TripFixAnswer>("trip_fix")
            pair<TripAlarmRequest, TripAlarmAnswer>("trip_alarm")
            pair<TripEndRequest, TripEndAnswer>("trip_end")
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.pair(kind: String) {
        put("headless.$kind.request", serializer<Req>())
        put("headless.$kind.answer", serializer<Ans>())
    }

    private fun fixtures(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    @Test
    fun fixturesExist() {
        // 3 + 8 ops and 10 headless kinds, two files each.
        assertEquals(2 * (3 + 8 + 10), fixtures().size)
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
