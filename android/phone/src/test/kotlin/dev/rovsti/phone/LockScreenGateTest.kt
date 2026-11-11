package dev.rovsti.phone

import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.platform.wa.WaIncoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock-screen policy (AD-1) for WhatsApp calls, which are not in `CallRegistry`: the full-screen intent of a ring
 * turns the flags on before the call list knows the call, the list keeps them on, and they never stay on without a
 * call. A fake clock runs the posted runnables.
 */
class LockScreenGateTest {
    private var now = 0L
    private val posted = mutableListOf<Pair<Long, Runnable>>()
    private val flags = mutableListOf<Boolean>()

    private val gate =
        LockScreenGate(
            setFlags = { flags += it },
            postDelayed = { r, delay -> posted += (now + delay) to r },
            removeCallbacks = { r -> posted.removeAll { it.second === r } },
        )

    private fun advance(ms: Long) {
        now += ms
        while (true) {
            val due = posted.filter { it.first <= now }.minByOrNull { it.first } ?: return
            posted.remove(due)
            due.second.run()
        }
    }

    private val on get() = flags.lastOrNull() == true

    private fun call(
        id: String,
        phase: CallPhase,
        startedMs: Long = now,
    ) = CallSummary(id, "905551112233@s.whatsapp.net", null, CallDirection.In, phase, false, startedMs)

    private fun list(vararg calls: CallSummary) {
        gate.onWaCalls(
            calls.any { LockScreenGate.holdsScreen(it, now) },
            calls.filter { it.phase is CallPhase.Ended }.mapTo(HashSet()) { it.callId },
        )
    }

    @Test fun aWhatsAppRingIntentTurnsTheFlagsOnOnAColdStart() {
        // onCreate of a cold process: no cellular call, the WhatsApp list still empty.
        gate.holdForWaIntent("c1")
        gate.onLiveCallChanged(false)
        list()
        assertTrue("shows over the keyguard and turns the screen on before the list has the call", on)
        advance(LockScreenGate.LINGER_MS * 2)
        assertTrue("the empty list does not clear a ring that is still on its way", on)
    }

    @Test fun theFlagsTurnOnWhenTheWhatsAppListGetsALiveCall() {
        gate.onLiveCallChanged(false)
        list()
        advance(LockScreenGate.LINGER_MS)
        assertFalse(on)
        list(call("c1", CallPhase.Ringing))
        assertTrue(on)
        list(call("c1", CallPhase.Active))
        assertTrue(on)
    }

    @Test fun theFlagsTurnOffAfterTheLingerWhenTheCallEnds() {
        gate.holdForWaIntent("c1")
        list(call("c1", CallPhase.Ringing))
        list(call("c1", CallPhase.Active))
        list(call("c1", CallPhase.Ended("hangup")))
        assertTrue("the Call ended summary is still seen", on)
        advance(LockScreenGate.LINGER_MS - 1)
        assertTrue(on)
        advance(1)
        assertFalse(on)
        assertFalse(gate.wanted)
    }

    @Test fun anIntentForACallThatIsAlreadyOverDropsTheHold() {
        gate.holdForWaIntent("c1")
        list(call("c1", CallPhase.Ended("remote")))
        advance(LockScreenGate.LINGER_MS)
        assertFalse(on)
    }

    @Test fun theHoldNeverOutlivesARingThatNeverShowsUp() {
        gate.holdForWaIntent("ghost")
        list()
        assertTrue(on)
        advance(WaIncoming.RING_TIMEOUT_MS - 1)
        assertTrue(on)
        advance(1)
        assertFalse("the hold timed out", gate.wanted)
        advance(LockScreenGate.LINGER_MS)
        assertFalse("never over the lock screen without a call", on)
        assertTrue("nothing left pending", posted.isEmpty())
    }

    @Test fun aCellularCallKeepsTheFlagsWhenTheWhatsAppCallEnds() {
        gate.onLiveCallChanged(true)
        list(call("c1", CallPhase.Active))
        list(call("c1", CallPhase.Ended("hangup")))
        advance(LockScreenGate.LINGER_MS * 2)
        assertTrue(on)
        gate.onLiveCallChanged(false)
        advance(LockScreenGate.LINGER_MS)
        assertFalse(on)
    }

    @Test fun aStaleRingingEntryDoesNotHoldTheScreen() {
        now = 10 * 60_000L
        assertFalse(LockScreenGate.holdsScreen(call("old", CallPhase.Ringing, startedMs = 0), now))
        assertTrue(LockScreenGate.holdsScreen(call("new", CallPhase.Ringing), now))
        assertTrue(LockScreenGate.holdsScreen(call("long", CallPhase.Active, startedMs = 0), now))
        assertTrue(LockScreenGate.holdsScreen(call("c", CallPhase.Connecting), now))
        assertFalse(LockScreenGate.holdsScreen(call("e", CallPhase.Ended("x")), now))
    }
}
