package dev.rovsti.platform.sms

import dev.rovsti.core.model.messages.DeliveryState
import dev.rovsti.core.model.messages.FiredSms
import dev.rovsti.core.model.messages.SmsKey
import dev.rovsti.core.model.messages.SmsMessagesPayload
import dev.rovsti.core.model.messages.SmsRow
import dev.rovsti.core.model.messages.SmsStatus
import dev.rovsti.core.model.messages.SmsThreadRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import dev.rovsti.core.model.messages.SmsSend as SmsSendModel

/**
 * An in-memory [SmsRepository] for screen tests: SMS rows live in a state flow, so a write re-emits [threads]. A send
 * is recorded in [sent] and answers a `Sent` status at once (set [sendOutcome] to fail it).
 */
class FakeSmsRepository(
    rows: List<SmsRow> = emptyList(),
    private val simList: SmsSims = SmsSims(emptyList(), null),
) : SmsRepository {
    private val all = MutableStateFlow(rows)

    val sent = ArrayList<SmsSendModel>()

    /** The state every send reports. */
    var sendOutcome: DeliveryState = DeliveryState.Sent

    override fun threads(limit: Int): Flow<List<SmsThreadRow>> =
        all.map { list ->
            list
                .groupBy { it.threadId }
                .map { (id, msgs) ->
                    val newest = msgs.maxBy { it.dateMs }
                    SmsThreadRow(id, listOf(newest.address), newest.body, newest.dateMs, msgs.count { !it.read })
                }.sortedByDescending { it.dateMs }
                .take(limit)
        }

    override suspend fun messages(
        threadId: Long,
        beforeMs: Long?,
        limit: Int,
    ): SmsMessagesPayload {
        val sms = all.value.filter { it.threadId == threadId && (beforeMs == null || it.dateMs < beforeMs) }
        return SmsRules.page(sms.sortedByDescending { it.dateMs }, emptyList(), limit)
    }

    override suspend fun sims() = simList

    override suspend fun search(
        like: String,
        limit: Int,
    ): SmsMessagesPayload {
        val needle = like.trim('%').lowercase()
        return SmsRules.page(all.value.filter { it.body.lowercase().contains(needle) }, emptyList(), limit)
    }

    override suspend fun markRead(threadId: Long): SmsResult<Unit> {
        all.value = all.value.map { if (it.threadId == threadId) it.copy(read = true) else it }
        return SmsResult.Ok(Unit)
    }

    override suspend fun deleteThread(threadId: Long): SmsResult<Unit> {
        all.value = all.value.filter { it.threadId != threadId }
        return SmsResult.Ok(Unit)
    }

    override suspend fun deleteMessage(key: SmsKey): SmsResult<Boolean> {
        val before = all.value.size
        if (key is SmsKey.Sms) all.value = all.value.filter { it.id != key.id }
        return SmsResult.Ok(all.value.size < before)
    }

    override suspend fun insertRestored(rows: List<SmsRow>): SmsResult<RestoreOutcome> {
        val fresh =
            rows.filter { r ->
                all.value.none {
                    it.address == r.address && it.dateMs == r.dateMs &&
                        it.body == r.body
                }
            }
        all.value = all.value + fresh
        return SmsResult.Ok(RestoreOutcome(fresh.size, rows.size - fresh.size))
    }

    override suspend fun send(request: SmsSendModel): SendHandle {
        sent += request
        return SendHandle(request.localId, true, flowOf(SmsStatus(request.localId, null, sendOutcome)))
    }

    override suspend fun sendMms(
        localId: Long,
        pduBase64: String,
        subId: Int?,
    ) = SendHandle(localId, true, flowOf(SmsStatus(localId, null, sendOutcome)))
}

/** An in-memory [ScheduledSmsRepository]; [fire] pushes a fired item to collectors. */
class FakeScheduledSmsRepository(
    var mode: ArmMode = ArmMode.Exact,
) : ScheduledSmsRepository {
    private val items = LinkedHashMap<Long, ScheduledItem>()
    private val firedFlow = MutableSharedFlow<FiredSms>(extraBufferCapacity = 8)

    override suspend fun arm(item: ScheduledItem): ArmResult {
        if (item.id <= 0 || item.to.isEmpty()) return ArmResult.Rejected
        items[item.id] = item
        return ArmResult.Armed(mode)
    }

    override suspend fun disarm(id: Long) = items.remove(id) != null

    override suspend fun armed() = items.values.toList()

    override fun fired(): Flow<FiredSms> = firedFlow.asSharedFlow()

    override suspend fun pullFired() = "[]"

    fun fire(
        id: Long,
        ok: Boolean,
    ) {
        items.remove(id)
        firedFlow.tryEmit(FiredSms(id, ok, 0))
    }
}
