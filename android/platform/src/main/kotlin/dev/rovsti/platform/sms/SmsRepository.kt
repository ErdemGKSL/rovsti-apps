package dev.rovsti.platform.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dev.rovsti.core.model.messages.DeliveryState
import dev.rovsti.core.model.messages.SmsKey
import dev.rovsti.core.model.messages.SmsMessagesPayload
import dev.rovsti.core.model.messages.SmsRow
import dev.rovsti.core.model.messages.SmsStatus
import dev.rovsti.core.model.messages.SmsThreadRow
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.liveQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.rovsti.core.model.messages.SmsSend as SmsSendModel

/** What a provider write answers: the repository never throws for a provider failure. */
sealed interface SmsResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : SmsResult<T>

    data class Failed(
        val reason: Reason,
    ) : SmsResult<Nothing>

    enum class Reason { Permission, Unknown }
}

/**
 * A send in flight. [accepted] is false when the platform could not queue it (no SIM, no `SEND_SMS`, a malformed
 * request); the [status] flow then still carries nothing. [status] replays the `sms_status` events of [localId]
 * (`Sent`, `Delivered`, `Failed`): collect it before or right after [SmsRepository.send] and cancel it on a terminal
 * state.
 */
data class SendHandle(
    val localId: Long,
    val accepted: Boolean,
    val status: Flow<SmsStatus>,
)

/** The SMS and MMS provider and the send path (AD-MSG2). Reads are cold flows that re-query on a provider change. */
interface SmsRepository {
    /** Conversations, newest first, at most [limit]. */
    fun threads(limit: Int = DEFAULT_THREADS): Flow<List<SmsThreadRow>>

    /** One page of a thread, a one-shot read: the newest [limit] messages older than [beforeMs] (null: from now). */
    suspend fun messages(
        threadId: Long,
        beforeMs: Long? = null,
        limit: Int = DEFAULT_PAGE,
    ): SmsMessagesPayload

    /** The active SIMs, empty without `READ_PHONE_STATE`. */
    suspend fun sims(): SmsSims

    /** SMS bodies matching the `LIKE` pattern [like] (`\` escapes), newest first. */
    suspend fun search(
        like: String,
        limit: Int = DEFAULT_PAGE,
    ): SmsMessagesPayload

    suspend fun markRead(threadId: Long): SmsResult<Unit>

    suspend fun deleteThread(threadId: Long): SmsResult<Unit>

    /** True when a row went away; Local keys and missing rows are false. */
    suspend fun deleteMessage(key: SmsKey): SmsResult<Boolean>

    /** Puts backed-up or deleted SMS rows back, skipping what is already there. */
    suspend fun insertRestored(rows: List<SmsRow>): SmsResult<RestoreOutcome>

    /** Queues [request] (split into parts by the system); progress arrives on [SendHandle.status]. */
    suspend fun send(request: SmsSendModel): SendHandle

    /** Sends a base64 MMS PDU built by Rust; the answer arrives as `sms_status` for [localId]. */
    suspend fun sendMms(
        localId: Long,
        pduBase64: String,
        subId: Int?,
    ): SendHandle

    companion object {
        const val DEFAULT_THREADS = 500
        const val DEFAULT_PAGE = 50
    }
}

/** The `sms_status` events of one send, typed. */
fun smsStatuses(localId: Long): Flow<SmsStatus> =
    PlatformEvents.events
        .filter { it.kind == SmsSend.EVENT_STATUS }
        .mapNotNull { event ->
            try {
                val o = org.json.JSONObject(event.json)
                SmsStatus(
                    localId = o.optLong("local_id"),
                    uri = o.optString("uri").takeIf { it.isNotEmpty() },
                    state = SmsSend.deliveryOf(o.optString("state")),
                    errorCode = if (o.has("error_code")) o.optInt("error_code") else null,
                )
            } catch (_: org.json.JSONException) {
                null
            }
        }.filter { it.localId == localId }

/** [SmsRepository] on `Telephony`; every read and write runs on [io]. */
class AndroidSmsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
) : SmsRepository {
    /**
     * The status flow of [localId], subscribed before the send starts so an immediate failure is not missed: it
     * replays the last events and stops listening at a terminal state or after [STATUS_WINDOW_MS].
     */
    private fun trackStatus(localId: Long): Flow<SmsStatus> {
        val out = MutableSharedFlow<SmsStatus>(replay = STATUS_REPLAY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(STATUS_WINDOW_MS) {
                smsStatuses(localId)
                    .transformWhile {
                        emit(it)
                        !it.state.isTerminal()
                    }.collect { out.emit(it) }
            }
        }
        return out.asSharedFlow()
    }

    private val watched =
        listOf(
            Telephony.Sms.CONTENT_URI,
            Telephony.Mms.CONTENT_URI,
            Telephony.Threads.CONTENT_URI,
            Uri.parse("content://mms-sms/"),
        )

    private suspend fun <T> write(block: () -> T): SmsResult<T> =
        withContext(io) {
            try {
                SmsResult.Ok(block())
            } catch (_: SecurityException) {
                SmsResult.Failed(SmsResult.Reason.Permission)
            } catch (_: RuntimeException) {
                SmsResult.Failed(SmsResult.Reason.Unknown)
            }
        }

    override fun threads(limit: Int): Flow<List<SmsThreadRow>> =
        liveQuery(context.contentResolver, watched, io, emptyList()) { SmsQuery.threads(context, limit) }

    override suspend fun messages(
        threadId: Long,
        beforeMs: Long?,
        limit: Int,
    ): SmsMessagesPayload =
        withContext(io) {
            try {
                SmsQuery.messages(context, threadId, beforeMs, limit)
            } catch (_: SecurityException) {
                SmsMessagesPayload(emptyList(), emptyList(), false)
            }
        }

    override suspend fun sims(): SmsSims =
        withContext(io) {
            try {
                SmsQuery.sims(context)
            } catch (_: SecurityException) {
                SmsSims(emptyList(), null)
            }
        }

    override suspend fun search(
        like: String,
        limit: Int,
    ): SmsMessagesPayload =
        withContext(io) {
            try {
                SmsQuery.search(context, like, limit)
            } catch (_: SecurityException) {
                SmsMessagesPayload(emptyList(), emptyList(), false)
            }
        }

    override suspend fun markRead(threadId: Long) = write { SmsQuery.markRead(context, threadId) }

    override suspend fun deleteThread(threadId: Long) = write { SmsQuery.deleteThread(context, threadId) }

    override suspend fun deleteMessage(key: SmsKey) = write { SmsQuery.deleteMessage(context, key) }

    override suspend fun insertRestored(rows: List<SmsRow>) = write { SmsQuery.insertRestored(context, rows) }

    override suspend fun send(request: SmsSendModel): SendHandle {
        val parsed = SmsRequest.from(request)
        val status = trackStatus(request.localId)
        val accepted = parsed != null && withContext(io) { SmsSend.send(context, parsed) }
        return SendHandle(request.localId, accepted, status)
    }

    override suspend fun sendMms(
        localId: Long,
        pduBase64: String,
        subId: Int?,
    ): SendHandle {
        val status = trackStatus(localId)
        val accepted = withContext(io) { MmsCodec.send(context, localId, pduBase64, subId) }
        return SendHandle(localId, accepted, status)
    }
}

private const val STATUS_REPLAY = 8
private const val STATUS_WINDOW_MS = 24L * 60 * 60 * 1000

private fun DeliveryState.isTerminal(): Boolean = this == DeliveryState.Delivered || this == DeliveryState.Failed
