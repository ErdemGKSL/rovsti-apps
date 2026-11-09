package dev.rovsti.platform.calllog

import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [CallLogRepository] for screen tests: the rows live in a state flow, so a write re-emits the reads like
 * the provider does. Blocked rows (kind [CallLogKind.Blocked]) show only in [blocked]. A restored row gets a new id.
 * Set [failWith] to make every write answer that failure.
 */
class FakeCallLogRepository(
    rows: List<CallLogEntry> = emptyList(),
) : CallLogRepository {
    private val all = MutableStateFlow(rows.sortedByDescending { it.dateMs })
    private var nextId = (rows.maxOfOrNull { it.id } ?: 0L) + 1

    /** When set, every write answers this failure and changes nothing. */
    var failWith: CallLogResult.Reason? = null

    /** How many times the missed-call notification was cleared. */
    var markedRead = 0
        private set

    /** Unseen missed calls the badge shows. */
    val missed = MutableStateFlow(0)

    override fun entries(limit: Int): Flow<List<CallLogEntry>> =
        all.map { rows -> rows.filter { it.kind != CallLogKind.Blocked }.take(limit) }

    override fun blocked(limit: Int): Flow<List<CallLogEntry>> =
        all.map { rows -> rows.filter { it.kind == CallLogKind.Blocked }.take(limit) }

    override fun missedCount(): Flow<Int> = missed

    override suspend fun delete(ids: List<Long>): CallLogResult<Restorable> {
        failWith?.let { return CallLogResult.Failed(it) }
        val gone = all.value.filter { it.id in ids }
        all.value = all.value - gone.toSet()
        return CallLogResult.Ok(Restorable(gone))
    }

    override suspend fun restore(rows: List<CallLogEntry>): CallLogResult<Int> {
        failWith?.let { return CallLogResult.Failed(it) }
        val back = rows.map { it.copy(id = nextId++) }
        all.value = (all.value + back).sortedByDescending { it.dateMs }
        return CallLogResult.Ok(back.size)
    }

    override suspend fun deleteAll(): CallLogResult<Int> {
        failWith?.let { return CallLogResult.Failed(it) }
        val n = all.value.size
        all.value = emptyList()
        return CallLogResult.Ok(n)
    }

    override suspend fun markMissedRead(): Boolean {
        markedRead++
        missed.value = 0
        return true
    }
}
