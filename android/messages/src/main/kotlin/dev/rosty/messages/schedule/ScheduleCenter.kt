package dev.rosty.messages.schedule

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.messages.FiredSms
import dev.rosty.core.model.messages.ScheduleAddRequest
import dev.rosty.core.model.messages.ScheduleCancelRequest
import dev.rosty.core.model.messages.ScheduleLoadRequest
import dev.rosty.core.model.messages.ScheduleReconcileRequest
import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.ScheduleText
import dev.rosty.core.model.wa.ScheduledText
import dev.rosty.core.ops.MessagesOps
import dev.rosty.platform.sms.ArmMode
import dev.rosty.platform.sms.ArmResult
import dev.rosty.platform.sms.ScheduledItem
import dev.rosty.platform.sms.ScheduledSmsRepository
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The gateway's scheduled texts (the existing `wa.schedule_text`, `wa.scheduled`, `wa.cancel_scheduled`). */
interface WaScheduleRemote {
    suspend fun schedule(
        chat: String,
        text: String,
        atMs: Long,
    ): Outcome<ScheduledText>

    /** Pending items plus those finished in the last 7 days. */
    suspend fun list(): Outcome<List<ScheduledText>>

    suspend fun cancel(id: String): Outcome<Unit>
}

/** [WaScheduleRemote] over the app's linked gateway; every call answers `NotFound` while WhatsApp is not linked. */
class GatewayWaScheduleRemote(
    private val repo: WaRepository,
) : WaScheduleRemote {
    private suspend fun <T> call(block: suspend (dev.rosty.core.ops.WaGateway) -> Outcome<T>): Outcome<T> {
        val gateway = repo.gateway() ?: return Outcome.Err(RustError.NotFound(NOT_LINKED))
        return block(gateway)
    }

    override suspend fun schedule(
        chat: String,
        text: String,
        atMs: Long,
    ): Outcome<ScheduledText> = call { it.scheduleText(ScheduleText(chat, text, atMs)) }

    override suspend fun list(): Outcome<List<ScheduledText>> = call { it.scheduled() }

    override suspend fun cancel(id: String): Outcome<Unit> =
        call { gateway ->
            when (val answer = gateway.cancelScheduled(id)) {
                is Outcome.Ok -> Outcome.Ok(Unit)
                is Outcome.Err -> answer
            }
        }

    companion object {
        const val NOT_LINKED = "WhatsApp is not linked"
    }
}

/** Where the local queue is kept: the content of `scheduled.json`, the file the GPUI app wrote (ARCH 7.1). */
interface QueueStore {
    /** The stored queue, or "" when there is none. */
    fun read(): String

    fun write(json: String): Boolean
}

/** [QueueStore] as `scheduled.json` in the data directory, replaced whole (a temp file, then a rename). */
class FileQueueStore(
    private val dir: File,
) : QueueStore {
    private val file get() = File(dir, FILE)

    override fun read(): String =
        try {
            if (file.isFile) file.readText() else ""
        } catch (_: IOException) {
            ""
        }

    override fun write(json: String): Boolean =
        try {
            dir.mkdirs()
            val temp = File(dir, "$FILE.tmp")
            temp.writeText(json)
            temp.renameTo(file) ||
                run {
                    file.writeText(json)
                    temp.delete()
                    true
                }
        } catch (_: IOException) {
            false
        }

    companion object {
        const val FILE = "scheduled.json"
    }
}

/** Everything scheduled: the local SMS queue and the gateway's texts. */
data class ScheduleState(
    val local: List<ScheduledSms> = emptyList(),
    val remote: List<ScheduledText> = emptyList(),
)

/** What [ScheduleCenter.schedule] did. */
sealed interface ScheduleOutcome {
    /** It waits for its time. [late]: exact alarms are denied, Android may send the SMS a few minutes late. */
    data class Scheduled(
        val atMs: Long,
        val late: Boolean = false,
    ) : ScheduleOutcome

    /** Less than a minute ahead. */
    data object TooSoon : ScheduleOutcome

    /** A WhatsApp text while WhatsApp is not linked. */
    data object NotLinked : ScheduleOutcome

    data object Failed : ScheduleOutcome
}

/** What the app says when a scheduled message could not be sent (fired settle). */
sealed interface ScheduleNotice {
    /**
     * [count] scheduled SMS failed; [to] names the recipient when it was one. [reopen] is the newest of them, to put
     * back into a composer (number, text).
     */
    data class SmsFailed(
        val count: Int,
        val to: String?,
        val reopen: Pair<String, String>?,
    ) : ScheduleNotice

    /** The gateway could not send a scheduled WhatsApp text to [chat]. */
    data class WhatsAppFailed(
        val chat: String,
    ) : ScheduleNotice
}

