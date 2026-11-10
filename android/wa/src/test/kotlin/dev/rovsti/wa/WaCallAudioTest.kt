package dev.rovsti.wa

import android.Manifest
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.CallAudio
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.wa.CallFrames
import dev.rovsti.platform.wa.WaCallAudio
import dev.rovsti.platform.wa.WaCallService
import dev.rovsti.wa.calls.AndroidCallAudioPump
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone end of the call audio chain (docs/kotlin-ui/wa-call-audio.md): the PCM format and the voice-call
 * attributes the platform sees, the mode and focus a started call takes, and that the audio opens only once the call
 * is connected. The Rust hops are covered by `rovsti_wa_client/tests/call_audio.rs` and the gateway tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaCallAudioTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private object Silence : CallFrames {
        override fun push(pcm: ByteArray) = Unit

        override fun pull(out: ByteArray): Int = -1
    }

    @After fun stopTheCall() {
        WaCallAudio.stop(context)
    }

    @Test fun framesAreSixtyMillisecondsOf16kHzMonoPcm16AndMatchTheBridge() {
        assertEquals(16_000, WaCallAudio.SAMPLE_RATE)
        assertEquals(960, WaCallAudio.FRAME_SAMPLES)
        assertEquals(1_920, WaCallAudio.FRAME_BYTES)
        assertEquals("one frame is 60 ms", 60, WaCallAudio.FRAME_SAMPLES * 1000 / WaCallAudio.SAMPLE_RATE)
        assertEquals(CallAudio.FRAME_BYTES, WaCallAudio.FRAME_BYTES)
        for (mask in listOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_OUT_MONO)) {
            val format = WaCallAudio.format(mask)
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, format.encoding)
            assertEquals(16_000, format.sampleRate)
            assertEquals(1, format.channelCount)
        }
    }

    @Test fun theTrackIsAVoiceCallAndTheMicrophoneTheCommunicationSource() {
        val attributes = WaCallAudio.trackAttributes()
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, attributes.contentType)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, WaCallAudio.RECORD_SOURCE)
    }

    @Test fun theSpeakerBufferHoldsAtLeastFourFrames() {
        assertEquals(4 * 1_920, WaCallAudio.bufferBytes(100))
        assertEquals(50_000, WaCallAudio.bufferBytes(50_000))
    }

    @Test fun routesPickEarpieceSpeakerOrBluetoothHeadset() {
        val types =
            listOf(
                android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            )
        assertEquals(0, WaCallAudio.pickDevice(types, WaCallAudio.Route.Earpiece))
        assertEquals(1, WaCallAudio.pickDevice(types, WaCallAudio.Route.Speaker))
        assertEquals("SCO before BLE", 3, WaCallAudio.pickDevice(types, WaCallAudio.Route.Bluetooth))
        assertEquals(-1, WaCallAudio.pickDevice(types.take(2), WaCallAudio.Route.Bluetooth))
    }

    @Test fun withoutTheMicrophoneGrantNothingStarts() {
        assertFalse(WaCallAudio.start(context, Silence))
        assertFalse(WaCallAudio.running)
        assertEquals(AudioManager.MODE_NORMAL, context.getSystemService(AudioManager::class.java).mode)
    }

    @Test fun aStartedCallTakesCommunicationModeAndFocusAndStopGivesThemBack() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = context.getSystemService(AudioManager::class.java)
        assertTrue(WaCallAudio.start(context, Silence))
        assertTrue(WaCallAudio.running)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        val focus = shadowOf(audio).lastAudioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, focus.audioFocusRequest.focusGain)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, focus.audioFocusRequest.audioAttributes.usage)

        assertTrue(WaCallAudio.stop(context))
        assertFalse(WaCallAudio.running)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }

    @Test fun theForegroundServiceClaimsTheMicrophoneAndPhoneCallOnlyForTheDialer() {
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val call = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        assertEquals(mic, WaCallService.typesFor(34, dialer = false))
        assertEquals(mic or call, WaCallService.typesFor(34, dialer = true))
        assertEquals(0, WaCallService.typesFor(29, dialer = true))
    }

    // --- the audio opens once the call is connected -----------------------------------------------------------

    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val peer = "905551112233@s.whatsapp.net"

    private fun call(
        phase: CallPhase,
        direction: CallDirection,
    ) = GatewayEvent.Call("c1", peer, "Ada", direction, phase, isVideo = false)

    private fun kotlinx.coroutines.test.TestScope.callsWithTheRealPump(): Pair<FakeWaGateway, WaCalls> {
        val fake =
            FakeWaGateway(link = link).apply {
                status =
                    Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected, ownJid = "905550000000@s.whatsapp.net"))
            }
        val repo =
            DefaultWaRepository(
                WaLinkStore(fake.ops),
                backgroundScope,
                fake.opener,
                fake.waEvents,
                MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8),
            )
        repo.start()
        val calls =
            WaCalls(
                repo,
                MutableStateFlow<PermissionState>(PermissionState.Granted),
                AndroidCallAudioPump(context),
                backgroundScope,
            )
        calls.start()
        return fake to calls
    }

    @Test fun anIncomingCallRecordsOnlyAfterItIsConnected() =
        runTest(UnconfinedTestDispatcher()) {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val (fake, calls) = callsWithTheRealPump()
            fake.emitEvent("inst1", 1, call(CallPhase.Ringing, CallDirection.In))
            calls.accept("c1")
            fake.emitEvent("inst1", 2, call(CallPhase.Connecting, CallDirection.In))
            assertFalse("answered, media not up", WaCallAudio.running)
            assertEquals(0, fake.count("wa.call_audio_open"))
            fake.emitEvent("inst1", 3, call(CallPhase.Active, CallDirection.In))
            assertTrue("connected", WaCallAudio.running)
            assertEquals(1, fake.count("wa.call_audio_open"))
            fake.emitEvent("inst1", 4, call(CallPhase.Ended("hangup"), CallDirection.In))
            assertFalse("ended", WaCallAudio.running)
        }

    @Test fun anOutgoingCallRecordsOnlyAfterThePeerPicksUp() =
        runTest(UnconfinedTestDispatcher()) {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val (fake, calls) = callsWithTheRealPump()
            calls.place(peer)
            fake.emitEvent("inst1", 1, call(CallPhase.Ringing, CallDirection.Out))
            assertFalse("ringing", WaCallAudio.running)
            fake.emitEvent("inst1", 2, call(CallPhase.Active, CallDirection.Out))
            assertTrue(WaCallAudio.running)
            calls.close()
            assertFalse(WaCallAudio.running)
        }
}
