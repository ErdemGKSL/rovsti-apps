package dev.rovsti.phone.diagnostics

import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.telephony.CallsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class StubCalls(
    private val events: List<String> = emptyList(),
    private val latency: Long = -1,
) : CallsApi {
    override fun snapshot() = "{}"

    override fun answer(callId: Int) = false

    override fun reject(callId: Int) = false

    override fun disconnect(callId: Int) = false

    override fun hold(
        callId: Int,
        on: Boolean,
    ) = false

    override fun mute(on: Boolean) = false

    override fun route(name: String) = false

    override fun dtmf(digit: Char) = false

    override fun dtmfStop() = false

    override fun answerReplacing(callId: Int) = false

    override fun eventDump() = events

    override fun routeLatencyMs() = latency

    override fun blockGuardHits() = 2

    override fun blockGuardOn() = true

    override fun silenceCallbacks() = 5
}

/** E5.1 Diagnostics: the counters, the ring timeline, the event ring and one copyable report. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @Before fun main() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun reset() = Dispatchers.resetMain()

    private val clipboard = FakeClipboardRepository()

    private fun model(
        calls: CallsApi = StubCalls(),
        core: String? = "0.1.0",
        ring: List<String> = emptyList(),
    ) = DiagnosticsViewModel(calls, { core }, { ring }, clipboard, appVersion = "0.1.0")

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test fun theSectionsShowWhatTheCallOwnerKnows() {
        val vm =
            model(
                StubCalls(listOf("onBind none", "onCallAdded 1 ringing"), latency = 120),
                ring = listOf("notify 9 ms"),
            )
        val state = wait { vm.state.first { it.sections.isNotEmpty() } }
        assertEquals(listOf("App", "Counters", "Last ring", "Call events"), state.sections.map { it.title })
        val app = state.sections[0].rows.associate { it.label to it.value }
        assertEquals("0.1.0", app["Version"])
        assertEquals("0.1.0", app["Rust core"])
        val counters = state.sections[1].rows.associate { it.label to it.value }
        assertEquals("on", counters["Block guard"])
        assertEquals("2", counters["Calls rejected by the block guard"])
        assertEquals("5", counters["Silence-ringer callbacks"])
        assertEquals("120 ms", counters["Audio route change"])
        assertEquals(listOf("notify 9 ms"), state.sections[2].lines)
        assertEquals(listOf("onBind none", "onCallAdded 1 ringing"), state.sections[3].lines)
    }

    @Test fun aCoreThatIsNotLoadedAndNoLatencyAreSaidPlainly() {
        val state = wait { model(core = null).state.first { it.sections.isNotEmpty() } }
        assertEquals(
            "not loaded",
            state.sections[0]
                .rows
                .single { it.label == "Rust core" }
                .value,
        )
        assertEquals(
            "not measured",
            state.sections[1]
                .rows
                .single { it.label == "Audio route change" }
                .value,
        )
        assertTrue("an empty section says none", "Call events\n  none" in state.report)
    }

    @Test fun copyAllPutsTheWholeReportOnTheClipboardOnce() {
        val vm = model(StubCalls(listOf("onBind none")), ring = listOf("first_frame 812 ms"))
        wait { vm.state.first { it.sections.isNotEmpty() } }
        vm.onAction(DiagnosticsAction.CopyAll)
        assertEquals(DiagnosticsEffect.Copied(true), wait { vm.effects.first() })
        val copied = clipboard.copies.single().first
        assertTrue(copied.startsWith("Rovsti Phone diagnostics"))
        assertTrue("onBind none" in copied)
        assertTrue("first_frame 812 ms" in copied)
        assertTrue("Version: 0.1.0" in copied)
    }

    @Test fun aRefusedCopyIsReported() {
        clipboard.accepts = false
        val vm = model()
        wait { vm.state.first { it.sections.isNotEmpty() } }
        vm.onAction(DiagnosticsAction.CopyAll)
        assertEquals(DiagnosticsEffect.Copied(false), wait { vm.effects.first() })
    }

    @Test fun theReportNeverHoldsANumber() {
        val state = wait { model(StubCalls(listOf("onCallAdded 1 ringing"))).state.first { it.sections.isNotEmpty() } }
        assertFalse(Regex("\\+?\\d{7,}").containsMatchIn(state.report))
    }

    @Test fun theSeventhQuickTapOpensTheHiddenPage() {
        val taps = SevenTaps(windowMs = 1_000)
        var now = 0L
        repeat(6) { assertFalse(taps.tap(now.also { now += 200 })) }
        assertTrue(taps.tap(now))
        // The count starts over.
        assertFalse(taps.tap(now + 200))
    }

    @Test fun aPauseBetweenTapsStartsTheCountAgain() {
        val taps = SevenTaps(windowMs = 1_000)
        repeat(5) { assertFalse(taps.tap(it * 100L)) }
        assertFalse("too late: a new sequence", taps.tap(5_000))
        repeat(5) { assertFalse(taps.tap(5_100L + it * 100)) }
        assertTrue("seven in a row after the pause", taps.tap(5_700))
    }
}
