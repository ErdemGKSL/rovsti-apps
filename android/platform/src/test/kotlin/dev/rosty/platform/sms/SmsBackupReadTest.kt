package dev.rosty.platform.sms

import android.content.ContentValues
import android.net.Uri
import android.provider.Telephony
import dev.rosty.core.model.messages.DeliveryState
import dev.rosty.core.model.messages.MmsPart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The backup reads ([SmsRepository.backupSms], [SmsRepository.backupMms]) on a provider that stores rows and honours
 * selections and `LIMIT` ([TelephonyTestProvider]): every row once across page boundaries inside rows of the same
 * date, MMS with text parts and addresses only, and no attachment touched or cached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsBackupReadTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sms = Robolectric.setupContentProvider(TelephonyTestProvider::class.java, "sms")
    private val mms = Robolectric.setupContentProvider(TelephonyTestProvider::class.java, "mms")
    private val resolver = context.contentResolver

    private fun putSms(
        address: String,
        body: String,
        date: Long,
        type: Int = 1,
        read: Int = 1,
        sub: Int = -1,
    ): Long =
        resolver
            .insert(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply {
                    put("address", address)
                    put("body", body)
                    put("date", date)
                    put("type", type)
                    put("read", read)
                    put("sub_id", sub)
                },
            )!!
            .lastPathSegment!!
            .toLong()

    private fun putMms(
        thread: Long,
        dateSeconds: Long,
        box: Int = 1,
        from: String = A,
        to: String = ME,
        parts: List<Pair<String, String?>> = listOf("text/plain" to "caption"),
    ): Long {
        val id =
            resolver
                .insert(
                    Telephony.Mms.CONTENT_URI,
                    ContentValues().apply {
                        put("thread_id", thread)
                        put("date", dateSeconds)
                        put("msg_box", box)
                        put("read", 1)
                    },
                )!!
                .lastPathSegment!!
                .toLong()
        for ((address, type) in listOf(from to 137, to to 151)) {
            resolver.insert(
                Uri.parse("content://mms/$id/addr"),
                ContentValues().apply {
                    put("address", address)
                    put("type", type)
                },
            )
        }
        for ((ct, text) in parts) {
            resolver.insert(
                Uri.parse("content://mms/part"),
                ContentValues().apply {
                    put("mid", id)
                    put("ct", ct)
                    put("text", text)
                },
            )
        }
        return id
    }

    @Test fun backupSmsPagesThroughSameDateRowsWithoutLossOrRepeats() =
        runTest(UnconfinedTestDispatcher()) {
            val dates = listOf(5_000L, 5_000, 5_000, 4_000, 4_000, 3_000, 1_000)
            val ids = dates.mapIndexed { i, d -> putSms(if (i % 2 == 0) A else B, "m$i", d) }
            val repo = AndroidSmsRepository(context, UnconfinedTestDispatcher(testScheduler))

            val pages = repo.backupSms(pageSize = 3).toList()

            assertEquals(listOf(3, 3, 1), pages.map { it.size })
            val expected =
                ids.zip(dates).sortedWith(
                    compareByDescending<Pair<Long, Long>> {
                        it.second
                    }.thenByDescending { it.first },
                )
            assertEquals(expected.map { it.first }, pages.flatten().map { it.id })
        }

    @Test fun backupSmsDecodesEveryColumn() =
        runTest(UnconfinedTestDispatcher()) {
            val id = putSms(A, "Merhaba", 1_700_000_000_000, type = 2, read = 0, sub = 1)
            val row =
                AndroidSmsRepository(context, UnconfinedTestDispatcher(testScheduler))
                    .backupSms()
                    .toList()
                    .single()
                    .single()
            assertEquals(id, row.id)
            assertEquals(A, row.address)
            assertEquals("Merhaba", row.body)
            assertEquals(1_700_000_000_000, row.dateMs)
            assertEquals(2, row.box)
            assertFalse(row.read)
            assertEquals(1, row.subId)
            assertEquals(DeliveryState.Sent, row.delivery)
            assertTrue("a thread id from the provider", row.threadId > 0)
        }

    @Test fun backupMmsKeepsTextPartsAndAddressesAndTouchesNoAttachment() =
        runTest(UnconfinedTestDispatcher()) {
            val id =
                putMms(
                    thread = 9,
                    dateSeconds = 1_700_000_000,
                    parts =
                        listOf(
                            "application/smil" to "<smil/>",
                            "image/jpeg" to null,
                            "text/plain" to "Fotoğraf",
                            "text/x-vCard" to "BEGIN:VCARD",
                        ),
                )
            putMms(thread = 9, dateSeconds = 1_600_000_000, box = 2, from = ME, to = B, parts = emptyList())
            val repo = AndroidSmsRepository(context, UnconfinedTestDispatcher(testScheduler))

            val rows = repo.backupMms().toList().flatten()

            assertEquals(2, rows.size)
            val first = rows.first()
            assertEquals(id, first.id)
            assertEquals(9, first.threadId)
            assertEquals(1_700_000_000_000, first.dateMs)
            assertEquals(A, first.from)
            assertEquals(listOf(ME), first.addresses)
            assertFalse(first.outgoing)
            assertEquals(
                listOf(MmsPart("text/plain", "Fotoğraf", null), MmsPart("text/x-vCard", "BEGIN:VCARD", null)),
                first.parts,
            )
            assertTrue(rows.last().outgoing)
            assertTrue(rows.last().parts.isEmpty())
            assertEquals(0, mms.filesOpened)
            val cache = File(context.cacheDir, "mms_parts")
            assertFalse("the backup read wrote to the cache", cache.exists())

            // The thread page does copy the image: the rig would have seen a backup read that did.
            repo.messages(9, null, 10)
            assertEquals(1, mms.filesOpened)
        }

    @Test fun backupMmsPagesThroughTheSameSecond() =
        runTest(UnconfinedTestDispatcher()) {
            val ids =
                (1..5).map {
                    putMms(
                        thread = 1,
                        dateSeconds = 1_700_000_000,
                        parts = listOf("text/plain" to "p$it"),
                    )
                }
            val pages =
                AndroidSmsRepository(context, UnconfinedTestDispatcher(testScheduler)).backupMms(pageSize = 2).toList()
            assertEquals(listOf(2, 2, 1), pages.map { it.size })
            assertEquals(ids.reversed(), pages.flatten().map { it.id })
            assertEquals((5 downTo 1).map { "p$it" }, pages.flatten().map { it.parts.single().text })
        }

    @Test fun anEmptyProviderEmitsNoPage() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = AndroidSmsRepository(context, UnconfinedTestDispatcher(testScheduler))
            assertTrue(repo.backupSms().toList().isEmpty())
            assertTrue(repo.backupMms().toList().isEmpty())
        }

    @Test fun aFullLastPageEndsWithOneEmptyRead() =
        runTest(UnconfinedTestDispatcher()) {
            repeat(4) { putSms(A, "m$it", 1_000L + it) }
            val pages =
                AndroidSmsRepository(context, UnconfinedTestDispatcher(testScheduler)).backupSms(pageSize = 2).toList()
            assertEquals(listOf(2, 2), pages.map { it.size })
        }

    private companion object {
        // The +90 555 000 00 0x test range.
        const val A = "+905550000001"
        const val B = "+905550000002"
        const val ME = "+905550000003"
    }
}
