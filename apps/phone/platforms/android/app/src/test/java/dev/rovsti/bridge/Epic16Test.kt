package dev.rovsti.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Epic16Test {
    // ---- Mailbox (16.2) -------------------------------------------------------------------

    @Test fun mailboxKeepsOneSlotPerKindAndDrainsOnAttach() {
        val got = ArrayList<Pair<String, String>>()
        val box = Mailbox { k, j -> got.add(k to j) }
        box.post("document_result", "a")
        box.post("document_result", "b")
        box.post("ui_request", "u")
        box.post("ringtone_result", "r")
        assertEquals(3, box.pending())
        assertTrue(got.isEmpty())
        box.attach()
        assertEquals(listOf("document_result" to "b", "ui_request" to "u", "ringtone_result" to "r"), got)
        assertEquals(0, box.pending())
        // Attached: delivered at once, and a second attach drains nothing.
        box.post("document_result", "c")
        box.attach()
        assertEquals("document_result" to "c", got.last())
        assertEquals(4, got.size)
    }

    @Test fun mailboxHoldsAgainAfterDetach() {
        val got = ArrayList<String>()
        val box = Mailbox { _, j -> got.add(j) }
        box.attach()
        box.detach()
        box.post("ui_request", "x")
        assertTrue(got.isEmpty())
        box.attach()
        assertEquals(listOf("x"), got)
    }

    // ---- Settings mirror (16.2, 16.8) -----------------------------------------------------

    @Test fun mirrorDefaultsAreV01() {
        val v = SettingsValues()
        assertTrue(v.proximity)
        assertFalse(v.flipToSilence || v.vibrateConnect || v.vibrateEnd)
        assertNull(v.keypadTones)
        assertEquals(v, SettingsMirror.parse("{}"))
    }

    @Test fun mirrorReadsAPush() {
        val v = SettingsMirror.parse(
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

    @Test fun tonesMapDigitsAndRejectOthers() {
        assertEquals(android.media.ToneGenerator.TONE_DTMF_0, KeypadTone.toneFor(0))
        assertEquals(android.media.ToneGenerator.TONE_DTMF_9, KeypadTone.toneFor(9))
        assertEquals(android.media.ToneGenerator.TONE_DTMF_S, KeypadTone.toneFor(10))
        assertEquals(android.media.ToneGenerator.TONE_DTMF_P, KeypadTone.toneFor(11))
        assertEquals(-1, KeypadTone.toneFor(12))
    }

    // ---- SIM accounts (16.3) ----------------------------------------------------------------

    @Test fun theKeyIsTheCallLogPair() {
        assertEquals(
            "com.android.phone/.PstnConnectionService#8901",
            SimAccounts.keyOf("com.android.phone/.PstnConnectionService", "8901"),
        )
        assertNull(SimAccounts.keyOf(null, "1"))
        assertNull(SimAccounts.keyOf("", "1"))
        assertNull(SimAccounts.keyOf("c", null))
    }

    @Test fun askNeedsNoDefaultAndTwoAccounts() {
        assertTrue(SimAccounts.ask(null, 2))
        assertFalse(SimAccounts.ask(null, 1))
        assertFalse(SimAccounts.ask("a#1", 2))
    }

    @Test fun theAccountsEnvelopeMatchesTheWire() {
        val json = SimAccounts.json(
            listOf(
                SimAccounts.Account("a#1", "Turkcell", 0, 0xFF1E88E5.toInt(), true),
                SimAccounts.Account("a#2", "Vodafone", null, 0, true, enabled = false),
            ),
            null,
        )
        val o = JSONObject(json)
        assertTrue(o.getBoolean("ok"))
        val data = o.getJSONObject("data")
        assertTrue(data.getBoolean("ask"))
        assertFalse(data.has("default_key"))
        val first = data.getJSONArray("accounts").getJSONObject(0)
        assertEquals("a#1", first.getString("key"))
        assertEquals(0, first.getInt("slot_index"))
        assertFalse(data.getJSONArray("accounts").getJSONObject(1).has("slot_index"))
        assertFalse(data.getJSONArray("accounts").getJSONObject(1).getBoolean("enabled"))
    }

    // ---- Documents (16.6) -------------------------------------------------------------------

    @Test fun readLimitedReadsUpToTheCap() {
        assertEquals("hello", Documents.readLimited(ByteArrayInputStream("hello".toByteArray())))
        val big = ByteArray(Documents.MAX_BYTES + 1)
        assertNull(Documents.readLimited(ByteArrayInputStream(big)))
        assertEquals(Documents.MAX_BYTES, Documents.readLimited(ByteArrayInputStream(ByteArray(Documents.MAX_BYTES) { 'a'.code.toByte() }))!!.length)
    }

    @Test fun resultJsonCarriesOpNameAndText() {
        val o = JSONObject(Documents.resultJson("import_backup", true, "b.json", "{}"))
        assertEquals("import_backup", o.getString("op"))
        assertTrue(o.getBoolean("ok"))
        assertEquals("b.json", o.getString("name"))
        assertEquals("{}", o.getString("text"))
        val cancelled = JSONObject(Documents.resultJson("export_backup", false))
        assertFalse(cancelled.getBoolean("ok"))
        assertFalse(cancelled.has("error"))
    }

    // ---- System pages (16.10) ----------------------------------------------------------------

    @Test fun accessibilityFallsBackToTheGeneralPageThenAppInfo() {
        val chain = SystemPages.chain("call_accessibility")
        assertEquals("android.telecom.action.SHOW_CALL_ACCESSIBILITY_SETTINGS", chain[0])
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS", chain[1])
        assertEquals(SystemPages.ACTION_DETAILS, chain.last())
    }
}
