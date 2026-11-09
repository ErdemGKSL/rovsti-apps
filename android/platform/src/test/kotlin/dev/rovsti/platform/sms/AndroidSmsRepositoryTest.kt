package dev.rovsti.platform.sms

import android.net.Uri
import android.provider.Telephony
import dev.rovsti.core.model.messages.DeliveryState
import dev.rovsti.core.model.messages.SmsKey
import dev.rovsti.core.model.messages.SmsRow
import dev.rovsti.core.model.messages.SmsThreadRow
import dev.rovsti.platform.CHANGE_DEBOUNCE_MS
import dev.rovsti.platform.RecordingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.rovsti.core.model.messages.SmsSend as SmsSendModel

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSmsRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sms = Robolectric.setupContentProvider(RecordingProvider::class.java, "sms")
    private val mms = Robolectric.setupContentProvider(RecordingProvider::class.java, "mms")
    private val mmsSms = Robolectric.setupContentProvider(RecordingProvider::class.java, "mms-sms")

    @Before fun fakeDivider() {
        SmsSend.divider = { _, body -> listOf(body) }
    }

    @After fun resetDivider() {
        SmsSend.divider = { m, b -> m.divideMessage(b) }
    }

    private val threadsUri = Uri.parse("content://mms-sms/conversations?simple=true")
    private val addressesUri = Uri.parse("content://mms-sms/canonical-addresses")

    private fun smsRow(
        id: Long,
        thread: Long,
        address: String,
        body: String,
        date: Long,
        type: Int = 1,
        read: Int = 1,
        sub: Int = -1,
        status: Int = -1,
    ): Map<String, Any?> =
        mapOf(
            "_id" to id,
            "thread_id" to thread,
            "address" to address,
            "body" to body,
            "date" to date,
            "type" to type,
            "read" to read,
            "sub_id" to sub,
            "status" to status,
        )

    private fun repo(
        io: kotlinx.coroutines.CoroutineDispatcher,
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(io),
    ) = AndroidSmsRepository(context, io, scope)

    @Test fun threadRowsDecodeToSmsThreadRow() =
        runTest(UnconfinedTestDispatcher()) {
            mmsSms.seed(
                threadsUri,
                mapOf("_id" to 7L, "date" to 1_700_000_100_000L, "recipient_ids" to "1 2", "snippet" to "see you"),
                mapOf("_id" to 3L, "date" to 1_700_000_000_000L, "recipient_ids" to "2", "snippet" to null),
            )
            mmsSms.seed(
                addressesUri,
                mapOf("_id" to "1", "address" to "+905551112233"),
                mapOf("_id" to "2", "address" to "05554445566"),
            )
            sms.seed(Telephony.Sms.CONTENT_URI, mapOf("thread_id" to 7L), mapOf("thread_id" to 7L))
            mms.seed(Telephony.Mms.CONTENT_URI)

            val rows = repo(UnconfinedTestDispatcher(testScheduler)).threads(10).first()

            assertEquals(
                listOf(
                    SmsThreadRow(7, listOf("+905551112233", "05554445566"), "see you", 1_700_000_100_000, 2),
                    SmsThreadRow(3, listOf("05554445566"), "", 1_700_000_000_000, 0),
                ),
                rows,
            )
        }

    @Test fun threadLimitIsHonoured() =
        runTest(UnconfinedTestDispatcher()) {
            mmsSms.seed(
                threadsUri,
                mapOf("_id" to 1L, "date" to 3L, "recipient_ids" to "1", "snippet" to "a"),
                mapOf("_id" to 2L, "date" to 2L, "recipient_ids" to "1", "snippet" to "b"),
            )
            mmsSms.seed(addressesUri, mapOf("_id" to "1", "address" to "111"))
            sms.seed(Telephony.Sms.CONTENT_URI)
            mms.seed(Telephony.Mms.CONTENT_URI)
            assertEquals(
                listOf(1L),
                repo(UnconfinedTestDispatcher(testScheduler)).threads(1).first().map { it.threadId },
            )
        }

    @Test fun insertThenRead() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repo(UnconfinedTestDispatcher(testScheduler))
            val row =
                SmsRow(0, 5, "+905551112233", "hello", 1_700_000_000_000, 2, true, 1, DeliveryState.Sent)
            sms.seed(Telephony.Sms.CONTENT_URI) // nothing there yet: not a duplicate

            val outcome = repo.insertRestored(listOf(row)) as SmsResult.Ok
            assertEquals(RestoreOutcome(inserted = 1, skipped = 0), outcome.value)
            val written = sms.inserts.single().values
            assertEquals("+905551112233", written.getAsString(Telephony.Sms.ADDRESS))
            assertEquals("hello", written.getAsString(Telephony.Sms.BODY))
            assertEquals(1_700_000_000_000, written.getAsLong(Telephony.Sms.DATE))
            assertEquals(2, written.getAsInteger(Telephony.Sms.TYPE))
            assertEquals(1, written.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID))

            // The provider now holds it: the next read decodes it back to the same row.
            sms.seed(Telephony.Sms.CONTENT_URI, smsRow(100, 5, "+905551112233", "hello", 1_700_000_000_000, 2, 1, 1, 0))
            mms.seed(Telephony.Mms.CONTENT_URI)
            val page = repo.messages(5, null, 20)
            assertEquals(listOf(row.copy(id = 100, delivery = DeliveryState.Delivered)), page.sms)
            assertFalse(page.hasOlder)
        }

    @Test fun restoreSkipsRowsThatAreAlreadyThere() =
        runTest(UnconfinedTestDispatcher()) {
            sms.respond(Telephony.Sms.CONTENT_URI) { call ->
                if (call.projection == listOf("_id") &&
                    call.args?.get(2) == "dup"
                ) {
                    listOf(mapOf("_id" to 1L))
                } else {
                    emptyList()
                }
            }
            val rows =
                listOf(
                    SmsRow(0, 1, "111", "dup", 10, 1, true, null, DeliveryState.None),
                    SmsRow(0, 1, "111", "new", 11, 1, true, null, DeliveryState.None),
                    SmsRow(0, 1, "", "no address", 12, 1, true, null, DeliveryState.None),
                )
            val outcome = (repo(UnconfinedTestDispatcher(testScheduler)).insertRestored(rows) as SmsResult.Ok).value
            assertEquals(RestoreOutcome(1, 2), outcome)
            assertEquals(1, sms.inserts.size)
        }

    @Test fun restoreWithoutTheRoleSaysSo() =
        runTest(UnconfinedTestDispatcher()) {
            sms.seed(Telephony.Sms.CONTENT_URI)
            sms.failWith = SecurityException("not the default SMS app")
            val result =
                repo(UnconfinedTestDispatcher(testScheduler)).insertRestored(
                    listOf(SmsRow(0, 1, "111", "x", 1, 1, true, null, DeliveryState.None)),
                )
            assertEquals(SmsResult.Ok(RestoreOutcome(0, 0, roleMissing = true)), result)
        }

    @Test fun messagesMergeSmsAndMmsAndFlagOlderPages() =
        runTest(UnconfinedTestDispatcher()) {
            sms.seed(
                Telephony.Sms.CONTENT_URI,
                smsRow(1, 9, "111", "newest", 5_000, 1, 0),
                smsRow(2, 9, "111", "older", 1_000, 2, 1, status = 32),
            )
            mms.seed(
                Telephony.Mms.CONTENT_URI,
                mapOf("_id" to 4L, "thread_id" to 9L, "date" to 3L, "msg_box" to 1, "read" to 1, "sub_id" to -1),
            )
            mms.seed(
                Uri.parse("content://mms/4/addr"),
                mapOf("address" to "111", "type" to 137),
                mapOf("address" to "me", "type" to 151),
            )
            mms.seed(Uri.parse("content://mms/part"), mapOf("_id" to 8L, "ct" to "text/plain", "text" to "pic caption"))

            val page = repo(UnconfinedTestDispatcher(testScheduler)).messages(9, null, 2)

            assertEquals(listOf(1L), page.sms.map { it.id })
            assertEquals(listOf(4L), page.mms.map { it.id })
            assertTrue(page.hasOlder)
            val m = page.mms.single()
            assertEquals("111", m.from)
            assertEquals(listOf("me"), m.addresses)
            assertEquals(3_000, m.dateMs)
            assertFalse(m.outgoing)
            assertEquals("pic caption", m.parts.single().text)
            assertFalse(page.sms.single().read)
            // The threshold read the provider with the paging arguments.
            val query = sms.queriesTo(Telephony.Sms.CONTENT_URI).last()
            assertEquals("thread_id = ?", query.selection)
            assertEquals("date DESC LIMIT 3", query.sortOrder)
        }

    @Test fun beforeMsPagesBothTables() =
        runTest(UnconfinedTestDispatcher()) {
            sms.seed(Telephony.Sms.CONTENT_URI)
            mms.seed(Telephony.Mms.CONTENT_URI)
            repo(UnconfinedTestDispatcher(testScheduler)).messages(9, 12_000, 10)
            assertEquals(listOf("9", "12000"), sms.queriesTo(Telephony.Sms.CONTENT_URI).last().args)
            assertEquals(listOf("9", "12"), mms.queriesTo(Telephony.Mms.CONTENT_URI).last().args)
        }

    @Test fun markReadUpdatesBothTables() =
        runTest(UnconfinedTestDispatcher()) {
            val result = repo(UnconfinedTestDispatcher(testScheduler)).markRead(9)
            assertEquals(SmsResult.Ok(Unit), result)
            assertEquals(1, sms.updates.size)
            assertEquals(1, mms.updates.size)
            assertEquals(
                1,
                sms.updates
                    .single()
                    .values
                    .getAsInteger("read"),
            )
            assertEquals("thread_id = ? AND read = 0", sms.updates.single().selection)
        }

    @Test fun deleteMessageAndThread() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repo(UnconfinedTestDispatcher(testScheduler))
            assertEquals(SmsResult.Ok(true), repo.deleteMessage(SmsKey.Sms(12)))
            assertEquals(SmsResult.Ok(true), repo.deleteMessage(SmsKey.Mms(7)))
            assertEquals(SmsResult.Ok(false), repo.deleteMessage(SmsKey.Local(1)))
            assertEquals(
                "sms",
                sms.deletes
                    .single()
                    .uri.authority,
            )
            assertEquals(
                "12",
                sms.deletes
                    .single()
                    .uri.lastPathSegment,
            )
            assertEquals(
                "7",
                mms.deletes
                    .single()
                    .uri.lastPathSegment,
            )
            assertEquals(SmsResult.Ok(Unit), repo.deleteThread(9))
            assertEquals(
                "conversations",
                mmsSms.deletes
                    .single()
                    .uri.pathSegments
                    .first(),
            )
            assertEquals(
                "9",
                mmsSms.deletes
                    .single()
                    .uri.lastPathSegment,
            )
        }

    @Test fun aDeniedWriteIsAPermissionFailure() =
        runTest(UnconfinedTestDispatcher()) {
            sms.failWith = SecurityException("no")
            val repo = repo(UnconfinedTestDispatcher(testScheduler))
            assertEquals(SmsResult.Failed(SmsResult.Reason.Permission), repo.markRead(1))
            assertEquals(SmsResult.Failed(SmsResult.Reason.Permission), repo.deleteMessage(SmsKey.Sms(1)))
        }

    @Test fun aDeniedReadIsEmpty() =
        runTest(UnconfinedTestDispatcher()) {
            sms.failWith = SecurityException("no")
            val repo = repo(UnconfinedTestDispatcher(testScheduler))
            val page = repo.messages(1, null, 5)
            assertTrue(page.sms.isEmpty() && page.mms.isEmpty() && !page.hasOlder)
            assertTrue(repo.search("%x%").sms.isEmpty())
        }

    @Test fun searchEscapesWithTheGivenPattern() =
        runTest(UnconfinedTestDispatcher()) {
            sms.seed(Telephony.Sms.CONTENT_URI, smsRow(1, 1, "111", "50% off", 10))
            val page = repo(UnconfinedTestDispatcher(testScheduler)).search("%50\\% off%", 5)
            assertEquals(listOf("50% off"), page.sms.map { it.body })
            val q = sms.queriesTo(Telephony.Sms.CONTENT_URI).last()
            assertEquals("body LIKE ? ESCAPE '\\'", q.selection)
            assertEquals(listOf("%50\\% off%"), q.args)
        }

    @Test fun threadsReQueryAfterAProviderChange() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            mmsSms.seed(threadsUri, mapOf("_id" to 1L, "date" to 1L, "recipient_ids" to "1", "snippet" to "a"))
            mmsSms.seed(addressesUri, mapOf("_id" to "1", "address" to "111"))
            sms.seed(Telephony.Sms.CONTENT_URI)
            mms.seed(Telephony.Mms.CONTENT_URI)
            val seen = ArrayList<List<SmsThreadRow>>()
            val job = backgroundScope.launch { repo(dispatcher).threads(10).toList(seen) }
            runCurrent()
            assertEquals(1, seen.size)
            mmsSms.seed(threadsUri, mapOf("_id" to 1L, "date" to 2L, "recipient_ids" to "1", "snippet" to "b"))
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            runCurrent()
            advanceTimeBy(CHANGE_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals("b", seen.last().single().snippet)
            job.cancel()
        }

    @Test fun sendQueuesAndReportsStatusEvents() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repo(UnconfinedTestDispatcher(testScheduler), backgroundScope)
            val handle = repo.send(SmsSendModel(41, listOf("+905551112233"), "hi", null))
            assertTrue(handle.accepted)
            SmsSend.emitStatus(40, null, "failed") // another send: filtered out
            SmsSend.emitStatus(41, "content://sms/100", "sent")
            val status = handle.status.first()
            assertEquals(41, status.localId)
            assertEquals(DeliveryState.Sent, status.state)
            assertEquals("content://sms/100", status.uri)
        }

    @Test fun anInvalidSendIsNotAccepted() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repo(UnconfinedTestDispatcher(testScheduler), backgroundScope)
            assertFalse(repo.send(SmsSendModel(1, emptyList(), "hi", null)).accepted)
            assertFalse(repo.send(SmsSendModel(1, listOf("1"), "", null)).accepted)
        }

    @Test fun noSimsWithoutAnySubscription() =
        runTest(UnconfinedTestDispatcher()) {
            val sims = repo(UnconfinedTestDispatcher(testScheduler)).sims()
            assertTrue(sims.sims.isEmpty())
        }
}
