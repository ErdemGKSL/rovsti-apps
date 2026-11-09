package dev.rovsti.platform.telephony

import android.media.ToneGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Flip cases of the GPUI build's Epic17Test (18.4), and the keypad tone table. */
class FlipRuleTest {
    @Test fun flipNeedsTwoFaceDownSamplesAfterAnUpwardOne() {
        val r = FlipRule()
        assertFalse(r.onZ(9.8f))
        assertFalse(r.onZ(-8f))
        assertTrue(r.onZ(-8.5f))
        // Fires once; it must be armed again by an upward sample.
        assertFalse(r.onZ(-9f))
        assertFalse(r.onZ(-9f))
    }

    @Test fun aPhoneAlreadyFaceDownNeverSilences() {
        val r = FlipRule()
        repeat(6) { assertFalse(r.onZ(-9.8f)) }
        assertFalse(r.onZ(-2f))
        assertFalse(r.onZ(-8f))
        assertTrue(r.onZ(-8f))
    }

    @Test fun oneNoisySampleDoesNotTrigger() {
        val r = FlipRule()
        r.onZ(1f)
        assertFalse(r.onZ(-8f))
        assertFalse(r.onZ(0f))
        assertFalse(r.onZ(-8f))
    }

    @Test fun keypadDigitsMapToDtmfTones() {
        assertEquals(ToneGenerator.TONE_DTMF_0, KeypadTone.toneFor(0))
        assertEquals(ToneGenerator.TONE_DTMF_9, KeypadTone.toneFor(9))
        assertEquals(ToneGenerator.TONE_DTMF_S, KeypadTone.toneFor(10))
        assertEquals(ToneGenerator.TONE_DTMF_P, KeypadTone.toneFor(11))
        assertEquals(-1, KeypadTone.toneFor(12))
    }
}
