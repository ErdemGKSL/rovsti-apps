package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.phone.BuildRowsRequest
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.phone.ExtraCall
import dev.rovsti.core.model.phone.GroupRequest
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.MergeExtrasRequest
import dev.rovsti.core.model.phone.MissedExtrasRequest
import dev.rovsti.core.model.phone.RecentsRow
import dev.rovsti.core.model.phone.RefreshDelta
import dev.rovsti.core.model.phone.RefreshDeltaRequest
import dev.rovsti.core.model.phone.RowView
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.telephony.RebuildCoalescer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What Recents shows. [rows] and [views] line up (`views[i]` draws `rows[i]`); the Rust core decided the grouping, the
 * order, the sections' text and every label. [delta] is what changed at the top since the previous model: [RefreshDelta.fresh]
 * rows arrived, [RefreshDelta.shift] rows were pushed down (the list animates only those). [missedLabel] is the
 * tab badge text (null for none, "99+" above 99).
 */
data class RecentsModel(
    val rows: ImmutableList<RecentsRow> = persistentListOf(),
    val views: ImmutableList<RowView> = persistentListOf(),
    val delta: RefreshDelta = RefreshDelta(fresh = 0, shift = 0),
    val missedCount: Int = 0,
    val missedLabel: String? = null,
    /** False until the first build has finished. */
    val ready: Boolean = false,
    /** Set when the last build failed (the rows are then the previous build's); null after a good build. */
    val error: RustError? = null,
)

/**
 * The Recents model, rebuilt from the system call log and the WhatsApp calls (`recents.group`, `merge_extras`,
 * `build`, `refresh_delta`, `missed_extras`). Source changes call [RebuildCoalescer.schedule], so a burst (the log
 * observer fires several times for one call) is one rebuild after [windowMs]; builds run one at a time on the newest
 * inputs. A failed op keeps the previous rows and sets [RecentsModel.error]
 * (the next good build clears it). Builds run on [scope] (not the main thread); the ops themselves run on the Rust dispatcher.
 *
 * [lastSeenMs] is the time the user last opened Recents (the missed badge counts calls after it). [locale] and [clock]
 * are read at each build (time zone and 12/24 h can change while the app runs).
 */
class RecentsRepository(
    private val callLog: CallLogRepository,
    private val ops: PhoneOps,
    private val scope: CoroutineScope,
    private val extras: Flow<List<ExtraCall>> = flowOf(emptyList()),
    private val locale: () -> LocaleTime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lastSeenMs: () -> Long = { 0L },
    private val windowMs: Long = RebuildCoalescer.WINDOW_MS,
) {
    private val state = MutableStateFlow(RecentsModel())
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    @Volatile private var entries: List<CallLogEntry> = emptyList()

    /** The call-log entries of the last build's source, newest first (what redial reads). */
    val callEntries: List<CallLogEntry> get() = entries

    @Volatile private var wa: List<ExtraCall> = emptyList()

    /** The WhatsApp calls of the last build's source, newest first (the details sheets read their history from it). */
    val extraCalls: List<ExtraCall> get() = wa
    private var delayed: Job? = null

    private val coalescer =
        RebuildCoalescer(
            post = { task, ms ->
                delayed =
                    scope.launch {
                        delay(ms)
                        task.run()
                    }
            },
            cancel = { delayed?.cancel() },
            windowMs = windowMs,
            rebuild = { pending.trySend(Unit) },
        )

    val model: StateFlow<RecentsModel> = state.asStateFlow()

    /** Starts observing the sources; calling it again does nothing. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (ignored in pending) build()
        }
        scope.launch {
            combine(callLog.entries(), extras) { log, extra -> log to extra }.collect { (log, extra) ->
                val first = !state.value.ready
                entries = log
                wa = extra
                // The first build is not worth waiting for; later bursts are merged.
                if (first) coalescer.now() else coalescer.schedule()
            }
        }
    }

    /** Rebuilds at once (the user came back to the tab, the clock or the locale changed). */
    fun refreshNow() = coalescer.now()

    private suspend fun build() {
        val log = entries
        val extra = wa
        val grouped = ops.recentsGroup(GroupRequest(log)).orFail() ?: return
        val merged =
            if (extra.isEmpty()) {
                grouped.rows
            } else {
                ops.mergeExtras(MergeExtrasRequest(grouped.rows, extra)).orFail()?.rows ?: return
            }
        val views = ops.recentsBuild(BuildRowsRequest(merged, clock(), locale())).orFail()?.views ?: return
        val old = state.value.rows
        val delta = ok(ops.refreshDelta(RefreshDeltaRequest(old, merged))) ?: RefreshDelta(0, 0)
        val missed = ok(ops.missedExtras(MissedExtrasRequest(log, extra, lastSeenMs())))
        state.value =
            RecentsModel(
                rows = merged.toImmutableList(),
                views = views.toImmutableList(),
                delta = delta,
                missedCount = missed?.total ?: 0,
                missedLabel = missed?.label,
                ready = true,
            )
    }

    /** The value of a good answer; a failed one keeps the previous model and records the error. */
    private fun <T> Outcome<T>.orFail(): T? =
        when (this) {
            is Outcome.Ok -> {
                value
            }

            is Outcome.Err -> {
                state.update { it.copy(error = error) }
                null
            }
        }

    private fun <T> ok(out: Outcome<T>): T? = (out as? Outcome.Ok)?.value

    companion object {
        /**
         * The WhatsApp calls the gateway lists, as the extra calls Recents merges in. A peer without a phone number
         * (a `@lid` or a group) cannot be called back from Recents and is left out, as in the Rust history
         * (`rovsti_app_core::phone::wa_call::extra_calls`).
         */
        fun extraCalls(calls: List<CallSummary>): List<ExtraCall> =
            calls.mapNotNull { call ->
                val number = dialNumber(call.peer) ?: return@mapNotNull null
                val ended = call.phase as? CallPhase.Ended
                ExtraCall(
                    numberRaw = number,
                    startedMs = call.startedMs,
                    durationS =
                        if (call.endedMs !=
                            null
                        ) {
                            ((call.endedMs!! - call.startedMs) / MS_PER_S).coerceAtLeast(0)
                        } else {
                            0
                        },
                    missed = call.direction == CallDirection.In && ended?.reason == "missed",
                    outgoing = call.direction == CallDirection.Out,
                    label = call.peerName?.takeIf { it.isNotBlank() && it != number },
                    callId = call.callId,
                )
            }

        /** `+<digits>` for a `<digits>[:device]@s.whatsapp.net` JID, null for anything else. */
        fun dialNumber(peer: String): String? {
            val user = peer.substringBefore('@', "").substringBefore(':')
            val server = peer.substringAfter('@', "")
            return if (server == "s.whatsapp.net" && user.isNotEmpty() &&
                user.all { it in '0'..'9' }
            ) {
                "+$user"
            } else {
                null
            }
        }

        private const val MS_PER_S = 1000L
    }
}
