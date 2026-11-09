package dev.rovsti.platform.calllog

import android.content.ContentValues
import android.provider.CallLog
import android.provider.CallLog.Calls
import dev.rovsti.core.Envelope
import dev.rovsti.core.model.phone.BlockReason
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.platform.CHANGE_DEBOUNCE_MS
import dev.rovsti.platform.RecordingProvider
import dev.rovsti.platform.RecordingProvider.QueryCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCallLogRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, CallLog.AUTHORITY)
    private val uri = Calls.CONTENT_URI

    private fun TestScope.repository() = AndroidCallLogRepository(context, StandardTestDispatcher(testScheduler))

    private fun row(
        id: Long,
        number: String,
        type: Int,
        date: Long,
        duration: Long = 0,
        name: String? = null,
        label: String? = null,
        lookup: String? = null,
        component: String? = null,
        account: String? = null,
        reason: Int = 0,
    ): Map<String, Any?> =
        mapOf(
            Calls._ID to id,
            Calls.NUMBER to number,
            Calls.TYPE to type,
            Calls.DATE to date,
            Calls.DURATION to duration,
            Calls.CACHED_NAME to name,
            Calls.CACHED_NUMBER_LABEL to label,
            Calls.CACHED_LOOKUP_URI to lookup,
            Calls.PHONE_ACCOUNT_COMPONENT_NAME to component,
            Calls.PHONE_ACCOUNT_ID to account,
            Calls.BLOCK_REASON to reason,
        )

    /** The rows of `crates/rovsti_core/tests/fixtures/call_log_ok.json`, as the provider holds them. */
    private val fixtureRows =
        listOf(
            row(
                104,
                "+90 555 111 22 33",
                3,
                1790000000000,
                0,
                "Ayşe Yılmaz",
                "Mobile",
                "content://com.android.contacts/contacts/lookup/0r17-abc/17",
            ),
            row(103, "05551112233", 1, 1789990000000, 61),
            row(102, "+4915123456789", 5, 1789980000000),
            row(101, "", 0, 1789970000000),
        )

    @Serializable
    private data class Entries(
        val entries: List<CallLogEntry>,
    )

    private fun fixtureEntries(): List<CallLogEntry> {
        val dir = File(requireNotNull(System.getProperty("rovsti.wire.fixtures")))
        val root = Envelope.json.parseToJsonElement(File(dir, "call_log_ok.json").readText()) as JsonObject
        return Envelope.json.decodeFromString(Entries.serializer(), root.getValue("data").toString()).entries
    }

    /** What the provider serves for a query: rows are filtered by the `_id IN (...)` and the type clauses. */
    private fun serve(table: () -> List<Map<String, Any?>>) {
        provider.respond(uri) { call -> filter(table(), call) }
    }

    private fun filter(
        rows: List<Map<String, Any?>>,
        call: QueryCall,
    ): List<Map<String, Any?>> {
        val selection = call.selection.orEmpty()
        return when {
            "_id IN" in selection -> rows.filter { it[Calls._ID].toString() in call.args.orEmpty() }
            "type != 6" in selection -> rows.filter { it[Calls.TYPE] != 6 }
            "type = 6" in selection -> rows.filter { it[Calls.TYPE] == 6 }
            else -> rows
        }
    }

    @Test
    fun seededRowsDecodeEqualToTheCoreFixture() =
        runTest {
            serve { fixtureRows }
            val entries = repository().entries().first()
            assertEquals(fixtureEntries(), entries)
            assertEquals(
                listOf(
                    CallLogKind.Missed,
                    CallLogKind.Incoming,
                    CallLogKind.Rejected,
                    CallLogKind.Unknown,
                ),
                entries.map {
                    it.kind
                },
            )
            assertEquals(17L, entries[0].contactId)
        }

    @Test
    fun theQueriesAskForNewestFirstAndTheLimit() =
        runTest {
            serve { fixtureRows }
            repository().entries(10_000).first()
            val call = provider.queries.single()
            assertEquals("date DESC", call.sortOrder)
            assertTrue(call.selection!!.contains("type != 6"))
        }

    @Test
    fun blockedCallsAreOnlyInTheBlockedRead() =
        runTest {
            val blocked = row(110, "0850 000 00 00", 6, 1790000100000, 0, reason = Calls.BLOCK_REASON_BLOCKED_NUMBER)
            val system = row(109, "0850 111 11 11", 6, 1790000050000, 0, reason = Calls.BLOCK_REASON_NOT_IN_CONTACTS)
            serve { listOf(blocked, system) + fixtureRows }
            val repository = repository()
            val all = repository.entries().first()
            assertEquals(listOf(104L, 103L, 102L, 101L), all.map { it.id })
            val only = repository.blocked().first()
            assertEquals(listOf(110L, 109L), only.map { it.id })
            assertEquals(listOf(BlockReason.User, BlockReason.System), only.map { it.blockReason })
            assertEquals(null, all.first().blockReason)
        }

    @Test
    fun deleteThenRestoreRoundTripsEveryRowExceptItsId() =
        runTest {
            val withAccount =
                row(
                    105,
                    "0212 555 00 11",
                    2,
                    1790000200000,
                    15,
                    "Özgür",
                    "Work",
                    "content://com.android.contacts/contacts/lookup/k/18",
                    "com.android.phone/.PstnConnectionService",
                    "1",
                )
            val blocked = row(110, "0850 000 00 00", 6, 1790000100000, 0, reason = Calls.BLOCK_REASON_BLOCKED_NUMBER)
            val rows = listOf(withAccount, blocked) + fixtureRows
            serve { rows }
            val repository = repository()
            val before = repository.entries().first() + repository.blocked().first()
            val ids = before.map { it.id }

            val deleted = (repository.delete(ids) as CallLogResult.Ok).value
            val delete = provider.deletes.single()
            assertEquals("${Calls._ID} IN (${ids.joinToString(",") { "?" }})", delete.selection)
            assertEquals(ids.map { it.toString() }, delete.args)
            assertEquals(before.toSet(), deleted.rows.toSet())

            provider.clearRecords()
            assertEquals(CallLogResult.Ok(before.size), repository.restore(deleted.rows))
            assertEquals(before.size, provider.inserts.size)
            for (insert in provider.inserts) {
                assertEquals(1, insert.values.getAsInteger(Calls.IS_READ))
                assertEquals(0, insert.values.getAsInteger(Calls.NEW))
            }

            // The provider now holds the inserted rows under new ids; they read back equal except for the id.
            var nextId = 500L
            val restored = provider.inserts.map { rowOf(nextId++, it.values) }
            serve { restored }
            val after = repository.entries().first() + repository.blocked().first()
            assertEquals(before.map { it.copy(id = 0) }, after.map { it.copy(id = 0) })
            assertTrue(after.all { it.id >= 500 })
        }

    private fun rowOf(
        id: Long,
        v: ContentValues,
    ): Map<String, Any?> = mapOf(Calls._ID to id) + v.valueSet().associate { it.key to it.value }

    @Test
    fun aBurstOfChangesIsOneRequery() =
        runTest {
            serve { fixtureRows }
            val seen = mutableListOf<List<CallLogEntry>>()
            backgroundScope.launch { repository().entries().collect { seen += it } }
            runCurrent()
            assertEquals("the first collect reads once", 1, provider.queriesTo(uri).size)

            repeat(5) { context.contentResolver.notifyChange(uri, null) }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("nothing reads before the burst settles", 1, provider.queriesTo(uri).size)
            advanceTimeBy(CHANGE_DEBOUNCE_MS + 1)
            runCurrent()

            assertEquals("five notifications read once more", 2, provider.queriesTo(uri).size)
            assertEquals(2, seen.size)
        }

    @Test
    fun missedCountReadsTheUnseenMissedCalls() =
        runTest {
            provider.seed(uri, row(1, "1", 3, 1), row(2, "2", 3, 2))
            assertEquals(2, repository().missedCount().first())
            val selection = provider.queries.single().selection!!
            assertTrue(selection, selection.contains("new = 1") && selection.contains("type = 3"))
        }

    @Test
    fun markMissedReadClearsTheNewFlag() =
        runTest {
            repository().markMissedRead()
            val update = provider.updates.single()
            assertEquals(0, update.values.getAsInteger(Calls.NEW))
            assertEquals(listOf("3"), update.args)
        }

    @Test
    fun deleteAllDeletesWithoutASelection() =
        runTest {
            assertEquals(CallLogResult.Ok(1), repository().deleteAll())
            assertEquals(null, provider.deletes.single().selection)
        }

    @Test
    fun aDeniedAppReadsNothingAndWritesAreAPermissionFailure() =
        runTest {
            provider.failWith = SecurityException("no READ_CALL_LOG")
            val repository = repository()
            assertEquals(emptyList<CallLogEntry>(), repository.entries().first())
            assertEquals(0, repository.missedCount().first())
            val denied = CallLogResult.Failed(CallLogResult.Reason.Permission)
            assertEquals(denied, repository.delete(listOf(1)))
            assertEquals(denied, repository.deleteAll())
            provider.failWith = IllegalStateException("boom")
            assertEquals(CallLogResult.Failed(CallLogResult.Reason.Unknown), repository.deleteAll())
        }

    @Test
    fun theFakeBehavesLikeTheRealRepositoryForUndo() =
        runTest {
            val fake = FakeCallLogRepository(fixtureEntries())
            val before = fake.entries().first()
            val deleted = (fake.delete(listOf(103, 102)) as CallLogResult.Ok).value
            assertEquals(listOf(104L, 101L), fake.entries().first().map { it.id })
            fake.restore(deleted.rows)
            assertEquals(before.map { it.copy(id = 0) }, fake.entries().first().map { it.copy(id = 0) })
        }
}
