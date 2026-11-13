package dev.rovsti.platform.wa

import dev.rovsti.core.ops.CallAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speaker side of the call latency work (docs/kotlin-ui/wa-call-audio.md, "Latency"): the size of the playback
 * buffer, when it starts, which frame sizes are played, and that no delay stands in the track after a burst or with
 * a drifting clock. All pure: no AudioTrack.
 */
class WaCallPlayoutTest {
    /** A track that plays as fast as frames arrive: its fill only changes when a frame is dropped. */
    private fun play(
        trim: PlayoutTrim,
        startFillMs: Int,
        frames: Int,
        frameMs: Int = 20,
        fillAt: (Int, Int) -> Int = { fill, _ -> fill },
    ): Pair<Int, Int> {
        var fill = startFillMs
        var droppedMs = 0
        repeat(frames) { i ->
            if (trim.drop(fillAt(fill, i), frameMs)) {
                fill -= frameMs
                droppedMs += frameMs
            }
        }
        return fill to droppedMs
    }

    @Test fun theSpeakerBufferIsTwoFramesOrThePlatformMinimum() {
        // 120 ms instead of the 240 ms it was.
        assertEquals(WaCallAudio.FRAME_BYTES * 2, WaCallAudio.speakerBufferBytes(100))
        assertEquals(120, WaCallAudio.speakerBufferBytes(0) / WaCallAudio.BYTES_PER_MS)
        assertEquals(WaCallAudio.FRAME_BYTES * 2, WaCallAudio.speakerBufferBytes(WaCallAudio.FRAME_BYTES * 2))
        // A device that needs more gets what it needs.
        assertEquals(5_000, WaCallAudio.speakerBufferBytes(5_000))
        assertTrue(WaCallAudio.speakerBufferBytes(100) < WaCallAudio.bufferBytes(100))
    }

    @Test fun playbackStartsAfterFortyMillisecondsNotAFullBuffer() {
        val capacity = WaCallAudio.speakerBufferBytes(100) / 2
        assertEquals(640, WaCallAudio.startThresholdFrames(capacity))
        assertEquals(40, WaCallAudio.startThresholdFrames(capacity) * 1000 / WaCallAudio.SAMPLE_RATE)
        // Never more than the buffer holds, never nothing.
        assertEquals(320, WaCallAudio.startThresholdFrames(320))
        assertEquals(1, WaCallAudio.startThresholdFrames(0))
    }

    @Test fun whateverFrameSizeArrivesIsPlayed() {
        // 20 ms from the new gateway, 60 ms from an old one, 40 ms in between.
        for (bytes in listOf(640, 1_280, 1_920)) assertTrue("$bytes", WaCallAudio.playable(bytes))
        for (bytes in listOf(-1, 0, 100, 639, 641, 1_919, 2_560)) assertFalse("$bytes", WaCallAudio.playable(bytes))
        assertEquals(CallAudio.STEP_BYTES, WaCallAudio.STEP_BYTES)
        assertEquals(20, WaCallAudio.STEP_BYTES / WaCallAudio.BYTES_PER_MS)
        assertEquals(60, WaCallAudio.FRAME_BYTES / WaCallAudio.BYTES_PER_MS)
    }

    @Test fun theFillIsWrittenMinusPlayedInMilliseconds() {
        val capacity = 1_920
        assertEquals(0, WaCallAudio.fillMs(0, 0, capacity))
        assertEquals(100, WaCallAudio.fillMs(1_600, 0, capacity))
        assertEquals(40, WaCallAudio.fillMs(16_000, 15_360, capacity))
        // The head is a 32-bit counter that wraps (after 74 hours at 16 kHz).
        val wrapped = (1L shl 32) + 100
        assertEquals(20, WaCallAudio.fillMs(wrapped, ((1L shl 32) - 220).toInt(), capacity))
        // More than the buffer can hold, or a head past what was written: not believable.
        assertEquals(-1, WaCallAudio.fillMs(100_000, 0, capacity))
        assertEquals(-1, WaCallAudio.fillMs(100, 200, capacity))
    }

