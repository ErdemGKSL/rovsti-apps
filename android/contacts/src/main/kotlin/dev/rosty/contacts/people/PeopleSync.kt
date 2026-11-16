package dev.rosty.contacts.people

import android.content.SharedPreferences
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.model.people.SyncJobView
import dev.rosty.core.model.people.SyncRunStatus
import dev.rosty.core.model.people.SyncStatus
import dev.rosty.core.model.people.SyncWire
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.ops.PeopleOps
import dev.rosty.people.sync.PartNotifier
import dev.rosty.people.sync.PeoplePictureCodec
import dev.rosty.people.sync.PeopleStore
import dev.rosty.people.sync.PeopleSyncHost
import dev.rosty.people.sync.PeopleSyncRunner
import dev.rosty.people.sync.PeopleSyncTriggers
import dev.rosty.people.sync.RunnerState
import dev.rosty.people.sync.SliceResult
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import dev.rosty.wa.avatars.AvatarCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/**
 * What the "WhatsApp Names and Photos" rows show (people sync v2, section 9; v3, section 5). [stage] values are
 * `SyncWire.STAGE_*`, reasons are `SyncWire` interrupt and fail reasons; done/total are of the current stage.
 */
sealed interface PeopleSyncStatus {
    /** No link: the rows are hidden. */
    data object NotLinked : PeopleSyncStatus

    /** Linked, but the gateway's WhatsApp session is not connected right now. */
    data object Connecting : PeopleSyncStatus

    /**
     * Nothing under way; [lastMs] is when the last run finished (null: never), with [people] on WhatsApp. [changed]:
     * how many people the last run added or changed (null: a run of an older build, or never).
     */
    data class Idle(
        val lastMs: Long?,
        val people: Int = 0,
        val changed: Int? = null,
    ) : PeopleSyncStatus

    /**
     * People sync v3: the gateway prepares the result. [phase] is a `PeopleJobWire.PHASE_*` with its count; while the
     * gateway waits, [waitUntilMs] and [waitSource] (`PeopleJobWire.WAIT_*`) say for what. [watched]: a slice here
     * follows it live; false: the phone comes back for the result later.
     */
    data class Preparing(
        val phase: String,
        val done: Int,
        val total: Int,
        val waitUntilMs: Long?,
        val waitSource: String?,
        val watched: Boolean,
    ) : PeopleSyncStatus

    /** People sync v3: the prepared changes are downloaded, [done] of [total] records. */
    data class Downloading(
        val done: Int,
        val total: Int,
    ) : PeopleSyncStatus

    /** A run is active here; [nextAtMs] when it waits for its pace (null: a request is under way). */
    data class Running(
        val stage: String,
        val done: Int,
        val total: Int,
        val nextAtMs: Long? = null,
    ) : PeopleSyncStatus

    /** WhatsApp's (or the gateway's) limit: the run continues by itself after [untilMs]; nothing may start before. */
    data class RateLimited(
        val untilMs: Long,
        val stage: String,
        val done: Int,
        val total: Int,
    ) : PeopleSyncStatus

    /** Stopped part way and resumable; [atMs] is when it last made progress. */
    data class Interrupted(
        val reason: String,
        val stage: String,
        val done: Int,
        val total: Int,
        val atMs: Long,
    ) : PeopleSyncStatus

    /** Failed and resumable once the cause is gone. */
    data class Failed(
        val reason: String,
        val message: String,
        val atMs: Long,
    ) : PeopleSyncStatus

    /** A resumable run exists ("Continue" shows; "Sync Now" asks before starting over). */
    val resumable: Boolean get() = this is RateLimited || this is Interrupted || this is Failed
}

/** What the settings rows can ask of the sync. */
interface PeopleSyncControl {
    val status: StateFlow<PeopleSyncStatus>

    /** "Sync Now" / "Start Over": a new full run; returns at once. */
    fun syncNow()

    /** "Continue Where It Left Off"; returns at once. */
    fun resume() {}

    /** "Stop": the active run ends stopped and stays resumable. */
    fun stop() {}

