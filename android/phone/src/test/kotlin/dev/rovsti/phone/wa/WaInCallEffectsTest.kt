package dev.rovsti.phone.wa

import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.platform.wa.ProximityLock
import dev.rovsti.platform.wa.WaOngoingCall
import dev.rovsti.platform.wa.WaProximity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A live WhatsApp call darkens the screen at the ear and names itself on the ongoing notification. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaInCallEffectsTest {
    private class FakeLock : ProximityLock {
        val log = mutableListOf<String>()
        override val supported = true

        override fun acquire() {
            log += "acquire"
        }

        override fun release() {
            log += "release"
        }
    }

    private val calls = MutableStateFlow<List<CallSummary>>(emptyList())
    private val muted = MutableStateFlow<Set<String>>(emptySet())
    private val speaker = MutableStateFlow(false)
    private val earpiece = MutableStateFlow(true)
    private val lock = FakeLock()
    private val proximity = WaProximity(lock)
    private val described = mutableListOf<WaOngoingCall?>()
    private var now = 1_000L

    private fun call(
        phase: CallPhase,
        direction: CallDirection = CallDirection.In,
        id: String = "c1",
    ) = CallSummary(id, "905551112233@s.whatsapp.net", "Ada", direction, phase, false, 500)

    private fun TestScope.start() {
        WaInCallEffects(calls, muted, speaker, earpiece, proximity, { described += it }, backgroundScope, { now })
            .start()
    }

    @Test fun theLockFollowsTheCallTheRouteAndMute() =
        runTest(UnconfinedTestDispatcher()) {
            start()
            calls.value = listOf(call(CallPhase.Connecting))
            assertFalse("connecting: no voice yet", proximity.holding)
            calls.value = listOf(call(CallPhase.Active))
            assertTrue(proximity.holding)
            speaker.value = true
            assertFalse("the loudspeaker", proximity.holding)
            speaker.value = false
            earpiece.value = false
            assertFalse("a headset", proximity.holding)
            earpiece.value = true
            muted.value = setOf("c1")
            assertFalse("muted", proximity.holding)
            muted.value = emptySet()
            calls.value = listOf(call(CallPhase.Ended("hangup")))
            assertFalse("ended", proximity.holding)
            assertEquals(List(4) { listOf("acquire", "release") }.flatten(), lock.log)
        }

    @Test fun theAppGoingAwayReleasesTheLock() =
        runTest(UnconfinedTestDispatcher()) {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            WaInCallEffects(calls, muted, speaker, earpiece, proximity, { described += it }, scope, { now }).start()
            calls.value = listOf(call(CallPhase.Active))
            assertTrue(proximity.holding)
            scope.cancel()
            assertFalse(proximity.holding)
            assertEquals("release", lock.log.last())
        }

    @Test fun theOngoingNoticeNamesTheCallAndWhenItConnected() =
        runTest(UnconfinedTestDispatcher()) {
            start()
            calls.value = listOf(call(CallPhase.Ringing, CallDirection.Out))
            assertEquals(WaOngoingCall("c1", "Ada", 0L), described.last())
            now = 7_000L
            calls.value = listOf(call(CallPhase.Active, CallDirection.Out))
            assertEquals(WaOngoingCall("c1", "Ada", 7_000L), described.last())
            now = 9_000L
            muted.value = setOf("c1")
            assertEquals("the connect time stays", 7_000L, described.last()?.connectedMs)
            calls.value = listOf(call(CallPhase.Ended("hangup"), CallDirection.Out))
            assertNull(described.last())
        }

    @Test fun anIncomingRingIsNotTheLiveCall() {
        assertNull(WaInCallEffects.liveCall(listOf(call(CallPhase.Ringing))))
        val active = call(CallPhase.Active, id = "c2")
        assertEquals(active, WaInCallEffects.liveCall(listOf(call(CallPhase.Ringing), active)))
    }
}
