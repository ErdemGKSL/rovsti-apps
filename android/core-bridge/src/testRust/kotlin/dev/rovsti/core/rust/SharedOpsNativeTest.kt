package dev.rovsti.core.rust

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.shared.CallingCodeRequest
import dev.rovsti.core.model.shared.ContactQrRequest
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.core.model.shared.ParseNumberRequest
import dev.rovsti.core.model.shared.QrNumber
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.ops.NativeSharedOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The shared facade against the real host library, and every shared fixture request against its answer file. */
class SharedOpsNativeTest {
    private val ops = NativeSharedOps(TestRust.core)
    private val dir = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))

    @Before
    fun load() = TestRust.ensure()

    @Test
    fun typedCallsAnswerLikeTheFixtures() =
        runBlocking {
            val parsed = ops.parseNumber(ParseNumberRequest("0532 123 45 67")).getOrOk()
            assertEquals("05321234567", parsed.canonical)
            assertEquals(
                "+905321234567",
                ops.render(RenderRequest("0532 123 45 67", NumberForm.E164, homeCc = "90")).getOrOk().text,
            )
            assertEquals(
                "Germany",
                ops
                    .countryOfCc(CallingCodeRequest("49"))
                    .getOrOk()
                    .country
                    ?.name,
            )
            val qr = ops.contactQr(ContactQrRequest("Ayse", listOf(QrNumber("0532 123 45 67")), "90")).getOrOk()
            assertTrue(qr.payload.startsWith("BEGIN:VCARD"))
            assertEquals(1, qr.kept)
        }

    @Test
    fun everySharedFixtureRequestGivesItsAnswerFile() =
        runBlocking {
            val stems =
                File(dir, "INDEX").readLines().filter { l ->
                    listOf("phone_number.", "format.", "calling_code.", "copy.").any { l.startsWith(it) }
                }
            assertTrue(stems.isNotEmpty())
            for (stem in stems) {
                val op = ops(stem)
                val request = File(dir, "$stem.request.json").readText().trim()
                val answer = File(dir, "$stem.answer.json").readText().trim()
                val live = TestRust.core.call(op, Envelope.json.parseToJsonElement(request))
                val wanted = Envelope.decode(answer.encodeToByteArray())
                assertEquals(stem, shape(wanted), shape(live))
            }
        }

    /** The op of a stem: the longest registered op name that is a prefix of it. */
    private fun ops(stem: String): String =
        dev.rovsti.core.ops.SharedOps.ops
            .filter { stem == it || stem.startsWith("$it.") }
            .maxByOrNull { it.length } ?: error("no op for $stem")

    /** Outcomes compared by value: errors are exceptions without equality. */
    private fun shape(outcome: Outcome<*>): String =
        when (outcome) {
            is Outcome.Ok -> "ok ${outcome.value}"
            is Outcome.Err -> "err ${outcome.error::class.simpleName} ${outcome.error.message} ${outcome.error.detail}"
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
