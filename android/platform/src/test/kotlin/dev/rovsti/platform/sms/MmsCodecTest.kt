package dev.rovsti.platform.sms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import dev.rovsti.bridge.MmsResultReceiver
import dev.rovsti.bridge.ReceiverWork
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.PlatformEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSmsManager
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MmsCodecTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val pdu = byteArrayOf(1, 2, 3, 4)
    private val b64 = Base64.encodeToString(pdu, Base64.NO_WRAP)

    @After fun reset() {
        ShadowSmsManager.reset()
        MmsCodec.downloadedHandler = null
        ReceiverWork.executor = { Thread(it).start() }
    }

    @Test fun onlyPlainNamesAreServed() {
        assertTrue(MmsCodec.isSafeName(MmsCodec.fileName("send", 12)))
        assertFalse(MmsCodec.isSafeName("../x"))
        assertFalse(MmsCodec.isSafeName("a/b"))
        assertFalse(MmsCodec.isSafeName("a..b"))
        assertFalse(MmsCodec.isSafeName(""))
        assertEquals("${context.packageName}.mmsfiles", MmsCodec.authority(context))
    }

    @Test fun decodeRejectsEmptyAndHuge() {
        assertArrayEquals(pdu, MmsCodec.decodePdu(b64))
        assertNull(MmsCodec.decodePdu(""))
        assertNull(MmsCodec.decodePdu("***"))
    }

    @Test fun sendWritesTheFileAndHandsItsUriToTheManager() {
        assertTrue(MmsCodec.send(context, 21, b64, null))

        assertArrayEquals(pdu, File(MmsCodec.dir(context), "send-21.pdu").readBytes())
        val params =
            shadowOf(
                context.getSystemService(android.telephony.SmsManager::class.java),
            ).lastSentMultimediaMessageParams
        assertEquals("content://${context.packageName}.mmsfiles/send-21.pdu", params.contentUri.toString())
        val intent = shadowOf(params.sentIntent).savedIntent
        assertEquals(MmsCodec.ACTION_SENT, intent.action)
        assertEquals(21L, intent.getLongExtra("local_id", 0))
        assertEquals("send-21.pdu", intent.getStringExtra("file"))
        assertFalse(MmsCodec.send(context, 22, "", null))
    }

    @Test fun downloadCreatesTheTargetFile() {
        assertTrue(MmsCodec.download(context, 30, "http://mms.example/m1", null))
        val params =
            shadowOf(
                context.getSystemService(android.telephony.SmsManager::class.java),
            ).lastDownloadedMultimediaMessageParams
        assertEquals("http://mms.example/m1", params.locationUrl)
        assertTrue(File(MmsCodec.dir(context), "recv-30.pdu").exists())
    }

    @Test fun takeDownloadedReadsOnceAndDeletes() {
        File(MmsCodec.dir(context), "recv-1.pdu").writeBytes(pdu)
        assertEquals(b64, MmsCodec.takeDownloaded(context, "recv-1.pdu"))
        assertNull(MmsCodec.takeDownloaded(context, "recv-1.pdu"))
        assertNull(MmsCodec.takeDownloaded(context, "../recv-1.pdu"))
    }

    @Test fun theResultReceiverReportsASendAndRemovesTheFile() =
        runTest(UnconfinedTestDispatcher()) {
            val file = File(MmsCodec.dir(context), "send-5.pdu").apply { writeBytes(pdu) }
            val events = ArrayList<PlatformEvent>()
            val job = launch { PlatformEvents.events.toList(events) }
            val sent = Intent(MmsCodec.ACTION_SENT).putExtra("local_id", 5L).putExtra("file", "send-5.pdu")
            deliverTo(context, MmsResultReceiver(), sent, Activity.RESULT_OK)
            job.cancel()
            assertFalse(file.exists())
            val o = JSONObject(events.single { it.kind == "sms_status" }.json)
            assertEquals("sent", o.getString("state"))
            assertEquals(5L, o.getLong("local_id"))
        }

    @Test fun aDownloadedPduGoesToTheHandler() {
        ReceiverWork.executor = { it.run() }
        File(MmsCodec.dir(context), "recv-9.pdu").writeBytes(pdu)
        var got: Triple<Long, String, Context>? = null
        MmsCodec.downloadedHandler = MmsCodec.DownloadedHandler { c, id, p -> got = Triple(id, p, c) }
        val intent = Intent(MmsCodec.ACTION_DOWNLOADED).putExtra("local_id", 9L).putExtra("file", "recv-9.pdu")
        deliverTo(context, MmsResultReceiver(), intent, Activity.RESULT_OK)
        assertEquals(9L, got?.first)
        assertEquals(b64, got?.second)
    }
}
