package dev.rovsti.phone.wa

import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.platform.wa.WaIncoming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live-event path posts the ringing notification once per call (it used to come only from the FCM push). */
class WaRingNotifierTest {
    private val now = 100_000L
    private val shown = mutableListOf<Pair<String, String>>()
    private val notifier =
        WaRingNotifier(MutableStateFlow(emptyList()), TestScope(), { now }) { id, title -> shown += id to title }

    private fun call(
        id: String,
        phase: CallPhase = CallPhase.Ringing,
        direction: CallDirection = CallDirection.In,
        name: String? = "Ada",
        startedMs: Long = now - 1_000,
        peer: String = "905551112233@s.whatsapp.net",
    ) = CallSummary(id, peer, name, direction, phase, false, startedMs)

    @Test fun anIncomingRingIsPostedOnceWithTheCallersName() {
        notifier.offer(listOf(call("c1")))
        notifier.offer(listOf(call("c1")))
        assertEquals(listOf("c1" to "Ada"), shown)
    }

    @Test fun onlyFreshIncomingRingsArePosted() {
        notifier.offer(
            listOf(
                call("out", direction = CallDirection.Out),
                call("active", phase = CallPhase.Active),
                call("ended", phase = CallPhase.Ended("remote")),
                call("stale", startedMs = now - WaIncoming.RING_TIMEOUT_MS),
            ),
        )
        assertEquals(emptyList<Pair<String, String>>(), shown)
    }

    @Test fun theTitleFallsBackToTheNumberThenTheGenericOne() {
        assertEquals("+905551112233", WaRingNotifier.titleOf(call("a", name = " ")))
        assertEquals("WhatsApp call", WaRingNotifier.titleOf(call("b", name = null, peer = "123@lid")))
    }
}
