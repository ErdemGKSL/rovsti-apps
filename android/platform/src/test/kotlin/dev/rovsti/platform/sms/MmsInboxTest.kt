package dev.rovsti.platform.sms

import android.net.Uri
import android.provider.Telephony
import dev.rovsti.core.FakeRustCore
import dev.rovsti.platform.Headless
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MmsInboxTest {
    private val context = RuntimeEnvironment.getApplication()
    private val mms = Robolectric.setupContentProvider(RecordingProvider::class.java, "mms")
    private val mmsSms = Robolectric.setupContentProvider(RecordingProvider::class.java, "mms-sms")
    private val core = FakeRustCore()

    @After fun reset() {
        Headless.useCore(null)
        MmsInbox.notifier = null
    }

    private val answer =
        """{"ok":true,"transaction_id":"R7","from":"+4915112345678","to":["+15550001111"],"subject":"Hi",
            "date_s":1700000000,"text":"Hello","spam":false,"otp":null,
            "parts":[{"content_type":"text/plain","name":null,"text":"Hello"}]}"""

    @Test fun aRetrievedMessageIsStoredNotifiedAndAnnounced() =
        runTest(UnconfinedTestDispatcher()) {
            Headless.assertOffMain = false
            Headless.useCore(core)
            core.onHeadless("mms_retrieved") { answer }
            mmsSms.seed(Uri.parse("content://mms-sms/threadID"), mapOf("_id" to 42L))
            val shown = ArrayList<Pair<Long, String>>()
            MmsInbox.notifier =
                MessageNotifier { _, spec ->
                    val o = JSONObject(spec)
                    shown += o.getString("tag").removePrefix("sms:").toLong() to o.getString("title")
                }
            val events = ArrayList<PlatformEvent>()
            val job = launch { PlatformEvents.events.toList(events) }

            MmsInbox.onDownloaded(context, 5, "AAEC")
            job.cancel()

            val row = mms.inserts.first { it.uri.toString().endsWith("/inbox") }.values
            assertEquals(42L, row.getAsLong(Telephony.Mms.THREAD_ID))
            assertEquals(1_700_000_000L, row.getAsLong(Telephony.Mms.DATE))
            assertEquals(Telephony.Mms.MESSAGE_BOX_INBOX, row.getAsInteger(Telephony.Mms.MESSAGE_BOX))
            assertEquals("Hi", row.getAsString(Telephony.Mms.SUBJECT))
            assertEquals(2, mms.inserts.count { it.uri.toString().endsWith("/addr") })
            val part = mms.inserts.single { it.uri.toString().endsWith("/part") }
            assertEquals("Hello", part.values.getAsString(Telephony.Mms.Part.TEXT))
            assertEquals(listOf(42L to "+4915112345678"), shown)
            val received = JSONObject(events.single { it.kind == "sms_received" }.json)
            assertEquals(42L, received.getLong("thread_id"))
            assertEquals("Hello", received.getString("body"))
            assertEquals(1_700_000_000_000, received.getLong("date_ms"))
        }

    @Test fun noDecodeMeansNothingIsStored() {
        Headless.assertOffMain = false
        Headless.useCore(core) // mms_retrieved unscripted: no decision
        MmsInbox.onDownloaded(context, 5, "AAEC")
        assertTrue(mms.inserts.isEmpty())
    }
}
