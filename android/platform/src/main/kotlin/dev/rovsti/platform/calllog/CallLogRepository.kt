package dev.rovsti.platform.calllog

import android.content.Context
import android.provider.CallLog
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.platform.liveQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** What a call-log write answers: the repository never throws for a provider failure. */
sealed interface CallLogResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : CallLogResult<T>

    data class Failed(
        val reason: Reason,
    ) : CallLogResult<Nothing>

    enum class Reason { Permission, Unknown }
}

/** Rows a delete took out, kept whole so Undo can put them back ([CallLogRepository.restore]). */
data class Restorable(
    val rows: List<CallLogEntry>,
)

/**
 * The system call log. Reads are cold [Flow]s that emit once on collect and again after every change of the call log
 * (newest first; blocked calls only in [blocked]); writes are suspend and answer a [CallLogResult].
 */
interface CallLogRepository {
    /** Recents rows, at most [limit] (500 at most). */
    fun entries(limit: Int = CallLogQuery.DEFAULT_LIMIT): Flow<List<CallLogEntry>>

    /** Blocked calls with their reason, at most [limit] (200 at most). */
    fun blocked(limit: Int = CallLogQuery.BLOCKED_LIMIT): Flow<List<CallLogEntry>>

    /** Unseen missed calls, for the tab badge. */
    fun missedCount(): Flow<Int>

    /** Deletes the rows with [ids]; the answer holds them for [restore]. */
    suspend fun delete(ids: List<Long>): CallLogResult<Restorable>

    /** Puts deleted rows back as read; the answer is how many were written. The new rows get new ids. */
    suspend fun restore(rows: List<CallLogEntry>): CallLogResult<Int>

    suspend fun deleteAll(): CallLogResult<Int>

    /** Opening Recents: clears the `NEW` flag and the system missed-call notification. True when Telecom was asked. */
    suspend fun markMissedRead(): Boolean
}

/** [CallLogRepository] on `CallLog.Calls`; every read and write runs on [io]. */
class AndroidCallLogRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : CallLogRepository {
    private fun <T> live(
        empty: T,
        query: () -> T,
    ): Flow<T> = liveQuery(context.contentResolver, listOf(CallLog.Calls.CONTENT_URI), io, empty, query)

    private suspend fun <T> write(block: () -> T): CallLogResult<T> =
        withContext(io) {
            try {
                CallLogResult.Ok(block())
            } catch (_: SecurityException) {
                CallLogResult.Failed(CallLogResult.Reason.Permission)
            } catch (_: RuntimeException) {
                CallLogResult.Failed(CallLogResult.Reason.Unknown)
            }
        }

    override fun entries(limit: Int) = live(emptyList()) { CallLogQuery.entries(context, limit) }

    override fun blocked(limit: Int) = live(emptyList()) { CallLogQuery.blocked(context, limit) }

    override fun missedCount() = live(0) { CallLogQuery.missedCount(context) }

    override suspend fun delete(ids: List<Long>) =
        write {
            val rows = CallLogQuery.byIds(context, ids)
            CallLogWrite.delete(context, ids)
            Restorable(rows)
        }

    override suspend fun restore(rows: List<CallLogEntry>) = write { CallLogWrite.restore(context, rows) }

    override suspend fun deleteAll() = write { CallLogWrite.deleteAll(context) }

    override suspend fun markMissedRead() = withContext(io) { MissedCalls.markRead(context) }
}
