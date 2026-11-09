package dev.rovsti.platform.sms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import dev.rovsti.bridge.SmsStatusReceiver
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.RecordingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSmsManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsSendTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val sms = Robolectric.setupContentProvider(RecordingProvider::class.java, "sms")
    private val manager: SmsManager get() = context.getSystemService(SmsManager::class.java)

    /** 153 characters per part, as GSM-7 multipart sends split. */
    private fun split(
        @Suppress("UNUSED_PARAMETER") sms: SmsManager,
        body: String,
    ): List<String> = body.chunked(153)

    @Before fun fakeDivider() {
        SmsSend.divider = ::split
    }

    @After fun reset() {
        ShadowSmsManager.reset()
        SmsSend.divider = { m, b -> m.divideMessage(b) }
    }

    @Test fun aLongBodyIsSplitIntoThePartsDivideMessageReturns() {
        val body = "word ".repeat(120) // 600 characters: several GSM parts
        val expected = split(manager, body)
        assertTrue(expected.size > 1)

        assertTrue(SmsSend.send(context, SmsRequest(5, listOf("+905551112233"), body, null)))

        val sent = shadowOf(manager).lastSentMultipartTextMessageParams
        assertEquals("+905551112233", sent.destinationAddress)
        assertEquals(expected, sent.parts)
        assertEquals(expected.size, sent.sentIntents.size)
        assertEquals(expected.size, sent.deliveryIntents.size)
        // Every part has its own PendingIntent carrying its index, the part count and the provider row.
        val extras = sent.sentIntents.map { shadowOf(it).savedIntent }
        assertEquals(expected.indices.toList(), extras.map { it.getIntExtra("part", -1) })
        assertTrue(extras.all { it.getIntExtra("parts", 0) == expected.size && it.getStringExtra("kind") == "sent" })
        assertTrue(extras.all { it.getLongExtra("local_id", 0) == 5L && it.action == SmsSend.ACTION_STATUS })
        assertEquals(
            setOf("delivery"),
            sent.deliveryIntents.map { shadowOf(it).savedIntent.getStringExtra("kind") }.toSet(),
        )
        val codes = (sent.sentIntents + sent.deliveryIntents).map { shadowOf(it).requestCode }
        assertEquals("request codes are unique", codes.size, codes.toSet().size)
        assertEquals(SmsStatusReceiver::class.java.name, extras.first().component?.className)
    }

    @Test fun aShortBodyIsOneTextMessageAndAnOutboxRowIsWritten() {
        assertTrue(SmsSend.send(context, SmsRequest(6, listOf("111", "222"), "hi", null)))

        val last = shadowOf(manager).lastSentTextMessageParams
        assertEquals("222", last.destinationAddress)
        assertEquals("hi", last.text)
        assertEquals(2, sms.inserts.size)
        val row = sms.inserts.first().values
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, row.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(Telephony.Sms.STATUS_PENDING, row.getAsInteger(Telephony.Sms.STATUS))
        assertEquals("hi", row.getAsString(Telephony.Sms.BODY))
        val uri = shadowOf(last.sentIntent).savedIntent.getStringExtra("uri")
        assertTrue(uri!!.startsWith("content://sms/"))
    }

    @Test fun theRequestParserRejectsWhatIsNotASend() {
        assertNull(SmsRequest.parse("""{"to":[],"body":"x"}"""))
        assertNull(SmsRequest.parse("""{"to":["1"],"body":""}"""))
        assertNull(SmsRequest.parse("nope"))
        assertEquals(
            SmsRequest(3, listOf("1", "2"), "b", 4),
            SmsRequest.parse("""{"local_id":3,"to":[" 1 ","","2"],"body":"b","sub_id":4}"""),
        )
        assertFalse(SmsSend.send(context, "nope"))
    }

    @Test fun statusCodesMapAsTheyDidBefore() {
        assertEquals("failed", SmsSend.stateFor("sent", false, 0, 3))
        assertNull(SmsSend.stateFor("sent", true, 0, 3))
        assertNull(SmsSend.stateFor("delivery", true, 1, 3))
        assertEquals("sent", SmsSend.stateFor("sent", true, 2, 3))
        assertEquals("delivered", SmsSend.stateFor("delivery", true, 0, 1))
        assertEquals("failed", SmsSend.stateFor("delivery", false, 2, 3))

        assertEquals(Telephony.Sms.STATUS_COMPLETE, SmsSend.providerStatus("delivered"))
        assertEquals(Telephony.Sms.STATUS_FAILED, SmsSend.providerStatus("failed"))
        assertEquals(Telephony.Sms.STATUS_PENDING, SmsSend.providerStatus("sent"))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, SmsSend.providerType("sent"))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, SmsSend.providerType("delivered"))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, SmsSend.providerType("failed"))
        assertNull(SmsSend.providerType("pending"))
    }

    @Test fun theStatusPayloadIsTheOldOne() {
        val failed = JSONObject(SmsSend.statusJson(9, "content://sms/3", "failed", 1))
        assertEquals(setOf("local_id", "state", "uri", "error_code"), failed.keys().asSequence().toSet())
        assertEquals("content://sms/3", failed.getString("uri"))
        assertEquals(1, failed.getInt("error_code"))
        assertEquals("""{"local_id":9,"state":"sent"}""", SmsSend.statusJson(9, null, "sent"))
    }

    private fun deliver(
        intent: Intent,
        code: Int,
    ) = deliverTo(context, SmsStatusReceiver(), intent, code)

    private fun statusIntent(
        kind: String,
        part: Int,
        parts: Int,
    ) = Intent(SmsSend.ACTION_STATUS)
        .putExtra("local_id", 12L)
        .putExtra("kind", kind)
        .putExtra("part", part)
        .putExtra("parts", parts)
        .putExtra("uri", "content://sms/55")

    @Test fun theReceiverEmitsSmsStatusAndUpdatesTheProvider() =
        runTest(UnconfinedTestDispatcher()) {
            val events = ArrayList<PlatformEvent>()
            val job = launch { PlatformEvents.events.toList(events) }

            deliver(statusIntent("sent", 0, 2), Activity.RESULT_OK) // first of two parts: nothing yet
            assertTrue(events.isEmpty())
            deliver(statusIntent("sent", 1, 2), Activity.RESULT_OK)
            deliver(statusIntent("delivery", 0, 1), Activity.RESULT_OK)
            deliver(statusIntent("sent", 0, 1), SmsManager.RESULT_ERROR_GENERIC_FAILURE)
            job.cancel()

            val states = events.map { JSONObject(it.json).getString("state") }
            assertEquals(listOf("sent", "delivered", "failed"), states)
            assertTrue(events.all { it.kind == "sms_status" })
            val failed = JSONObject(events.last().json)
            assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, failed.getInt("error_code"))
            assertEquals(12L, failed.getLong("local_id"))
            assertEquals("content://sms/55", failed.getString("uri"))
            assertFalse(JSONObject(events.first().json).has("error_code"))
            // The provider row followed: type Sent, then Complete, then Failed.
            assertEquals(3, sms.updates.size)
            assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, sms.updates[0].values.getAsInteger(Telephony.Sms.TYPE))
            assertEquals(Telephony.Sms.STATUS_COMPLETE, sms.updates[1].values.getAsInteger(Telephony.Sms.STATUS))
            assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, sms.updates[2].values.getAsInteger(Telephony.Sms.TYPE))
        }
}
