package dev.rovsti.bridge

import android.media.AudioDeviceInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaIncomingTest {
    private fun call(vararg pairs: Pair<String, String>) = mapOf("kind" to "call", "call_id" to "c1") + pairs

    @Test fun nameWins() {
        val n = WaIncomingNotice.of(call("name" to "Ayse", "from" to "905551234567@s.whatsapp.net"))
        assertEquals(WaIncomingNotice("c1", "Ayse"), n)
    }

    @Test fun fallsBackToTheUserPartOfFrom() {
        assertEquals("905551234567", WaIncomingNotice.of(call("from" to "905551234567@s.whatsapp.net"))?.title)
        assertEquals("905551234567", WaIncomingNotice.of(call("from" to "905551234567:4@s.whatsapp.net", "name" to " "))?.title)
    }

    @Test fun fallsBackToTheGenericTitle() {
        assertEquals("WhatsApp call", WaIncomingNotice.of(call())?.title)
        assertEquals("WhatsApp call", WaIncomingNotice.of(call("from" to "@lid"))?.title)
    }

    @Test fun onlyCallsWithAnIdQualify() {
        assertNull(WaIncomingNotice.of(mapOf("kind" to "message", "call_id" to "c1")))
        assertNull(WaIncomingNotice.of(mapOf("kind" to "call")))
        assertNull(WaIncomingNotice.of(mapOf("kind" to "call", "call_id" to " ")))
    }

    private fun end(vararg pairs: Pair<String, String>) =
        mapOf("kind" to "call_end", "call_id" to "c1", "missed" to "1") + pairs

    @Test fun missedNoticeNeedsMissedCallEndWithAnId() {
        assertEquals(WaMissedNotice("c1", "Ayse"), WaMissedNotice.of(end("name" to "Ayse")))
        assertNull(WaMissedNotice.of(end("missed" to "0")))
        assertNull(WaMissedNotice.of(mapOf("kind" to "call_end", "call_id" to "c1")))
        assertNull(WaMissedNotice.of(end("kind" to "call")))
        assertNull(WaMissedNotice.of(end("call_id" to " ")))
        assertNull(WaMissedNotice.of(mapOf("kind" to "call_end", "missed" to "1")))
    }

    @Test fun missedTitleFollowsTheSameFallbacks() {
        assertEquals("905551234567", WaMissedNotice.of(end("from" to "905551234567:4@s.whatsapp.net"))?.title)
        assertEquals("WhatsApp call", WaMissedNotice.of(end())?.title)
    }

    @Test fun missedNotificationIdDiffersFromTheRingingOne() {
        assertNotEquals(WaIncoming.notificationId("c1"), WaIncoming.missedNotificationId("c1"))
    }

    @Test fun actionJsonCarriesIdAndAction() {
        val json = JSONObject(WaIncoming.actionJson("c1", "answer")!!)
        assertEquals("c1", json.getString("call_id"))
        assertEquals("answer", json.getString("action"))
        assertNull(WaIncoming.actionJson("c1", "hold"))
        assertNull(WaIncoming.actionJson("", "decline"))
    }

    @Test fun pickDeviceChoosesSpeakerOrEarpiece() {
        val types = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertEquals(2, WaCallAudio.pickDevice(types, true))
        assertEquals(1, WaCallAudio.pickDevice(types, false))
        assertEquals(-1, WaCallAudio.pickDevice(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), true))
        assertEquals(-1, WaCallAudio.pickDevice(emptyList(), false))
    }
}
