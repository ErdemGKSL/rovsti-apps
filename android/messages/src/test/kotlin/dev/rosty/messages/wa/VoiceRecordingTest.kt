package dev.rosty.messages.wa

import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.core.model.wa.TypingState
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.conversation.RecordPhase
import dev.rosty.platform.audio.FakeVoiceRecorder
import dev.rosty.platform.audio.RecorderStop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The record state machine of the composer's microphone, on a fake microphone and the virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecordingTest {
    @get:Rule val folder = TemporaryFolder()

    private class Rig(
        val recording: VoiceRecording,
        val mic: FakeVoiceRecorder,
        val player: FakeVoiceEngine,
        val takes: MutableList<VoiceTake>,
        val hints: MutableList<UiText>,
        val states: MutableList<TypingState>,
        val discarded: MutableList<File>,
    )

    private fun TestScope.rig(
        mic: FakeVoiceRecorder = FakeVoiceRecorder(),
        file: () -> File? = { File(folder.root, "take-${System.nanoTime()}.ogg") },
    ): Rig {
        val takes = mutableListOf<VoiceTake>()
        val hints = mutableListOf<UiText>()
        val states = mutableListOf<TypingState>()
        val discarded = mutableListOf<File>()
        val player = FakeVoiceEngine()
        val recording =
            VoiceRecording(
                scope = backgroundScope,
                recorder = mic,
                preview = player,
                newFile = file,
                discard = { discarded += it },
                clock = { testScheduler.currentTime },
                chatState = { states += it },
                hint = { hints += it },
                onTake = { takes += it },
            )
        return Rig(recording, mic, player, takes, hints, states, discarded)
    }

    private fun TestScope.hold(
        ms: Long,
        level: Int = 20_000,
        rig: Rig,
    ) {
        rig.mic.level = level
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun holdAndReleaseSendsTheTakeWithItsLengthAndWaveform() =
        runTest {
            val r = rig()
            assertTrue(r.recording.start())
            assertEquals(
                RecordPhase.Holding,
                r.recording.state.value
                    ?.phase,
            )
            assertEquals(VoiceRecording.MAX_MS, r.mic.maxMs)
            hold(1_500, rig = r)
            hold(1_300, level = 2_000, rig = r)
            assertEquals(
                "0:02",
                r.recording.state.value
                    ?.elapsed,
            )
            assertTrue(
                r.recording.state.value!!
                    .level in 0.5f..0.6f,
            )
            r.recording.release()
            runCurrent()
            assertNull(r.recording.state.value)
            val take = r.takes.single()
            assertEquals(r.mic.started.single(), take.file)
            assertEquals(3, take.seconds)
            assertTrue(MediaWaveform.valid(take.waveform))
            // The loud start and the quieter end show in the bars.
            assertEquals(100, take.waveform.first().toInt())
            assertTrue(take.waveform.last() < take.waveform.first())
            assertEquals(1, r.mic.stops)
            assertTrue(r.hints.isEmpty() && r.discarded.isEmpty())
        }

    @Test fun aTakeUnderASecondIsThrownAwayWithAHint() =
        runTest {
            val r = rig()
            r.recording.start()
            hold(900, rig = r)
            r.recording.release()
            runCurrent()
            assertTrue(r.takes.isEmpty())
            assertEquals(listOf(r.mic.started.single()), r.discarded)
            assertEquals(listOf<UiText>(UiText.Res(R.string.wa_record_hold)), r.hints)
            assertNull(r.recording.state.value)
            // A take the recorder could not finish is thrown away the same way.
            r.mic.playable = false
            r.recording.start()
            hold(3_000, rig = r)
            r.recording.release()
            runCurrent()
            assertTrue(r.takes.isEmpty())
            assertEquals(2, r.discarded.size)
        }

    @Test fun slidingToCancelThrowsTheTakeAwayWithoutAWord() =
        runTest {
            val r = rig()
            r.recording.start()
            hold(4_000, rig = r)
            r.recording.cancel()
            runCurrent()
            assertTrue(r.takes.isEmpty() && r.hints.isEmpty())
            assertEquals(r.mic.started, r.discarded)
            assertFalse(r.mic.recording)
            assertNull(r.recording.state.value)
            // A release after the cancel (the finger lifts) does nothing.
            r.recording.release()
            runCurrent()
            assertTrue(r.takes.isEmpty())
        }

    @Test fun lockKeepsRecordingStopShowsTheTakeAndThePreviewPlaysIt() =
        runTest {
            val r = rig()
            r.recording.start()
            hold(1_000, rig = r)
            r.recording.lock()
            // The finger lifts after the lock: recording goes on.
            r.recording.release()
            assertEquals(
                RecordPhase.Locked,
                r.recording.state.value
                    ?.phase,
            )
            assertTrue(r.mic.recording)
            hold(4_000, rig = r)
            r.recording.stop()
            runCurrent()
            val review = r.recording.state.value
            assertEquals(RecordPhase.Review, review?.phase)
            assertEquals("0:05", review?.elapsed)
            assertEquals(MediaWaveform.SAMPLES, review?.bars?.size)
            assertFalse(r.mic.recording)
            assertTrue(r.takes.isEmpty())

            r.recording.togglePreview()
            assertEquals(
                r.mic.started
                    .single()
                    .absolutePath,
                r.player.path,
            )
            assertTrue(
                r.recording.state.value!!
                    .playing,
            )
            r.player.position = 2_500
            advanceTimeBy(VoiceRecording.TICK_MS + 1)
            runCurrent()
            assertEquals(
                0.5f,
                r.recording.state.value!!
                    .progress,
            )
            r.recording.togglePreview()
            assertFalse(
                r.recording.state.value!!
                    .playing,
            )
            assertFalse(r.player.playing)
            r.recording.togglePreview()
            assertTrue(r.player.playing)
            assertEquals(1, r.player.starts)

            r.recording.send()
            runCurrent()
            assertEquals(5, r.takes.single().seconds)
            assertTrue(r.player.releases > 0)
            assertNull(r.recording.state.value)
        }

    @Test fun sendWhileLockedEndsTheRecordingAndSendsIt() =
        runTest {
            val r = rig()
            r.recording.start()
            r.recording.lock()
            hold(2_400, rig = r)
            r.recording.send()
            runCurrent()
            assertEquals(2, r.takes.single().seconds)
            assertFalse(r.mic.recording)
        }

    @Test fun deleteInReviewThrowsTheTakeAway() =
        runTest {
            val r = rig()
            r.recording.start()
            r.recording.lock()
            hold(2_000, rig = r)
            r.recording.stop()
            r.recording.delete()
            runCurrent()
            assertTrue(r.takes.isEmpty() && r.hints.isEmpty())
            assertEquals(r.mic.started, r.discarded)
            assertNull(r.recording.state.value)
        }

    @Test fun aCallOrTheAppGoingAwayEndsTheRecordingIntoReview() =
        runTest {
            val r = rig()
            // A call takes the audio focus while the finger holds the microphone.
            r.recording.start()
            hold(3_000, rig = r)
            r.mic.interrupt(RecorderStop.FocusLost)
            runCurrent()
            assertEquals(
                RecordPhase.Review,
                r.recording.state.value
                    ?.phase,
            )
            assertFalse(r.mic.recording)
            // The finger lifts afterwards: nothing is sent that was not meant to be.
            r.recording.release()
            runCurrent()
            assertTrue(r.takes.isEmpty())
            r.recording.delete()

            // The app goes to the background while locked.
            r.recording.start()
            r.recording.lock()
            hold(2_000, rig = r)
            r.recording.interrupt()
            assertEquals(
                RecordPhase.Review,
                r.recording.state.value
                    ?.phase,
            )
            // ...and a preview that plays pauses.
            r.recording.togglePreview()
            r.recording.interrupt()
            assertFalse(
                r.recording.state.value!!
                    .playing,
            )
            r.recording.delete()

            // Too short when the call came: thrown away with the hint.
            r.recording.start()
            hold(400, rig = r)
            r.mic.interrupt(RecorderStop.FocusLost)
            runCurrent()
            assertNull(r.recording.state.value)
            assertEquals(listOf<UiText>(UiText.Res(R.string.wa_record_hold)), r.hints)
        }

    @Test fun theLengthCapEndsTheRecordingIntoReview() =
        runTest {
            val r = rig()
            r.recording.start()
            r.recording.lock()
            hold(VoiceRecording.MAX_MS + VoiceRecording.TICK_MS, rig = r)
            val review = r.recording.state.value
            assertEquals(RecordPhase.Review, review?.phase)
            assertEquals("15:00", review?.elapsed)
            assertFalse(r.mic.recording)
        }

    @Test fun aMicrophoneThatCannotOpenSaysSo() =
        runTest {
            val r = rig(mic = FakeVoiceRecorder(available = false))
            assertFalse(r.recording.start())
            runCurrent()
            assertNull(r.recording.state.value)
            assertEquals(listOf<UiText>(UiText.Res(R.string.wa_record_failed)), r.hints)
            assertEquals(1, r.discarded.size)
            // Not linked: no file to record into.
            val unlinked = rig(file = { null })
            assertFalse(unlinked.recording.start())
            assertTrue(unlinked.mic.started.isEmpty())
        }

    @Test fun recordingIsToldOnlyAfterTheDebounceThenRepeatedThenPaused() =
        runTest {
            val r = rig()
            // A tap on the microphone tells nobody.
            r.recording.start()
            hold(300, rig = r)
            r.recording.release()
            runCurrent()
            assertTrue(r.states.isEmpty())

            r.recording.start()
            hold(VoiceRecording.STATE_DELAY_MS, rig = r)
            assertEquals(listOf(TypingState.Recording), r.states)
            hold(VoiceRecording.STATE_EVERY_MS * 2, rig = r)
            assertEquals(List(3) { TypingState.Recording }, r.states)
            r.recording.release()
            runCurrent()
            assertEquals(List(3) { TypingState.Recording } + TypingState.Paused, r.states)

            // Cancelled after it was told: paused too.
            r.states.clear()
            r.recording.start()
            hold(1_000, rig = r)
            r.recording.cancel()
            runCurrent()
            assertEquals(listOf(TypingState.Recording, TypingState.Paused), r.states)
            hold(VoiceRecording.STATE_EVERY_MS * 2, rig = r)
            assertEquals(2, r.states.size)
        }

    @Test fun onlyOneRecordingAtATime() =
        runTest {
            val r = rig()
            assertTrue(r.recording.start())
            assertFalse(r.recording.start())
            assertEquals(1, r.mic.started.size)
            assertNotNull(r.recording.state.value)
            r.recording.dispose()
            runCurrent()
            assertNull(r.recording.state.value)
            assertEquals(r.mic.started, r.discarded)
        }
}
