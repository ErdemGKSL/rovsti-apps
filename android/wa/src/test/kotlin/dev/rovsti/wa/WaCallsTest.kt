package dev.rovsti.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallStarted
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.wa.calls.AndroidCallAudioPump
import dev.rovsti.wa.calls.CallResult
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaCallsTest {
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val push = MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8)
    private val mic = MutableStateFlow<PermissionState>(PermissionState.Granted)
    private val pump = FakeCallPump()
    private val peer = "905551112233@s.whatsapp.net"

    private fun fake() =
        FakeWaGateway(link = link).apply {
            status =
                Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected, ownJid = "905550000000@s.whatsapp.net"))
        }

    private class Rig(
        val fake: FakeWaGateway,
        val calls: WaCalls,
    )

    /** The ids `ringOver` was told about, in order. */
    private val ringsOver = mutableListOf<String>()

    private fun TestScope.rig(
        fake: FakeWaGateway = fake(),
        clockMs: Long = 1_000,
    ): Rig {
        val repo = DefaultWaRepository(WaLinkStore(fake.ops), backgroundScope, fake.opener, fake.waEvents, push)
        repo.start()
        val calls = WaCalls(repo, mic, pump, backgroundScope, clock = { clockMs }, ringOver = { ringsOver += it })
        calls.start()
        return Rig(fake, calls)
    }

    private fun call(
        id: String,
        phase: CallPhase,
        direction: CallDirection = CallDirection.In,
    ) = GatewayEvent.Call(id, peer, "Ada", direction, phase, isVideo = false)

    private fun joined(): Boolean = pump.threads.all { it.apply { join(1_000) }.isAlive.not() }

    @Test fun acceptOpensTheAudioOnlyWhenTheCallIsConnectedThenEndStopsAndJoinsTheThreads() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.fake.emitEvent("inst1", 1, call("c1", CallPhase.Ringing))
            val result = r.calls.accept("c1")
            assertEquals(CallResult.Started("c1"), result)
            assertEquals("answered but not connected: no recorder, no audio socket", 0, pump.starts.get())
            assertEquals(0, r.fake.count("wa.call_audio_open"))
            r.fake.emitEvent("inst1", 2, call("c1", CallPhase.Connecting))
            assertEquals(0, pump.starts.get())
            r.fake.emitEvent("inst1", 3, call("c1", CallPhase.Active))
            assertEquals(1, pump.starts.get())
            assertEquals(2, pump.threads.size)
            assertTrue("both threads run", pump.threads.all { it.isAlive })
            assertEquals(1, r.fake.count("wa.call_audio_open"))

            assertTrue(r.calls.end("c1") is Outcome.Ok)
            assertEquals(1, pump.stops.get())
            assertTrue("the pump threads are gone within 1 s", joined())
            assertEquals(
                "the audio handle was closed",
                listOf(FakeWaGateway.AUDIO),
                r.fake.released.filter {
                    it ==
                        FakeWaGateway.AUDIO
                },
            )
        }

    @Test fun anOutgoingCallStartsTheAudioOnlyWhenTheCallIsActive() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            assertEquals(CallResult.Started("call-1"), r.calls.place(peer))
            assertEquals("ringing: no audio yet", 0, pump.starts.get())
            r.fake.emitEvent("inst1", 1, call("call-1", CallPhase.Ringing, CallDirection.Out))
            assertEquals(0, pump.starts.get())
            r.fake.emitEvent("inst1", 2, call("call-1", CallPhase.Active, CallDirection.Out))
            assertEquals(1, pump.starts.get())
            r.fake.emitEvent("inst1", 3, call("call-1", CallPhase.Ended("hangup")))
            assertEquals("an ended event stops the pump", 1, pump.stops.get())
            assertTrue(joined())
            assertEquals(
                CallPhase.Ended("hangup"),
                r.calls.calls.value
                    .single()
                    .phase,
            )
        }

    @Test fun withoutMicrophoneNothingIsSentAndNoRecorderStarts() =
        runTest(UnconfinedTestDispatcher()) {
            mic.value = PermissionState.DeniedOnce
            val r = rig()
            assertEquals(CallResult.NeedsMicrophone, r.calls.place(peer))
            assertEquals(CallResult.NeedsMicrophone, r.calls.accept("c1"))
            assertEquals(0, pump.starts.get())
            assertEquals(0, r.fake.count("wa.place_call"))
            assertEquals(0, r.fake.count("wa.accept_call"))
            assertEquals(0, r.fake.count("wa.call_audio_open"))
        }

    @Test fun theRealPumpRefusesToRecordWithoutTheGrant() {
        val context = RuntimeEnvironment.getApplication()
        val real = AndroidCallAudioPump(context)
        assertFalse(real.start(FakeFrames))
        assertFalse(real.stop())
    }

    @Test fun aRevokedGrantBetweenAcceptAndStartKeepsThePumpOff() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.fake.emitEvent("inst1", 1, call("c9", CallPhase.Active, CallDirection.Out))
            assertEquals(1, pump.starts.get())
            r.calls.close()
            assertTrue(joined())
            mic.value = PermissionState.RevokedWhileRunning
            r.fake.emitEvent("inst1", 2, call("c10", CallPhase.Active, CallDirection.Out))
            assertEquals("no second start without the grant", 1, pump.starts.get())
        }

    @Test fun rejectAndEndWorkWhenTheGatewayFailsAndStillSilenceTheAudio() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.calls.accept("c1")
            r.fake.emitEvent("inst1", 1, call("c1", CallPhase.Active))
            r.fake.callErrors["wa.end_call"] = RustError.Network("down")
            assertTrue(r.calls.end("c1") is Outcome.Err)
            assertEquals(1, pump.stops.get())
            assertTrue(joined())
        }

    @Test fun muteIsForwardedAndTracked() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            assertTrue(r.calls.mute("c1", true) is Outcome.Ok)
            val sent =
                r.fake.core.calls
                    .single { it.op == "wa.set_muted" }
                    .request
                    .toString()
            assertTrue(sent, sent.contains("\"c1\"") && sent.contains("true"))
            assertEquals(setOf("c1"), r.calls.mutedCalls.value)
            r.calls.mute("c1", false)
            assertEquals(emptySet<String>(), r.calls.mutedCalls.value)
        }

    @Test fun speakerGoesToThePump() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            assertTrue(r.calls.speaker(true))
            assertEquals(listOf(true), pump.speakerRequests)
            assertTrue(r.calls.speakerOn.value)
        }

    @Test fun theListLoadsFromTheGatewayThenFollowsEvents() =
        runTest(UnconfinedTestDispatcher()) {
            val fake =
                fake().apply {
                    calls =
                        listOf(
                            CallSummary("old", peer, "Ada", CallDirection.In, CallPhase.Ended("missed"), false, 10, 20),
                            CallSummary(
                                "newer",
                                peer,
                                null,
                                CallDirection.Out,
                                CallPhase.Ended("hangup"),
                                false,
                                50,
                                60,
                            ),
                        )
                }
            val r = rig(fake, clockMs = 777)
            assertEquals(
                listOf("newer", "old"),
                r.calls.calls.value
                    .map { it.callId },
            )
            fake.emitEvent("inst1", 1, call("live", CallPhase.Ringing))
            assertEquals(
                listOf("live", "newer", "old"),
                r.calls.calls.value
                    .map { it.callId },
            )
            assertEquals(
                777L,
                r.calls.calls.value
                    .first()
                    .startedMs,
            )
            fake.emitEvent("inst1", 2, call("live", CallPhase.Connecting))
            assertEquals("updated in place", 3, r.calls.calls.value.size)
            assertEquals(
                CallPhase.Connecting,
                r.calls.calls.value
                    .first()
                    .phase,
            )
        }

    @Test fun placeFailureIsReported() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = fake().apply { placed = Outcome.Err(RustError.Network("no route")) }
            val r = rig(fake)
            assertTrue(r.calls.place(peer) is CallResult.Failed)
        }

    private fun WaCalls.phaseOf(id: String) = calls.value.single { it.callId == id }.phase

    @Test fun acceptStopsTheRingAtOnceAndShowsTheCallConnecting() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.fake.emitEvent("inst1", 1, call("c1", CallPhase.Ringing))
            assertTrue(ringsOver.isEmpty())
            assertEquals(CallResult.Started("c1"), r.calls.accept("c1"))
            assertEquals("the ringing notification goes on answer", listOf("c1"), ringsOver.distinct())
            assertEquals(
                "the screen leaves Ringing without waiting for the gateway",
                CallPhase.Connecting,
                r.calls.phaseOf("c1"),
            )
            // A Ringing event the gateway sent before it saw the answer must not ring the screen again.
            r.fake.emitEvent("inst1", 2, call("c1", CallPhase.Ringing))
            assertEquals(CallPhase.Connecting, r.calls.phaseOf("c1"))
            r.fake.emitEvent("inst1", 3, call("c1", CallPhase.Active))
            assertEquals(CallPhase.Active, r.calls.phaseOf("c1"))
        }

    @Test fun aFailedAcceptRingsAgainAndReleasesTheMicrophoneService() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = fake().apply { callErrors["wa.accept_call"] = RustError.Network("down") }
            val r = rig(fake)
            fake.emitEvent("inst1", 1, call("c1", CallPhase.Ringing))
            assertTrue(r.calls.accept("c1") is CallResult.Failed)
            assertEquals(CallPhase.Ringing, r.calls.phaseOf("c1"))
            assertEquals(1, pump.prepares.get())
            assertEquals("the service started for the answer is stopped", 1, pump.stops.get())
        }

    @Test fun theMicrophoneServiceStartsAtTheAnswerBeforeTheRecorder() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.fake.emitEvent("inst1", 1, call("c1", CallPhase.Ringing))
            r.calls.accept("c1")
            assertEquals("prepared while the user answers (app in front)", 1, pump.prepares.get())
            assertEquals(0, pump.starts.get())
            r.fake.emitEvent("inst1", 2, call("c1", CallPhase.Active))
            assertEquals(1, pump.starts.get())
            assertEquals("the prepared service is not stopped and restarted from the background", 0, pump.stops.get())
            r.calls.end("c1")
            assertEquals(1, pump.stops.get())
        }

    @Test fun aCallThatEndsBeforeConnectingStopsThePreparedService() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            assertEquals(CallResult.Started("call-1"), r.calls.place(peer))
            assertEquals(1, pump.prepares.get())
            r.fake.emitEvent("inst1", 1, call("call-1", CallPhase.Ended("no_answer"), CallDirection.Out))
            assertEquals(1, pump.stops.get())
            assertEquals(0, pump.starts.get())
        }

    @Test fun declineAndAnyNonRingingPhaseStopTheRing() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.fake.emitEvent("inst1", 1, call("c1", CallPhase.Ringing))
            r.calls.reject("c1")
            assertEquals(listOf("c1"), ringsOver.distinct())
            // Answered on another device, or the caller gave up: the event ends the ring too.
            r.fake.emitEvent("inst1", 2, call("c2", CallPhase.Ringing))
            r.fake.emitEvent("inst1", 3, call("c2", CallPhase.Ended("elsewhere_answered")))
            assertEquals(listOf("c1", "c2"), ringsOver.distinct())
            // An outgoing call never had a ringing notification here.
            r.fake.emitEvent("inst1", 4, call("o1", CallPhase.Active, CallDirection.Out))
            assertFalse("o1" in ringsOver)
        }

    @Test fun theRealPumpStartsTheServiceBeforeTheRecorderAndStopsItAfter() {
        val order = mutableListOf<String>()
        val real =
            AndroidCallAudioPump(
                RuntimeEnvironment.getApplication(),
                startService = {
                    order += "service.start"
                    true
                },
                stopService = { order += "service.stop" },
                startAudio = { _, _ ->
                    order += "audio.start"
                    true
                },
                stopAudio = {
                    order += "audio.stop"
                    true
                },
            )
        real.prepare()
        assertTrue(real.start(FakeFrames))
        assertTrue(real.stop())
        assertEquals(listOf("service.start", "service.start", "audio.start", "audio.stop", "service.stop"), order)
    }

    private object FakeFrames : dev.rovsti.platform.wa.CallFrames {
        override fun push(pcm: ByteArray) = Unit

        override fun pull(out: ByteArray): Int = -1
    }
}