/**
 * Scheduled send (E6.10, AD-MSG6), one per app.
 *
 * - **SMS**: Rust owns the queue (`schedule.*` over the content of `scheduled.json`, kept by [queue]); an item is added
 *   there and armed as one `AlarmManager` alarm through [alarms]. The alarm sends cold; what fired is settled here
 *   (`schedule.reconcile`): a sent item leaves the queue, a failed one is announced on [notices].
 * - **WhatsApp**: the gateway keeps the item and sends it ([remote]); its `scheduled` events ([live]) keep the list
 *   in step, and a text that newly failed is announced. The gateway names the chat of an item by the id it holds;
 *   [keyOf] turns it into the key the offline store keeps the chat under, so an item of a person the gateway still
 *   knows by a LID shows in the thread of their number, and [rekey] moves the items of a chat that merged.
 */
class ScheduleCenter(
    private val scope: CoroutineScope,
    private val ops: MessagesOps,
    private val alarms: ScheduledSmsRepository,
    private val remote: WaScheduleRemote,
    private val live: Flow<GatewayEvent>,
    private val queue: QueueStore,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The key the offline store keeps the chat of a WhatsApp id under. */
    private val keyOf: suspend (jid: String) -> String = { it },
) {
    private val lock = Mutex()
    private var queueJson = ""
    private var loaded = false

    /** Fired items not settled yet: they wait here while the queue could not be read (the core still starting). */
    private val unsettled = ArrayList<FiredSms>()
    private val started = AtomicBoolean(false)

    private val stateFlow = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = stateFlow.asStateFlow()

    private val noticeBus =
        MutableSharedFlow<ScheduleNotice>(
            extraBufferCapacity = NOTICE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val notices: SharedFlow<ScheduleNotice> = noticeBus.asSharedFlow()

    /** Loads the queue, settles what fired while nobody looked, and follows the alarms and the gateway. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            lock.withLock { ensureLoaded() }
            settle(parseFired(alarms.pullFired()))
        }
        scope.launch { alarms.fired().collect { settle(listOf(it)) } }
        scope.launch { live.collect { if (it is GatewayEvent.Scheduled) onRemote(it) } }
        scope.launch { refreshRemote() }
    }

    /** The caller holds [lock]. A corrupt or missing file reads as an empty queue (`schedule.load`). */
    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = withContext(io) { queue.read() }
        val answer = ops.scheduleLoad(ScheduleLoadRequest(stored)) as? Outcome.Ok ?: return
        queueJson = answer.value.queueJson
        stateFlow.update { it.copy(local = answer.value.items) }
        loaded = true
    }

    /** The caller holds [lock]. */
    private suspend fun save(
        json: String,
        items: List<ScheduledSms>,
    ) {
        queueJson = json
        stateFlow.update { it.copy(local = items) }
        withContext(io) { queue.write(json) }
    }

    /** Schedules [body] for [atMs]: an SMS into the local queue with its alarm, a WhatsApp text on the gateway. */
    suspend fun schedule(
        dest: ScheduleDest,
        body: String,
        atMs: Long,
    ): ScheduleOutcome {
        val text = body.trim()
        if (text.isEmpty()) return ScheduleOutcome.Failed
        if (atMs < now() + ScheduleRules.MIN_LEAD_MS) return ScheduleOutcome.TooSoon
        return when (dest) {
            is ScheduleDest.Sms -> scheduleSms(dest, text, atMs)
            is ScheduleDest.WhatsApp -> scheduleWhatsApp(dest, text, atMs)
        }
    }

    private suspend fun scheduleSms(
        dest: ScheduleDest.Sms,
        body: String,
        atMs: Long,
    ): ScheduleOutcome =
        lock.withLock {
            ensureLoaded()
            if (!loaded) return@withLock ScheduleOutcome.Failed
            val created = now()
            val item =
                ScheduledSms(
                    id = ScheduleRules.nextId(stateFlow.value.local, created),
                    atMs = atMs,
                    target = ScheduleTarget.Sms(dest.to, dest.subId),
                    body = body,
                    createdMs = created,
                )
            val added =
                ops.scheduleAdd(ScheduleAddRequest(queueJson, item, created)) as? Outcome.Ok
                    ?: return@withLock ScheduleOutcome.Failed
            save(added.value.queueJson, added.value.items)
            when (val armed = alarms.arm(ScheduledItem(item.id, atMs, dest.to, body, dest.subId))) {
                is ArmResult.Armed -> {
                    ScheduleOutcome.Scheduled(atMs, late = armed.mode == ArmMode.Inexact)
                }

                ArmResult.Rejected -> {
                    // No alarm, so it would never be sent: take it out again.
                    (ops.scheduleCancel(ScheduleCancelRequest(queueJson, item.id)) as? Outcome.Ok)?.let {
                        save(it.value.queueJson, it.value.items)
                    }
                    ScheduleOutcome.Failed
                }
            }
        }

    private suspend fun scheduleWhatsApp(
        dest: ScheduleDest.WhatsApp,
        body: String,
        atMs: Long,
    ): ScheduleOutcome =
        when (val answer = remote.schedule(dest.chat, body, atMs)) {
            is Outcome.Ok -> {
                val item = keyed(answer.value)
                stateFlow.update { it.copy(remote = ScheduleRules.upsert(it.remote, item)) }
                ScheduleOutcome.Scheduled(atMs)
            }

            is Outcome.Err -> {
                if (answer.error is RustError.NotFound) ScheduleOutcome.NotLinked else ScheduleOutcome.Failed
            }
        }

    /** Takes a waiting item back. True when it is gone (also when the gateway had sent or dropped it already). */
    suspend fun cancel(key: StripKey): Boolean =
        when (key) {
            is StripKey.Local -> {
                lock.withLock {
                    ensureLoaded()
                    if (!loaded) return@withLock false
                    val answer =
                        ops.scheduleCancel(ScheduleCancelRequest(queueJson, key.id)) as? Outcome.Ok
                            ?: return@withLock false
                    save(answer.value.queueJson, answer.value.items)
                    alarms.disarm(key.id)
                    answer.value.removed
                }
            }

            is StripKey.Gateway -> {
                when (val answer = remote.cancel(key.id)) {
                    is Outcome.Ok -> {
                        stateFlow.update { s -> s.copy(remote = s.remote.filterNot { it.id == key.id }) }
                        true
                    }

                    is Outcome.Err -> {
                        // Gone already (sent meanwhile): the list was stale either way.
                        val gone = answer.error is RustError.NotFound && stateFlow.value.remote.any { it.id == key.id }
                        if (gone) {
                            stateFlow.update { s -> s.copy(remote = s.remote.filterNot { it.id == key.id }) }
                            refreshRemote()
                        }
                        gone
                    }
                }
            }
        }

    /** Reads the gateway's list again (a conversation opened, the link changed). Nothing changes when it fails. */
    suspend fun refreshRemote() {
        val list = (remote.list() as? Outcome.Ok)?.value?.map { keyed(it) } ?: return
        stateFlow.update { it.copy(remote = list) }
    }

    /** Fired settle of the local queue: sent items leave it, failed ones are announced. */
    internal suspend fun settle(fired: List<FiredSms>) {
        val failed =
            lock.withLock {
                unsettled += fired
                ensureLoaded()
                // Never reconcile against a queue that was not read: that would save an empty one over the file.
                if (!loaded || unsettled.isEmpty()) return@withLock emptyList()
                val answer =
                    ops.scheduleReconcile(ScheduleReconcileRequest(queueJson, unsettled.toList())) as? Outcome.Ok
                        ?: return@withLock emptyList()
                unsettled.clear()
                save(answer.value.queueJson, answer.value.items)
                answer.value.failed
            }
        if (failed.isEmpty()) return
        val sms = failed.mapNotNull { item -> (item.target as? ScheduleTarget.Sms)?.let { item to it } }
        val newest = sms.maxByOrNull { it.first.atMs }
        noticeBus.tryEmit(
            ScheduleNotice.SmsFailed(
                count = failed.size,
                to =
                    sms
                        .singleOrNull()
                        ?.second
                        ?.to
                        ?.takeIf { failed.size == 1 },
                reopen = newest?.let { (item, target) -> target.to to item.body },
            ),
        )
    }

    private suspend fun keyed(item: ScheduledText): ScheduledText {
        val key = keyOf(item.chat)
        return if (key == item.chat) item else item.copy(chat = key)
    }

    /** The WhatsApp chat [from] is [into] now (two chats of one person became one): its scheduled texts follow. */
    fun rekey(
        from: String,
        into: String,
    ) {
        if (from == into) return
        stateFlow.update { s ->
            if (s.remote.none { it.chat == from }) {
                s
            } else {
                s.copy(remote = s.remote.map { if (it.chat == from) it.copy(chat = into) else it })
            }
        }
    }

    /**
     * The WhatsApp account is another one, or the link is gone: the scheduled texts in memory belonged to it (the
     * gateway dropped its own rows with the account).
     */
    fun forgetWhatsApp() {
        stateFlow.update { if (it.remote.isEmpty()) it else it.copy(remote = emptyList()) }
    }

    private suspend fun onRemote(event: GatewayEvent.Scheduled) {
        val item =
            keyed(ScheduledText(event.id, event.chat, event.text, event.sendAtMs, event.createdMs, event.state))
        val before = stateFlow.value.remote.firstOrNull { it.id == item.id }
        stateFlow.update { it.copy(remote = ScheduleRules.upsert(it.remote, item)) }
        if (ScheduleRules.newlyFailed(before, item)) noticeBus.tryEmit(ScheduleNotice.WhatsAppFailed(item.chat))
    }

    companion object {
        private const val NOTICE_BUFFER = 8
        private val lenient = Json { ignoreUnknownKeys = true }

        /** Pure: `ScheduledSms.pullFired` answers a JSON array of `{id, ok, at_ms}`; anything unreadable is nothing. */
        fun parseFired(json: String): List<FiredSms> =
            try {
                lenient.decodeFromString(ListSerializer(FiredSms.serializer()), json)
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
    }
}