    /**
     * People sync v3: the settings row shows a run the gateway prepares while no slice here follows it; one slice is
     * started to follow it live (with the notification, as every run).
     */
    fun attend() {}
}

/**
 * Contacts' people sync (X.1b, people sync v2 S6): follows the link and the gateway's live `profile` events, and runs
 * the shared [PeopleSyncRunner] (the planner in Rust does the pacing, the backoff and the resumption) for the worker.
 * "Sync now", "Continue" and the foreground trigger hand their start to [request], which in the app enqueues the
 * foreground worker (people sync v2 S11), so a run started from the UI outlives the activity; without one (tests) the
 * run happens in [scope]. Every committed part reaches the provider's readers through a [PartNotifier] (at most one
 * notification per [notifyMinMs], the last one guaranteed).
 *
 * [legacy] is the v1 prefs file (`people-sync`): its last sync is shown until the first v2 run finishes, then it is
 * cleared (the file name stays reserved).
 */
@OptIn(FlowPreview::class)
class PeopleSync(
    private val store: PeopleStore,
    ops: PeopleOps,
    private val wa: WaRepository,
    numbers: suspend () -> List<String>,
    private val scope: CoroutineScope,
    private val legacy: SharedPreferences? = null,
    gatewayUrl: suspend () -> String = { "" },
    codec: AvatarCodec = PeoplePictureCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
    random: () -> Int = { Random.nextInt(0, Int.MAX_VALUE) },
    lockFile: File? = null,
    notifyMinMs: Long = NOTIFY_MIN_MS,
    private val onChanged: suspend () -> Unit = {},
    /** Starts a run (`SyncWire.START_*`) in the worker; null runs it in [scope]. */
    private val request: ((String) -> Unit)? = null,
    /** Drops the pending continuation work (a Stop between two slices must not be undone by it). */
    private val dropContinuation: () -> Unit = {},
    /** Where the gateway writes a bundle's pictures (`<cache_dir>/people-bundle`), emptied when a slice begins. */
    bundleDir: File? = null,
) : PeopleSyncControl,
    PeopleSyncHost {
    private val notifier = PartNotifier(scope, notifyMinMs) { onChanged() }
    private val sleep = pause

    /** The engine; the worker and the tests reach it directly. */
    override val runner =
        PeopleSyncRunner(
            store = store,
            ops = ops,
            wa = wa,
            numbers = numbers,
            gatewayUrl = gatewayUrl,
            codec = codec,
            clock = clock,
            pause = sleep,
            random = random,
            onPart = notifier::part,
            lockFile = lockFile,
            bundleDir = bundleDir,
            background = scope,
        )

    private val legacyStatus = MutableStateFlow(readLegacy())
    private var started = false

    override val status: StateFlow<PeopleSyncStatus> =
        combine(runner.status, wa.linkState, legacyStatus) { runner, link, old ->
            words(runner, link, old)
        }.stateIn(scope, SharingStarted.Eagerly, words(RunnerState(), wa.linkState.value, legacyStatus.value))

    /** Follows the link and the gateway's `profile` events; reads the stored status. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { refresh() }
        scope.launch {
            var wasLinked = false
            wa.linkState.collect { link ->
                val linked = link is WaLinkState.Connected
                val gone = link is WaLinkState.None || link is WaLinkState.LoggedOut || link is WaLinkState.Banned
                if (wasLinked && gone && wa.settled.value) {
                    RostyLog.event(TAG, "unlinked", "link" to link.javaClass.simpleName)
                    runner.unlinked()
                }
                wasLinked = linked
            }
        }
        scope.launch {
            wa.events
                .filterIsInstance<GatewayEvent.Profile>()
                .onEach { onProfile(it.profile()) }
                .debounce(LIVE_DEBOUNCE_MS)
                .collect { livePictures() }
        }
    }

    /** "Sync now": a new full run that checks everyone again. */
    override fun syncNow() = start(SyncWire.START_RESTART)

    /** "Continue where it left off". */
    override fun resume() = start(SyncWire.START_RESUME)

    /**
     * "Stop" (and the notification's Stop): the active run ends `interrupted(cancelled)` and stays resumable, and the
     * gateway's job is cancelled. Between two slices the continuation is dropped as well.
     */
    override fun stop() {
        if (!runner.status.value.active) dropContinuation()
        runner.cancel()
    }

    /** One slice that follows the gateway's job live (people sync v3, section 5). */
    override fun attend() = start(SyncWire.START_AUTO)

    override fun now(): Long = clock()

    override suspend fun pause(ms: Long) = sleep(ms)

    override suspend fun runSlice(
        start: String,
        sliceMs: Long?,
    ): SliceResult = run(start, sliceMs)

    private fun start(start: String) {
        val worker = request
        if (worker != null) worker(start) else scope.launch { run(start, null) }
    }

    /**
     * The foreground trigger (people sync v3, section 6): syncs when the last run is [PERIOD_MS] old and the last attempt
     * half an hour, unless the user stopped the stored run or it failed ([PeopleSyncTriggers]). A resumable run is left
     * to its continuation.
     */
    fun syncIfStale() {
        scope.launch {
            val stored = runner.refresh()
            val last = stored?.last?.atMs ?: legacyStatus.value.lastMs.takeIf { it > 0 }
            val go =
                PeopleSyncTriggers.shouldStart(
                    PeopleSyncTriggers.Kind.FOREGROUND,
                    clock(),
                    lastFinishedMs = last,
                    lastAttemptMs = stored?.attemptMs,
                    run = stored?.run,
                    notBeforeMs = stored?.notBeforeMs,
                )
            if (go) {
                val worker = request
                if (worker != null) worker(SyncWire.START_AUTO) else run(SyncWire.START_AUTO, null)
            }
        }
    }

    /** One slice for the worker: resume or an incremental run, bounded by [sliceMs]. */
    suspend fun runSlice(sliceMs: Long = PeopleSyncRunner.WORKER_SLICE_MS): SliceResult =
        run(SyncWire.START_AUTO, sliceMs)

    /** One slice as [start] says (`SyncWire.START_*`). */
    suspend fun run(
        start: String,
        sliceMs: Long?,
    ): SliceResult {
        val result = runner.run(start, sliceMs)
        if (runner.status.value.stored
                ?.last != null
        ) {
            clearLegacy()
        }
        return result
    }

    /** Re-reads the stored status. */
    suspend fun refresh() {
        runner.refresh()?.let { if (it.last != null) clearLegacy() }
    }

    // ---- live events

    private suspend fun onProfile(profile: WaProfile) {
        if (wa.linkState.value !is WaLinkState.Connected || WaIds.isGroup(profile.jid)) return
        val answer = store.upsert(listOf(PeopleSyncRunner.profileUpdate(profile))) ?: return
        if (answer.changed > 0 || answer.dropFiles.isNotEmpty()) notifier.part()
    }

    /** The debounced picture pass after live events; a running sync's picture stage covers it instead. */
    private suspend fun livePictures() {
        if (wa.linkState.value !is WaLinkState.Connected) return
        runner.run(SyncWire.START_LIVE_PICTURES, PeopleSyncRunner.LIVE_SLICE_MS)
    }

    // ---- the row

    private fun words(
        runner: RunnerState,
        link: WaLinkState,
        old: Legacy,
    ): PeopleSyncStatus {
        if (link !is WaLinkState.Connected) return PeopleSyncStatus.NotLinked
        val stored: SyncStatus? = runner.stored
        val run = stored?.run
        if (runner.active && !runner.live) {
            val progress = runner.progress ?: run?.progress
            val stage = runner.stage ?: run?.stage
            if (stage == SyncWire.STAGE_DOWNLOAD) {
                return PeopleSyncStatus.Downloading(progress?.done ?: 0, progress?.total ?: 0)
            }
            if (stage == SyncWire.STAGE_SERVER) return preparing(runner.job ?: run?.job, watched = true)
            return PeopleSyncStatus.Running(
                stage = runner.stage ?: run?.stage ?: SyncWire.STAGE_LOOKUP,
                done = progress?.done ?: 0,
                total = progress?.total ?: 0,
                nextAtMs = runner.sleepingUntilMs,
            )
        }
        if (run != null && waitsForGateway(run)) return preparing(run.job, watched = false)
        if (run != null) {
            val stage = run.stage
            val (done, total) = run.progress.done to run.progress.total
            val until = stored.notBeforeMs ?: run.phase.untilMs.takeIf { run.phase.kind == SyncWire.WAITING }
            return when {
                until != null -> {
                    PeopleSyncStatus.RateLimited(until, stage, done, total)
                }

                run.phase.kind == SyncWire.FAILED -> {
                    PeopleSyncStatus.Failed(
                        run.phase.reason ?: SyncWire.OTHER,
                        run.phase.message.orEmpty(),
                        run.heartbeatMs,
                    )
                }

                else -> {
                    // `running` without an active slice here: another process's slice, or one that died.
                    val reason = run.phase.reason ?: SyncWire.PROCESS_DEATH
                    PeopleSyncStatus.Interrupted(reason, stage, done, total, run.heartbeatMs)
                }
            }
        }
        if (link.status.state == InstanceState.Disconnected) return PeopleSyncStatus.Connecting
        val last = stored?.last
        return when {
            last != null -> PeopleSyncStatus.Idle(last.atMs, last.people, last.changed)
            old.lastMs > 0 -> PeopleSyncStatus.Idle(old.lastMs, old.people)
            else -> PeopleSyncStatus.Idle(null)
        }
    }

    /** A stored job-mode run whose slice ended while the gateway still works (or another process follows it). */
    private fun waitsForGateway(run: SyncRunStatus): Boolean =
        run.stage == SyncWire.STAGE_SERVER &&
            (
                run.phase.kind == SyncWire.RUNNING ||
                    (run.phase.kind == SyncWire.INTERRUPTED && run.phase.reason == SyncWire.SERVER_WORKING)
            )

    private fun preparing(
        job: SyncJobView?,
        watched: Boolean,
    ): PeopleSyncStatus.Preparing {
        val waiting = job?.state == PeopleJobWire.WAITING
        return PeopleSyncStatus.Preparing(
            phase = job?.phase.orEmpty(),
            done = job?.done ?: 0,
            total = job?.total ?: 0,
            waitUntilMs = job?.waitUntilMs?.takeIf { waiting },
            waitSource = job?.waitSource?.takeIf { waiting },
            watched = watched,
        )
    }

    /** The v1 sync's last run, as its prefs remember it. */
    private data class Legacy(
        val lastMs: Long = 0,
        val people: Int = 0,
    )

    private fun readLegacy(): Legacy {
        val prefs = legacy ?: return Legacy()
        if (!prefs.getBoolean(LEGACY_OK, false)) return Legacy()
        return Legacy(prefs.getLong(LEGACY_LAST, 0), prefs.getInt(LEGACY_PEOPLE, 0))
    }

    private fun clearLegacy() {
        val prefs = legacy ?: return
        if (prefs.all.isEmpty()) return
        prefs.edit().clear().apply()
        legacyStatus.value = Legacy()
    }

    companion object {
        /** The log tag of everything the sync writes. */
        const val TAG = PeopleSyncRunner.TAG

        /** How often the worker runs (people sync v3: twelve hours). */
        const val PERIOD_MS = PeopleSyncTriggers.PERIOD_MS

        /** Numbers asked about per run (recent callers first). */
        const val MAX_NUMBERS = PeopleSyncRunner.MAX_NUMBERS

        /** Provider notifications to Phone, at most one per this (D9). */
        const val NOTIFY_MIN_MS = 3_000L

        private const val LIVE_DEBOUNCE_MS = 2_000L

        // The v1 prefs (`PeopleSyncLog`), read once.
        private const val LEGACY_LAST = "last_ms"
        private const val LEGACY_OK = "ok"
        private const val LEGACY_PEOPLE = "people"
    }
}
