package dev.rovsti.core.rust

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustNative
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.DialTextRequest
import dev.rovsti.core.model.phone.GroupRequest
import dev.rovsti.core.model.phone.PastePlan
import dev.rovsti.core.model.phone.ToneStep
import dev.rovsti.core.model.phone.TrackerViewRequest
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.PhoneOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Phone facade against the real host library, and every self-contained Phone fixture against its answer file. */
class PhoneOpsNativeTest {
    private val ops = NativePhoneOps(TestRust.core)
    private val dir = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))

    @Before
    fun load() = TestRust.ensure()

    @Test
    fun typedCallsAnswerLikeTheFixtures() =
        runBlocking {
            val rows =
                ops
                    .recentsGroup(
                        GroupRequest(
                            listOf(
                                CallLogEntry(1, "0532 123 45 67", "+905321234567", CallLogKind.Missed, 1_000_000),
                                CallLogEntry(2, "0532 123 45 67", "+905321234567", CallLogKind.Missed, 900_000),
                                CallLogEntry(3, "0212 555 01 02", "+902125550102", CallLogKind.Outgoing, 800_000, 60),
                            ),
                        ),
                    ).getOrOk()
                    .rows
            assertEquals(2, rows.size)
            assertEquals(2, rows[0].count)
            assertEquals("+902125550102", rows[1].normalized)
            val plan = ops.pastePlan(DialTextRequest("0532 123 45 67 / 0212 555 01 02")).getOrOk().plan
            assertTrue(plan is PastePlan.Choose)
            val tones = ops.tonePlan(DialTextRequest("12,3")).getOrOk()
            assertEquals(
                listOf(ToneStep.Tone('1'), ToneStep.Tone('2'), ToneStep.Pause, ToneStep.Tone('3')),
                tones.steps,
            )
        }

    @Test
    fun trackerHandleLifecycle() =
        runBlocking {
            val handle = ops.trackerNew().getOrOk().handle
            try {
                val empty =
                    ops
                        .trackerView(
                            dev.rovsti.core.model.phone
                                .TrackerViewRequest(handle, 1_000),
                        ).getOrOk()
                assertTrue(empty.empty)
            } finally {
                dev.rovsti.core.RustNative
                    .release(handle)
            }
            val after =
                ops.trackerView(
                    dev.rovsti.core.model.phone
                        .TrackerViewRequest(handle, 1_000),
                )
            assertTrue((after as Outcome.Err).error is RustError.NotFound)
        }

    @Test
    fun everySelfContainedPhoneFixtureRequestGivesItsAnswerFile() =
        runBlocking {
            val stems =
                File(dir, "INDEX").readLines().filter { stem ->
                    PhoneOps.ops.any { stem == it || stem.startsWith("$it.") }
                }
            var ran = 0
            for (stem in stems) {
                val request = File(dir, "$stem.request.json").readText().trim()
                // Handle and seed-directory cases need a live object or files; Rust runs those itself.
                val answer = File(dir, "$stem.answer.json").readText()
                if ("7777777777" in request || "7777777777" in answer || "\$DIR" in request) continue
                val op = PhoneOps.ops.filter { stem == it || stem.startsWith("$it.") }.maxBy { it.length }
                val live = TestRust.core.call(op, Envelope.json.parseToJsonElement(request))
                val wanted = Envelope.decode(File(dir, "$stem.answer.json").readText().trim().encodeToByteArray())
                assertEquals(stem, shape(wanted), shape(live))
                ran++
            }
            assertTrue("ran $ran fixtures", ran > 100)
        }

    private fun shape(outcome: Outcome<*>): String =
        when (outcome) {
            is Outcome.Ok -> "ok ${outcome.value}"
            is Outcome.Err -> "err ${outcome.error::class.simpleName} ${outcome.error.message} ${outcome.error.detail}"
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
