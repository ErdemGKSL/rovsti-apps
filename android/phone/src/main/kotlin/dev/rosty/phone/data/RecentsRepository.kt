package dev.rosty.phone.data

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.phone.BuildRowsRequest
import dev.rosty.core.model.phone.CallDirection
import dev.rosty.core.model.phone.CallLogEntry
import dev.rosty.core.model.phone.CallPhase
import dev.rosty.core.model.phone.ExtraCall
import dev.rosty.core.model.phone.GroupRequest
import dev.rosty.core.model.phone.LocaleTime
import dev.rosty.core.model.phone.MergeExtrasRequest
import dev.rosty.core.model.phone.MissedExtrasRequest
import dev.rosty.core.model.phone.RecentsRow
import dev.rosty.core.model.phone.RefreshDelta
import dev.rosty.core.model.phone.RefreshDeltaRequest
import dev.rosty.core.model.phone.RowView
import dev.rosty.core.model.phone.TimeFormat
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.ops.PhoneOps
import dev.rosty.platform.calllog.CallLogRepository
import dev.rosty.platform.identity.CallerIds
import dev.rosty.platform.telephony.RebuildCoalescer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
 *
 * Names are the current contacts' ([ids], F29): each build matches the log's numbers to the contacts, and a change of
 * the contacts ([idsChanged]) rebuilds, so a renamed or newly saved contact shows at once. The log's cached name
 * names only a number no contact owns; [savedNameOf] keeps it for the call details.
 *
 * [callLogGranted] is whether READ_CALL_LOG is held, as the permission controller last read it. The log is queried
 * again each time it changes: a read made before the grant answers an empty list, and granting the permission sends no
 * provider change, so without this the first process after a fresh install kept an empty Recents until it restarted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsRepository(
    private val callLog: CallLogRepository,
    private val ops: PhoneOps,
    private val scope: CoroutineScope,
    private val extras: Flow<List<ExtraCall>> = flowOf(emptyList()),
    private val locale: () -> LocaleTime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lastSeenMs: () -> Long = { 0L },
    private val windowMs: Long = RebuildCoalescer.WINDOW_MS,
    private val ids: CallerIds? = null,
    private val idsChanged: Flow<Long> = flowOf(0L),
    private val callLogGranted: Flow<Boolean> = flowOf(true),
) {
    private val state = MutableStateFlow(RecentsModel())
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    @Volatile private var entries: List<CallLogEntry> = emptyList()

    @Volatile private var named: List<CallLogEntry>? = null

    @Volatile private var savedNames: Map<Long, String> = emptyMap()

    /**
     * The call-log entries of the last build, newest first (what redial, the person sheet and the statistics read),
     * named by the current contacts.
     */
    val callEntries: List<CallLogEntry> get() = named ?: entries

    /** The name the call log cached for entry [logId] at call time; null when it cached none. */
    fun savedNameOf(logId: Long): String? = savedNames[logId]

    @Volatile private var wa: List<ExtraCall> = emptyList()

    /** The WhatsApp calls of the last build's source, newest first (the details sheets read their history from it). */
    val extraCalls: List<ExtraCall> get() = namedWa ?: wa

    @Volatile private var namedWa: List<ExtraCall>? = null
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
            val logs = callLogGranted.distinctUntilChanged().flatMapLatest { callLog.entries() }
            combine(logs, extras, idsChanged) { log, extra, _ -> log to extra }.collect { (log, extra) ->
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

    /**
     * The app came back to the front: rebuilds when the local day or the time format changed since the last build, so
     * the day labels ("Today", "Yesterday") and the times are not those of the day the list was built. The log observer
     * alone never fires for that: a phone left on Recents overnight still said "Today" for yesterday's calls.
     */
    fun refreshIfStale() {
        if (!state.value.ready) return
        if (stampOf(clock(), locale()) != builtStamp) coalescer.now()
    }

    @Volatile private var builtStamp: Pair<Long, TimeFormat>? = null

    private suspend fun build() {
        val raw = entries
        val log = ids?.liveEntries(raw) ?: raw
        named = log
        savedNames = raw.mapNotNull { e -> e.cachedName?.let { e.id to it } }.toMap()
        val extra = ids?.liveExtras(wa) ?: wa
        namedWa = extra
        val grouped = ops.recentsGroup(GroupRequest(log)).orFail() ?: return
        val merged =
            if (extra.isEmpty()) {
                grouped.rows
            } else {
                ops.mergeExtras(MergeExtrasRequest(grouped.rows, extra)).orFail()?.rows ?: return
            }
        val nowMs = clock()
        val localeNow = locale()
        // Stamped before the op: a refreshIfStale racing this build must not start a second one for the same day.
        val stampBefore = builtStamp
        builtStamp = stampOf(nowMs, localeNow)
        val views =
            ops.recentsBuild(BuildRowsRequest(merged, nowMs, localeNow)).orFail()?.views ?: run {
                builtStamp = stampBefore
                return
            }
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
        private const val DAY_MS = 86_400_000L

        /** The local day of [nowMs] and the time format: what the built labels depend on besides the log. */
        internal fun stampOf(
            nowMs: Long,
            locale: LocaleTime,
        ): Pair<Long, TimeFormat> = Math.floorDiv(nowMs + locale.tzOffsetS * MS_PER_S, DAY_MS) to locale.format

        /**
         * The WhatsApp calls the gateway lists, as the extra calls Recents merges in. A peer without a phone number
         * (a `@lid` or a group) cannot be called back from Recents and is left out, as in the Rust history
         * (`rosty_app_core::phone::wa_call::extra_calls`).
         */
        fun extraCalls(calls: List<CallSummary>): List<ExtraCall> =
            calls.mapNotNull { call ->
                val number = dialNumber(call) ?: return@mapNotNull null
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

        /**
         * The number to dial and to look a contact up by for a WhatsApp call: `+<digits>` of its peer's phone JID,
         * or, for a peer the gateway names by a LID, of the number next to it (`peer_alt`): the one the gateway sent,
         * or the one Phone's own identity table filled in before the call got here (`resolvedWaCalls`, WA-R2). Null
         * for a LID nobody could map to a number.
         */
        fun dialNumber(call: CallSummary): String? = WaIds.number(call.peer, call.peerAlt)

        private const val MS_PER_S = 1000L
    }
}