    @Test fun aBurstDrainsBackToTheTarget() {
        // The network stalled and delivered everything at once: the track is at 100 ms and would stay there.
        val trim = PlayoutTrim()
        val (fill, dropped) = play(trim, startFillMs = 100, frames = 103)
        assertEquals(WaCallAudio.TARGET_FILL_MS, fill)
        assertEquals(60, dropped)
        // From then on nothing is dropped: ten more seconds at the target.
        assertEquals(40 to 0, play(trim, startFillMs = fill, frames = 500))
    }

    @Test fun nothingIsDroppedBeforeAWholeWindowHasPassed() {
        // 1.98 s at 100 ms: could still be jitter that comes back.
        assertEquals(100 to 0, play(PlayoutTrim(), startFillMs = 100, frames = 99))
    }

    @Test fun aFillThatDipsToTheTargetIsJitterAndIsLeftAlone() {
        // The fill swings between 20 and 100 ms: the low points show the buffer is in use.
        val (_, dropped) = play(PlayoutTrim(), startFillMs = 0, frames = 3_000) { _, i -> 20 + (i % 5) * 20 }
        assertEquals(0, dropped)
    }

    @Test fun aSurplusSmallerThanOneFrameIsLeftAlone() {
        assertEquals(59 to 0, play(PlayoutTrim(), startFillMs = 59, frames = 1_000))
        // An old gateway's 60 ms frames: only a surplus of a whole frame can go.
        assertEquals(90 to 0, play(PlayoutTrim(), startFillMs = 90, frames = 1_000, frameMs = 60))
        assertEquals(40 to 60, play(PlayoutTrim(), startFillMs = 100, frames = 1_000, frameMs = 60))
    }

    @Test fun aDriftingClockNeverBuildsAStandingDelay() {
        // The gateway's clock runs 0.2 % fast: one extra millisecond every 25 frames, for ten minutes.
        val trim = PlayoutTrim()
        var fill = 40.0
        var worst = 0.0
        var droppedMs = 0
        repeat(30_000) {
            fill += 0.04
            if (trim.drop(fill.toInt(), 20)) {
                fill -= 20
                droppedMs += 20
            }
            worst = maxOf(worst, fill)
        }
        assertTrue("worst $worst", worst < 70)
        assertTrue("fill $fill", fill < 70)
        // 1.2 s too much arrived; all but what is in the buffer went, 20 ms at a time.
        assertTrue("dropped $droppedMs", droppedMs in 1_160..1_200)
    }

    @Test fun anUnknownFillNeverDrops() {
        val trim = PlayoutTrim()
        assertEquals(100 to 0, play(trim, startFillMs = 100, frames = 1_000) { _, _ -> -1 })
        // And it forgets what it saw: a window starts over after it.
        assertEquals(100 to 0, play(trim, startFillMs = 100, frames = 50))
        assertFalse(trim.drop(-1, 20))
        assertEquals(100 to 0, play(trim, startFillMs = 100, frames = 99))
    }

    @Test fun theLogLineHasTheFillAndWhatWasTrimmed() {
        val log = FillLog()
        log.frame(fillMs = 40, frameMs = 20, dropped = false)
        log.frame(fillMs = 100, frameMs = 20, dropped = true)
        log.frame(fillMs = -1, frameMs = 20, dropped = false)
        assertEquals(
            "WA call audio: track_fill mean=70ms min=40ms max=100ms trimmed=20ms frames=3 frame=20ms " +
                "underruns=2 buffer=120ms",
            log.line(underruns = 2, bufferMs = 120),
        )
        // It starts over after each line; without a known fill it says so.
        log.frame(fillMs = -1, frameMs = 60, dropped = false)
        assertEquals(
            "WA call audio: track_fill=unknown trimmed=0ms frames=1 frame=60ms underruns=2 buffer=120ms",
            log.line(underruns = 2, bufferMs = 120),
        )
    }
}
