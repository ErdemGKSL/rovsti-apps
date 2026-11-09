package dev.rovsti.platform.system

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SettingsMirror and SystemPages cases of the old `Epic16Test`. */
class SettingsMirrorTest {
    // ---- Settings mirror (16.2, 16.8) -----------------------------------------------------

    @Test fun mirrorDefaultsAreV01() {
        val v = SettingsValues()
        assertTrue(v.proximity)
        assertFalse(v.flipToSilence || v.vibrateConnect || v.vibrateEnd)
        assertNull(v.keypadTones)
        assertEquals(v, SettingsMirror.parse("{}"))
    }

    @Test fun mirrorReadsAPush() {
        val v =
            SettingsMirror.parse(
                """{"proximity":false,"flip_to_silence":true,"vibrate_connect":true,"keypad_tones":false,"haptics":"light","name_format":"last_first","has_replies":true}""",
            )!!
        assertFalse(v.proximity)
        assertTrue(v.flipToSilence && v.vibrateConnect && !v.vibrateEnd && v.hasReplies)
        assertEquals(false, v.keypadTones)
        assertEquals("light", v.haptics)
        assertEquals("last_first", v.nameFormat)
        assertNull(SettingsMirror.parse("not json"))
    }

    @Test fun keypadTonesFollowTheSettingElseTheSystem() {
        assertTrue(SettingsMirror.effectiveKeypadTones(SettingsValues(keypadTones = true), false))
        assertFalse(SettingsMirror.effectiveKeypadTones(SettingsValues(keypadTones = false), true))
        assertTrue(SettingsMirror.effectiveKeypadTones(SettingsValues(), true))
        assertFalse(SettingsMirror.effectiveKeypadTones(SettingsValues(), false))
    }

    @Test fun factsJsonCarriesBothFacts() {
        val o = JSONObject(SettingsMirror.factsJson(true, false))
        assertTrue(o.getBoolean("flip_supported"))
        assertFalse(o.getBoolean("system_keypad_tones"))
    }

    @Test fun hapticsFollowTheMirror() {
        assertFalse(Haptics.wants(SettingsValues(), "connect"))
        assertTrue(Haptics.wants(SettingsValues(vibrateConnect = true), "connect"))
        assertFalse(Haptics.wants(SettingsValues(vibrateConnect = true), "end"))
        assertTrue(Haptics.wants(SettingsValues(vibrateEnd = true), "end"))
        assertFalse(Haptics.wants(SettingsValues(vibrateEnd = true), "other"))
    }

    // ---- System pages (16.10) ----------------------------------------------------------------

    @Test fun accessibilityFallsBackToTheGeneralPageThenAppInfo() {
        val chain = SystemPages.chain("call_accessibility")
        assertEquals("android.telecom.action.SHOW_CALL_ACCESSIBILITY_SETTINGS", chain[0])
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS", chain[1])
        assertEquals(SystemPages.ACTION_DETAILS, chain.last())
    }
}
