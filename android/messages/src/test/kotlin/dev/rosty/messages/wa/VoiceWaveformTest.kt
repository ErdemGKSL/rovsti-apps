package dev.rosty.messages.wa

import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.platform.audio.VoiceRecorder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The waveform of a recorded voice note: the level of a reading and the 64 bars WhatsApp carries. */
class VoiceWaveformTest {
    @Test fun aReadingIsADecibelLevelFromSilenceToFullScale() {
        assertEquals(0, VoiceWaveform.level(0))
        assertEquals(0, VoiceWaveform.level(-5))
        assertEquals(100, VoiceWaveform.level(VoiceRecorder.MAX_AMPLITUDE))
        assertEquals(100, VoiceWaveform.level(VoiceRecorder.MAX_AMPLITUDE * 2))
        // Half the floor below full scale (-25 dB, about 1 843) is half way up.
        assertEquals(50, VoiceWaveform.level(1_843))
        // At the floor (-50 dB, about 104) and below: silence.
        assertEquals(0, VoiceWaveform.level(100))
        assertEquals(0, VoiceWaveform.level(1))
        // Louder is never lower.
        val levels = (1..VoiceRecorder.MAX_AMPLITUDE step 997).map(VoiceWaveform::level)
        assertEquals(levels.sorted(), levels)
    }

    @Test fun noReadingsAndSilenceAreAllZeros() {
        assertArrayEquals(ByteArray(MediaWaveform.SAMPLES), VoiceWaveform.reduce(emptyList()))
        assertArrayEquals(ByteArray(MediaWaveform.SAMPLES), VoiceWaveform.reduce(List(300) { 0 }))
    }

    @Test fun sixtyFourReadingsAreTheBarsScaledToTheLoudest() {
        val readings = List(64) { it % 10 * 5 }
        val bars = VoiceWaveform.reduce(readings)
        assertEquals(64, bars.size)
        assertEquals(100, bars.max().toInt())
        // 45 is the loudest: every bar is scaled by 100 / 45.
        assertEquals(listOf(0, 11, 22, 33, 44, 56, 67, 78, 89, 100), bars.take(10).map { it.toInt() })
    }

    @Test fun moreReadingsThanBarsAreAveragedSliceBySlice() {
        // 128 readings: each bar is the mean of two, here 20 and 60, so every bar is 40 and then scaled to 100.
        val even = VoiceWaveform.reduce(List(128) { if (it % 2 == 0) 20 else 60 })
        assertTrue(even.all { it.toInt() == 100 })
        // A loud first half and a quiet second half stay that way.
        val halves = VoiceWaveform.reduce(List(640) { if (it < 320) 80 else 20 })
        assertTrue(halves.take(32).all { it.toInt() == 100 })
        assertTrue(halves.drop(32).all { it.toInt() == 25 })
    }

    @Test fun fewerReadingsThanBarsAreRepeated() {
        val bars = VoiceWaveform.reduce(listOf(10, 50, 100, 30))
        assertEquals(64, bars.size)
        assertEquals(listOf(10, 50, 100, 30), (0 until 4).map { bars[it * 16].toInt() })
        assertTrue((0 until 16).all { bars[it].toInt() == 10 })
    }

    @Test fun readingsOutOfRangeAreClampedAndTheResultIsAlwaysAValidWaveform() {
        val bars = VoiceWaveform.reduce(listOf(-40, 250, 50) + List(97) { 70 })
        assertTrue(MediaWaveform.valid(bars))
        assertNotNull(MediaWaveform.encode(bars))
        for (n in listOf(1, 2, 63, 64, 65, 999, 9_000)) {
            assertTrue("$n readings", MediaWaveform.valid(VoiceWaveform.reduce(List(n) { it * 7 % 101 })))
        }
    }

    @Test fun barsAreFractionsOfTheMaximum() {
        val samples = ByteArray(MediaWaveform.SAMPLES) { if (it == 0) 100 else 25 }
        val bars = VoiceWaveform.bars(samples)
        assertEquals(1f, bars[0])
        assertEquals(0.25f, bars[1])
    }
}
