package dev.rovsti.platform.wa

import android.content.Intent
import android.media.AudioDeviceInfo
import dev.rovsti.core.ops.CallAudio
import dev.rovsti.platform.PlatformEvents
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaIncomingTest {
    private fun call(vararg pairs: Pair<String, String>) = mapOf("kind" to "call", "call_id" to "c1") + pairs

    @Test fun nameWins() {
        val n = WaIncomingNotice.of(call("name" to "Ayse", "sender" to "905551234567@s.whatsapp.net"))
        assertEquals(WaIncomingNotice("c1", "Ayse"), n)
    }

    @Test fun fallsBackToTheNumberOfSender() {
        assertEquals("+905551234567", WaIncomingNotice.of(call("sender" to "905551234567@s.whatsapp.net"))?.title)
        assertEquals(
            "+905551234567",
            WaIncomingNotice.of(call("sender" to "905551234567:4@s.whatsapp.net", "name" to " "))?.title,
        )
    }

    @Test fun stillReadsTheOldFromKey() {
        assertEquals("+905551234567", WaIncomingNotice.of(call("from" to "905551234567@s.whatsapp.net"))?.title)
        assertEquals(
            "+905551234567",
            WaIncomingNotice.of(call("sender" to "905551234567@s.whatsapp.net", "from" to "1@s.whatsapp.net"))?.title,
        )
    }

    @Test fun fallsBackToTheGenericTitle() {
        assertEquals("WhatsApp call", WaIncomingNotice.of(call())?.title)
        assertEquals("WhatsApp call", WaIncomingNotice.of(call("sender" to "@lid"))?.title)
    }

    @Test fun aSavedContactNamesTheCallerBeforeTheWhatsAppName() {
        val book = mapOf("+905551234567" to "Ayşe (iş)")
        val n = WaIncomingNotice.of(call("name" to "Ayse", "sender" to "905551234567:4@s.whatsapp.net"), book::get)
        assertEquals("Ayşe (iş)", n?.title)
        // A stranger with a push name keeps it.
        assertEquals(
            "Mert",
            WaIncomingNotice.of(call("name" to "Mert", "sender" to "905559999999@s.whatsapp.net"), book::get)?.title,
        )
        assertEquals("Ayşe (iş)", WaMissedNotice.of(end("sender" to "905551234567@s.whatsapp.net"), book::get)?.title)
    }

    @Test fun aLidPeerIsNoNumber() {
        // The gateway could not map the LID to a phone JID: no number to look up, never the LID digits as a title.
        val asked = ArrayList<String>()
        val lookup = { n: String ->
            asked += n
            "Nobody"
        }
        assertEquals("Mert", WaIncomingNotice.of(call("name" to "Mert", "sender" to "123456789@lid"), lookup)?.title)
        assertEquals("WhatsApp call", WaIncomingNotice.of(call("sender" to "123456789@lid"), lookup)?.title)
        assertTrue(asked.isEmpty())
    }

    @Test fun aBareNumberTitleIsFormattedForReading() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("Ayse", WaIncoming.displayTitle(context, "Ayse"))
        val shown = WaIncoming.displayTitle(context, "+905321234567")
        assertEquals("+905321234567", shown.filter { it == '+' || it.isDigit() })
        assertTrue(shown, shown.contains(' '))
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
        assertEquals("+905551234567", WaMissedNotice.of(end("sender" to "905551234567:4@s.whatsapp.net"))?.title)
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
        val types =
            listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
        assertEquals(2, WaCallAudio.pickDevice(types, WaCallAudio.Route.Speaker))
        assertEquals(1, WaCallAudio.pickDevice(types, WaCallAudio.Route.Earpiece))
        assertEquals(-1, WaCallAudio.pickDevice(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), WaCallAudio.Route.Speaker))
        assertEquals(-1, WaCallAudio.pickDevice(emptyList(), WaCallAudio.Route.Earpiece))
    }

    @Test fun frameSizeMatchesTheCoreBridgeHandle() {
        assertEquals(CallAudio.FRAME_BYTES, WaCallAudio.FRAME_BYTES)
        assertEquals(WaCallAudio.FRAME_SAMPLES * 2, CallAudio.FRAME_BYTES)
    }

    @Test fun microphoneBufferHoldsAtLeastFourFrames() {
        assertEquals(WaCallAudio.FRAME_BYTES * 4, WaCallAudio.bufferBytes(100))
        assertEquals(50_000, WaCallAudio.bufferBytes(50_000))
    }

    @Test fun channelIdsAreTheGpuiOnes() {
        assertEquals("wa_calls", WaIncoming.CHANNEL)
        assertEquals("wa_missed", WaIncoming.MISSED_CHANNEL)
    }

    @Test fun takeActionReturnsThePayloadOnceAndStripsTheExtra() {
        val context = RuntimeEnvironment.getApplication()
        val intent =
            Intent()
                .putExtra(
                    WaIncoming.EXTRA_CALL_ID,
                    "c1",
                ).putExtra(WaIncoming.EXTRA_ACTION, WaIncoming.ANSWER)
        val first = WaIncoming.takeAction(context, intent)
        assertEquals("answer", JSONObject(first!!).getString("action"))
        assertNull(WaIncoming.takeAction(context, intent))
        assertNull(WaIncoming.takeAction(context, Intent()))
        assertNull(WaIncoming.takeAction(context, null))
    }

    @Test fun pushEmitsTheWakeEventWithTheDataAsJson() {
        val context = RuntimeEnvironment.getApplication()
        val seen = mutableListOf<dev.rovsti.platform.PlatformEvent>()
        val job =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
                PlatformEvents.events.collect { seen += it }
            }
        WaPush.onMessage(context, mapOf("kind" to "message", "chat" to "x"))
        job.cancel()
        val event = seen.single { it.kind == WaPush.EVENT }
        assertEquals("x", JSONObject(event.json).getString("chat"))
        assertEquals("", WaPush.token())
        assertTrue(WaPush.payload(emptyMap()) == "{}")
    }

    @Test fun aRingThatIsOverIsNeverPostedAgain() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(WaIncoming.show(context, "over-1", "Ayse"))
        assertTrue(manager.activeNotifications.any { it.id == WaIncoming.notificationId("over-1") })
        // Answered (in the app or on the notification): the notification goes...
        WaIncoming.cancel(context, "over-1")
        assertTrue(manager.activeNotifications.none { it.id == WaIncoming.notificationId("over-1") })
        assertTrue(WaIncoming.isOver("over-1"))
        // ...and a late or repeated `call` push for the same call does not ring it again.
        assertFalse(WaIncoming.show(context, "over-1", "Ayse"))
        WaPush.onMessage(context, mapOf("kind" to "call", "call_id" to "over-1"))
        assertTrue(manager.activeNotifications.none { it.id == WaIncoming.notificationId("over-1") })
    }

    @Test fun theAppOpensTheCallScreenItselfOnlyOnAnUnlockedPhoneInUse() {
        assertTrue(WaIncoming.shouldOpenDirectly(interactive = true, locked = false))
        assertFalse("locked: the full-screen intent does it", WaIncoming.shouldOpenDirectly(true, locked = true))
        assertFalse("screen off: the full-screen intent does it", WaIncoming.shouldOpenDirectly(false, locked = false))
    }

    @Test fun silenceWatchWarnsOnceAfterTwoSecondsOfExactZeros() {
        val watch = SilenceWatch()
        val zero = ByteArray(WaCallAudio.FRAME_BYTES)
        val quiet = ByteArray(WaCallAudio.FRAME_BYTES).also { it[100] = 1 }
        val fired = (1..SilenceWatch.LIMIT_FRAMES * 2).count { watch.frame(zero) }
        assertEquals("one warning per run", 1, fired)
        assertTrue("about 2 s of 60 ms frames", SilenceWatch.LIMIT_FRAMES * 60 in 1_900..2_100)
        assertFalse(watch.frame(quiet))
        val again = (1..SilenceWatch.LIMIT_FRAMES).map { watch.frame(zero) }
        assertEquals("a sound re-arms it", listOf(true), again.filter { it })
        assertTrue(again.last())
    }
}
