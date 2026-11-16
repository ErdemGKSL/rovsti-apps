package dev.rosty.people.sync

import android.util.Base64
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.log.LogLevel
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.people.PeopleBundleRecord
import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.model.people.PeopleSyncBundleAnswer
import dev.rosty.core.model.people.PersonUpdate
import dev.rosty.core.model.people.SyncBeginRequest
import dev.rosty.core.model.people.SyncJobPicture
import dev.rosty.core.model.people.SyncJobRecord
import dev.rosty.core.model.people.SyncJobView
import dev.rosty.core.model.people.SyncNextRequest
import dev.rosty.core.model.people.SyncPictureResult
import dev.rosty.core.model.people.SyncProgress
import dev.rosty.core.model.people.SyncReport
import dev.rosty.core.model.people.SyncReportRequest
import dev.rosty.core.model.people.SyncStatus
import dev.rosty.core.model.people.SyncStatusRequest
import dev.rosty.core.model.people.SyncStep
import dev.rosty.core.model.people.SyncStopRequest
import dev.rosty.core.model.people.SyncStopped
import dev.rosty.core.model.people.SyncWire
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.PictureAnswer
import dev.rosty.core.model.wa.PictureBody
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.ops.PeopleOps
import dev.rosty.core.ops.WaGateway
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import dev.rosty.wa.avatars.AvatarCodec
import dev.rosty.wa.avatars.rateLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.random.Random

/** How one slice ended, for the worker, the settings row and the tests. */
sealed interface SliceResult {
    /** The run finished (or a live pass had nothing left). */
    data object Done : SliceResult

    /** The stored link was read and there is none: nothing asked, nothing stored. */
    data object NotLinked : SliceResult

    /** The stored link was not read in time ([PeopleSyncRunner.LINK_WAIT_MS], a cold process): try again soon. */
    data object LinkNotReady : SliceResult

    /** Another slice runs (in this process, or the file lock is held): its status is the answer. */
    data object AlreadyRunning : SliceResult

    /** The planner would not start: WhatsApp's penalty runs until [untilMs], or (null) there is nothing to resume. */
    data class Refused(
        val untilMs: Long?,
    ) : SliceResult

    /** A rate limit: the run continues by itself after [untilMs]. */
    data class Waiting(
        val untilMs: Long,
    ) : SliceResult

    /**
     * Resumable; [reason] is a `SyncWire` interrupt reason. [etaMs]: with `server_working`, how long the gateway expects
     * its job still to take (people sync v3), null when it did not say.
     */
    data class Interrupted(
        val reason: String,
        val etaMs: Long? = null,
    ) : SliceResult

    /** Resumable once the cause is gone; [reason] is a `SyncWire` fail reason. */
    data class Failed(
        val reason: String,
        val message: String,
    ) : SliceResult
}

/** What the runner is doing now, next to the stored status. */
data class RunnerState(
    /** A slice of the run is active in this process. */
    val active: Boolean = false,
    /** The active slice is a live picture pass (no run). */
    val live: Boolean = false,
    /** `SyncWire.STAGE_*` of the active slice. */
    val stage: String? = null,
    val progress: SyncProgress? = null,
    /** The slice waits (pace or rate limit) until then; null while a request is under way. */
    val sleepingUntilMs: Long? = null,
    /** The wait until [sleepingUntilMs] is a short rate-limit pause (WhatsApp's limit), not the normal pace. */
    val limited: Boolean = false,
    /** The planner's status, re-read after every slice and every committed part. */
    val stored: SyncStatus? = null,
    /** People sync v3: the gateway job the active slice follows (stage `server` / `download`); null otherwise. */
    val job: SyncJobView? = null,
)

/**
 * Runs the people sync planner (`rosty_app_core::people_sync`, through the `people.sync_*` ops; plans
 * `_bmad-output/plans/people-sync-v2.md` and `_bmad-output/kotlin-ui/plans/people-sync-v3.md`) against the gateway, one
 * slice at a time. Shared by Contacts and Phone.
 *
 * The loop: `people.sync_next` says what to do; a step calls the gateway and hands the answer to `people.sync_report`,
 * which commits it (book first, checkpoint second); a sleep waits ([pause]); a stop ends the slice. Every slice opts in
 * to the gateway's people job (v3, `jobs = true`): the planner starts or joins it (`job_start`), follows it with
 * long-polls (`job_watch`), downloads its bundle (`job_fetch`, each picture downscaled and stored here) and closes it
 * (`job_done`). A gateway without the job routes answers `job_unsupported` and the planner goes on with the v2 steps
 * (`lookup`, `profiles`, `picture`). After every committed part with changes the dropped files are deleted and [onPart] is called (the
 * app coalesces it, see [PartNotifier]). Timing, batching, backoff and resumption are all the planner's: this class
 * only performs.
 *
 * One slice at a time: a second [run] while one is active answers [SliceResult.AlreadyRunning] at once, and a file
 * lock ([lockFile]) guards against a second process. A cancelled slice still ends the slice, so the stored run is never
 * left `running`: stopped by [cancel] (the Stop button and the notification's Stop) it ends `interrupted(cancelled)`;
 * cancelled from outside (the system stopped the worker, people sync v2 S11) it ends `interrupted(slice_budget)`,
 * which the worker continues by itself. Only the user's Stop also cancels the gateway's job (from [background], so the
 * pending long-poll returns at once); a stop by the system leaves the job working on the gateway.
 */
class PeopleSyncRunner(
    private val store: PeopleStore,
    private val ops: PeopleOps,
    private val wa: WaRepository,
    private val numbers: suspend () -> List<String>,
    /** The stored link's gateway URL (part of the checkpoint's link identity); empty when unknown. */
    private val gatewayUrl: suspend () -> String = { "" },
    private val codec: AvatarCodec = PeoplePictureCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val random: () -> Int = { Random.nextInt(0, Int.MAX_VALUE) },
    private val onPart: () -> Unit = {},
    /** Held for the slice; null: no cross-process lock (tests). */
    private val lockFile: File? = null,
    /**
     * Where the gateway op writes a bundle's picture files (`<cache_dir>/people-bundle`); emptied when a slice begins.
     * Null: not emptied (tests).
     */
    private val bundleDir: File? = null,
    /** Runs the gateway cancel of the user's Stop, outside the slice that is being cancelled. */
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val running = Mutex()
    private val state = MutableStateFlow(RunnerState())

    @Volatile private var job: Job? = null

    /** The gateway job of ours the active slice follows: what the user's Stop cancels on the gateway. */
    @Volatile private var following: Following? = null

    private val stopCount = MutableStateFlow(0L)

    /** Counts [cancel] calls: compared before and after a slice or a wait, it tells a Stop from the system's stop. */
    val stops: StateFlow<Long> = stopCount.asStateFlow()

    /** What the runner is doing, and the planner's last status. */
    val status: StateFlow<RunnerState> = state.asStateFlow()

    /** Re-reads the planner's status (the settings row, after a process start). */
    suspend fun refresh(): SyncStatus? {
        val stored = (ops.syncStatus(SyncStatusRequest(dir = store.dataDir, nowMs = clock())) as? Outcome.Ok)?.value
        if (stored != null) state.update { it.copy(stored = stored) }
        return stored
    }

    /**
     * The user's Stop (the "Stop" button, the notification's Stop): the active slice ends `interrupted(cancelled)` and
     * the gateway's job is cancelled too (people sync v3 D9), from [background] so the slice's pending long-poll returns
     * at once. With no slice active, a stored job-mode run (the gateway works while the phone waits for it) is cancelled
     * on the gateway and interrupted here.
     */
    fun cancel() {
        val active = job
        val target = following
        stop()
        if (target != null) {
            background.launch { cancelOnGateway(target.gateway, target.jobId, "stop") }
        } else if (active == null) {
            background.launch { stopStoredJob() }
        }
    }

    /** Ends the active slice without touching the gateway's job (counted as a Stop, see [stops]). */
    private fun stop() {
        stopCount.update { it + 1 }
        job?.cancel(CancellationException("people sync cancelled"))
    }

    /** WhatsApp was unlinked: a stored run becomes `interrupted(wa_disconnected)` (dropped when another link begins). */
    suspend fun unlinked() {
        stop()
        interruptStored(SyncWire.WA_DISCONNECTED)
    }

    /** A Stop between two slices of a job-mode run: the gateway's job of ours is cancelled, the run interrupted. */
    private suspend fun stopStoredJob() {
        if (running.isLocked) return
        val run = refresh()?.run ?: return
        if (run.stage != SyncWire.STAGE_SERVER && run.stage != SyncWire.STAGE_DOWNLOAD) return
        if (run.phase.kind == SyncWire.FAILED) return
        run.job?.takeIf { it.mine && it.state in WORKING }?.let { view ->
            wa.gateway()?.let { cancelOnGateway(it, view.jobId, "stop_between_slices") }
        }
        interruptStored(SyncWire.CANCELLED)
    }

    private suspend fun cancelOnGateway(
        gateway: WaGateway,
        jobId: String,
        why: String,
    ) {
        val out = gateway.peopleSyncCancel(jobId)
        RostyLog.event(TAG, "job_cancel", "why" to why, "ok" to (out is Outcome.Ok))
    }

    /**
     * Interrupts the stored run with [reason] (`SyncWire` interrupt reason) while no slice holds it: the worker's Stop
     * during a rate limit's wait between two slices.
     */
    suspend fun interruptStored(reason: String) {
        val req = SyncStopRequest(dir = store.dataDir, nowMs = clock(), reason = reason)
        (ops.syncStop(req) as? Outcome.Ok)?.value?.let { stored -> state.update { it.copy(stored = stored) } }
    }

    /**
     * One slice: [start] is a `SyncWire.START_*`; [sliceMs] bounds it (the worker's budget), null in the foreground.
     */
    suspend fun run(
        start: String,
        sliceMs: Long?,
    ): SliceResult {
        if (!running.tryLock()) {
            RostyLog.event(TAG, "slice_skipped", "reason" to "already_running", "start" to start)
            return SliceResult.AlreadyRunning
        }
        try {
            job = currentCoroutineContext()[Job]
            val stopsAtStart = stopCount.value
            val lock = lockFile?.let(::tryLock)
            if (lockFile != null && lock == null) {
                RostyLog.event(TAG, "slice_skipped", "reason" to "file_lock", "start" to start)
                return SliceResult.AlreadyRunning
            }
            try {
                return slice(start, sliceMs, stopsAtStart)
            } finally {
                lock?.let(::release)
            }
        } finally {
            job = null
            state.update { it.copy(active = false, live = false, sleepingUntilMs = null, limited = false, job = null) }
            running.unlock()
        }
    }

    private suspend fun slice(
        start: String,
        sliceMs: Long?,
        stopsAtStart: Long,
    ): SliceResult {
        val waitStart = clock()
        val settled = withTimeoutOrNull(LINK_WAIT_MS) { wa.settled.first { it } } != null
        RostyLog.event(TAG, "link_wait", "settled" to settled, "ms" to clock() - waitStart)
        if (!settled) return SliceResult.LinkNotReady
        val link = wa.linkState.value
        if (link !is WaLinkState.Connected) {
            RostyLog.event(TAG, "slice_skipped", "reason" to "not_linked", "link" to link.javaClass.simpleName)
            return SliceResult.NotLinked
        }
        if (link.status.state == InstanceState.Disconnected) {
            RostyLog.event(TAG, "slice_skipped", "reason" to SyncWire.WA_DISCONNECTED)
            return SliceResult.Interrupted(SyncWire.WA_DISCONNECTED)
        }
        val gateway =
            wa.gateway() ?: run {
                RostyLog.event(LogLevel.Warn, TAG, "slice_skipped", "reason" to "no_gateway")
                return SliceResult.Interrupted(SyncWire.NETWORK)
            }
        val live = start == SyncWire.START_LIVE_PICTURES
        if (!live) clearBundleDir()
        val targets = if (live) emptyList() else numbers()
        val begin =
            ops.syncBegin(
                SyncBeginRequest(
                    dir = store.dataDir,
                    nowMs = clock(),
                    instanceId = link.status.instanceId,
                    gatewayUrl = gatewayUrl(),
                    numbers = targets,
                    start = start,
                    sliceMs = sliceMs,
                    jobs = true,
                ),
            )
        val begun =
            when (begin) {
                is Outcome.Ok -> {
                    begin.value
                }

                is Outcome.Err -> {
                    coreError("sync_begin", begin.error)
                    return SliceResult.Failed(SyncWire.CORE, begin.error.message.orEmpty())
                }
            }
        state.update { it.copy(stored = begun.status) }
        val handle =
            begun.handle ?: run {
                val refused = begun.refused
                RostyLog.event(TAG, "slice_refused", "kind" to refused?.kind, "until_ms" to refused?.untilMs)
                return SliceResult.Refused(refused?.untilMs)
            }
        state.update {
            it.copy(
                active = true,
                live = live,
                stage = begun.status.run?.stage,
                progress = begun.status.run?.progress,
                job = begun.status.run?.job,
            )
        }
        follow(gateway, begun.status.run?.job)
        var parts = 0
        var stopped: SyncStopped? = null
        var stopReason = SyncWire.CANCELLED
        try {
            while (stopped == null) {
                val step =
                    when (val next = ops.syncNext(SyncNextRequest(handle, clock(), rand()))) {
                        is Outcome.Ok -> {
                            next.value
                        }

                        is Outcome.Err -> {
                            coreError("sync_next", next.error)
                            return SliceResult.Failed(SyncWire.CORE, next.error.message.orEmpty())
                        }
                    }
                when (step.kind) {
                    SyncWire.STEP_STOP -> {
                        stopped = step.stopped ?: SyncStopped(SyncWire.DONE)
                    }

                    SyncWire.STEP_SLEEP -> {
                        val ms = step.ms ?: 0
                        RostyLog.event(TAG, "pace", "sleep_ms" to ms, "why" to step.why)
                        state.update { it.copy(sleepingUntilMs = clock() + ms, limited = step.why == WHY_RATE_LIMIT) }
                        pause(ms)
                        state.update { it.copy(sleepingUntilMs = null, limited = false) }
                    }

                    else -> {
                        val report = perform(gateway, step)
                        val committed =
                            when (val out = ops.syncReport(SyncReportRequest(handle, clock(), rand(), report))) {
                                is Outcome.Ok -> {
                                    out.value
                                }

                                is Outcome.Err -> {
                                    coreError("sync_report", out.error)
                                    return SliceResult.Failed(SyncWire.CORE, out.error.message.orEmpty())
                                }
                            }
                        state.update {
                            it.copy(
                                stage = committed.stage ?: it.stage,
                                progress = committed.progress,
                                job = committed.job,
                            )
                        }
                        follow(gateway, committed.job)
                        if (committed.changed > 0 || committed.dropFiles.isNotEmpty()) {
                            store.delete(committed.dropFiles)
                            parts++
                            RostyLog.event(
                                TAG,
                                "commit",
                                "changed" to committed.changed,
                                "dropped" to committed.dropFiles.size,
                            )
                            onPart()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (stopCount.value != stopsAtStart) {
                    ops.syncReport(SyncReportRequest(handle, clock(), rand(), SyncReport.cancelled))
                } else {
                    // Not our Stop: the system stopped the worker (its time budget, a constraint). The step under way
                    // is asked again on resume; the worker schedules the continuation.
                    stopReason = SyncWire.SLICE_BUDGET
                }
                end(parts, SyncStopped(SyncWire.INTERRUPTED, reason = stopReason))
            }
            throw e
        } finally {
            following = null
            withContext(NonCancellable) {
                val req = SyncStopRequest(handle = handle, nowMs = clock(), reason = stopReason)
                (ops.syncStop(req) as? Outcome.Ok)?.value?.let { stored -> state.update { it.copy(stored = stored) } }
            }
        }
        val end = stopped ?: SyncStopped(SyncWire.DONE)
        end(parts, end)
        val result = end.result()
        return if (result is SliceResult.Interrupted && result.reason == SyncWire.SERVER_WORKING) {
            result.copy(etaMs = state.value.job?.etaMs)
        } else {
            result
        }
    }

    /** Remembers the job the slice follows, so a Stop can cancel it: only our own, while it works or holds a result. */
    private fun follow(
        gateway: WaGateway,
        view: SyncJobView?,
    ) {
        following = view?.takeIf { it.mine && it.state in WORKING }?.let { Following(gateway, it.jobId) }
    }

    /** Picture files a slice that died left behind (a finished or cut part deletes its own). */
    private suspend fun clearBundleDir() {
        val dir = bundleDir ?: return
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun end(
        parts: Int,
        stopped: SyncStopped,
    ) {
        val progress = state.value.progress
        RostyLog.event(
            if (stopped.outcome == SyncWire.FAILED) LogLevel.Warn else LogLevel.Info,
            TAG,
            "slice_end",
            "outcome" to stopped.outcome,
            "reason" to (stopped.reason ?: stopped.source),
            "stage" to state.value.stage,
            "job_state" to state.value.job?.state,
            "done" to progress?.done,
            "total" to progress?.total,
            "parts" to parts,
            "next_at" to stopped.untilMs,
        )
    }

    /** Performs one gateway step and words the answer as a report. */
    private suspend fun perform(
        gateway: WaGateway,
        step: SyncStep,
    ): SyncReport {
        val began = clock()
        return try {
            when (step.kind) {
                SyncWire.STEP_LOOKUP -> {
                    lookup(gateway, step.jids.orEmpty())
                }

                SyncWire.STEP_PROFILES -> {
                    profiles(gateway, step.jids.orEmpty())
                }

                SyncWire.STEP_PLAN_PICTURES -> {
                    SyncReport.files(store.files(), PeopleStore.MAX_BYTES)
                }

                SyncWire.STEP_PICTURE -> {
                    picture(gateway, step.jid.orEmpty(), step.knownId)
                }

                SyncWire.STEP_JOB_START -> {
                    jobStart(gateway, step)
                }

                SyncWire.STEP_JOB_WATCH -> {
                    jobWatch(gateway, step)
                }

                SyncWire.STEP_JOB_FETCH -> {
                    jobFetch(gateway, step)
                }

                SyncWire.STEP_JOB_DONE -> {
                    // Its answer does not matter: the gateway drops a finished job by itself after a day.
                    cancelOnGateway(gateway, step.jobId.orEmpty(), "done")
                    SyncReport.jobClosed
                }

                else -> {
                    SyncReport.error(SyncWire.OTHER, "unknown step ${step.kind}")
                }
            }
        } catch (e: IOException) {
            SyncReport.error(SyncWire.NETWORK, e.message ?: "I/O error")
        }.also { report ->
            if (report.kind == "error" || report.kind == "rate_limited") {
                RostyLog.event(
                    LogLevel.Warn,
                    TAG,
                    report.kind,
                    "step" to step.kind,
                    "source" to report.source,
                    "retry_after_ms" to report.retryAfterMs,
                    "kind" to report.error,
                    "message" to report.message,
                    "ms" to clock() - began,
                )
            }
        }
    }

    private suspend fun lookup(
        gateway: WaGateway,
        jids: List<String>,
    ): SyncReport =
        when (val answer = gateway.lookupUsers(jids)) {
            is Outcome.Ok -> {
                val found =
                    answer.value
                        .filter { it.onWhatsapp }
                        .map { PersonUpdate(jid = it.jid, onWhatsapp = true, verifiedName = it.verifiedName) }
                SyncReport.lookup(jids, found)
            }

            is Outcome.Err -> {
                failure(answer.error)
            }
        }

    private suspend fun profiles(
        gateway: WaGateway,
        jids: List<String>,
    ): SyncReport =
        when (val answer = gateway.profiles(jids)) {
            is Outcome.Ok -> {
                val (real, empty) = answer.value.partition(::isAnswered)
                SyncReport.profiles(jids, real.map(::profileUpdate), empty.map { it.jid })
            }

            is Outcome.Err -> {
                failure(answer.error)
            }
        }

    private suspend fun picture(
        gateway: WaGateway,
        jid: String,
        knownId: String?,
    ): SyncReport =
        // The full picture (S12): WhatsApp's preview is ~96 px and looks blurry in a header avatar. It is stored
        // downscaled to PICTURE_PX; the request counts against the planner's picture pace like any other.
        when (val answer = gateway.picture(PictureBody(jid, full = true, knownId = knownId))) {
            is Outcome.Ok -> {
                SyncReport.picture(jid, pictureResult(jid, answer.value))
            }

            is Outcome.Err -> {
                failure(answer.error)
            }
        }

    private suspend fun jobStart(
        gateway: WaGateway,
        step: SyncStep,
    ): SyncReport =
        when (val answer = gateway.peopleSyncStart(step.mode ?: PeopleJobWire.MODE_AUTO, step.entries.orEmpty())) {
            is Outcome.Ok -> {
                val status = answer.value.status
                when {
                    !answer.value.supported -> {
                        RostyLog.event(TAG, "job_unsupported", "fallback" to "legacy")
                        SyncReport.jobUnsupported
                    }

                    status == null -> {
                        SyncReport.error(SyncWire.GATEWAY, "the gateway's start answer has no status")
                    }

                    else -> {
                        RostyLog.event(
                            TAG,
                            "job_started",
                            "state" to status.state,
                            "mine" to status.mine,
                            "numbers" to status.numbers,
                        )
                        SyncReport.jobStarted(status)
                    }
                }
            }

            is Outcome.Err -> {
                failure(answer.error)
            }
        }

    private suspend fun jobWatch(
        gateway: WaGateway,
        step: SyncStep,
    ): SyncReport =
        when (val answer = gateway.peopleSyncStatus(step.jobId.orEmpty(), step.rev, step.waitMs)) {
            is Outcome.Ok -> {
                val status = answer.value.status
                if (answer.value.gone || status == null) SyncReport.jobGone else SyncReport.job(status)
            }

            is Outcome.Err -> {
                failure(answer.error)
            }
        }

    private suspend fun jobFetch(
        gateway: WaGateway,
        step: SyncStep,
    ): SyncReport {
        val jobId = step.jobId.orEmpty()
        val part: PeopleSyncBundleAnswer =
            when (val answer = gateway.peopleSyncBundle(jobId, step.after ?: 0, step.maxBytes)) {
                is Outcome.Ok -> answer.value
                is Outcome.Err -> return failure(answer.error)
            }
        if (part.gone) {
            deleteTemp(part.records)
            return SyncReport.jobGone
        }
        val records = part.records.map { SyncJobRecord.of(it, storeBundlePicture(it)) }
        RostyLog.event(
            TAG,
            "bundle_part",
            "records" to records.size,
            "pictures" to records.count { it.picture?.kind == SyncJobPicture.STORED },
            "failed" to records.count { it.picture?.kind == SyncJobPicture.FAILED },
            "more" to part.more,
            "bytes" to part.bytes,
        )
        return SyncReport.bundle(jobId, records, part.lastSeq, part.more, part.bytes)
    }

    /**
     * A record's picture bytes, downscaled and stored exactly as a v2 picture ([saveBytes]); the temp file is deleted
     * either way. Null when the record carried no bytes.
     */
    private suspend fun storeBundlePicture(record: PeopleBundleRecord): SyncJobPicture? {
        val path = record.picturePath ?: return null
        val file = File(path)
        val id = record.pictureId
        val jid = record.jid
        return try {
            val bytes =
                withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.readBytes() }
                    ?.let { codec.downscale(it, PICTURE_PX) }
            val name = if (bytes != null && id != null && jid != null) store.store(jid, id, bytes) else null
            if (name != null) SyncJobPicture.stored(name) else SyncJobPicture.failed
        } catch (_: IOException) {
            SyncJobPicture.failed
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun deleteTemp(records: List<PeopleBundleRecord>) {
        withContext(Dispatchers.IO) { records.mapNotNull { it.picturePath }.forEach { File(it).delete() } }
    }

    private suspend fun pictureResult(
        jid: String,
        answer: PictureAnswer,
    ): SyncPictureResult =
        when (answer.kind) {
            "image" -> {
                val id = answer.id
                val name = id?.let { saveBytes(jid, it, answer) }
                if (id != null && name != null) {
                    SyncPictureResult(SyncPictureResult.STORED, pictureId = id, file = name)
                } else {
                    SyncPictureResult(SyncPictureResult.FAILED)
                }
            }

            "unchanged" -> {
                SyncPictureResult(SyncPictureResult.UNCHANGED)
            }

            "none" -> {
                SyncPictureResult(SyncPictureResult.NONE)
            }

            else -> {
                SyncPictureResult(SyncPictureResult.FAILED)
            }
        }

    private suspend fun saveBytes(
        jid: String,
        id: String,
        answer: PictureAnswer,
    ): String? {
        val bytes = readBytes(answer)?.let { codec.downscale(it, PICTURE_PX) } ?: return null
        return store.store(jid, id, bytes)
    }

    private fun readBytes(answer: PictureAnswer): ByteArray? =
        try {
            when {
                answer.dataB64 != null -> {
                    Base64.decode(answer.dataB64, Base64.DEFAULT)
                }

                answer.path != null -> {
                    val file = File(answer.path!!)
                    try {
                        file.readBytes()
                    } finally {
                        file.delete()
                    }
                }

                else -> {
                    null
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun rand(): Int = random().let { if (it < 0) -(it + 1) else it }

    private fun coreError(
        op: String,
        error: RustError,
    ) {
        RostyLog.event(LogLevel.Error, TAG, "error", "kind" to "core", "op" to op, "message" to error.message)
    }

    private fun tryLock(file: File): Held? =
        try {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            val lock =
                try {
                    raf.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock == null) {
                raf.close()
                null
            } else {
                Held(raf, lock)
            }
        } catch (_: IOException) {
            null
        }

    private fun release(held: Held) {
        try {
            held.lock.release()
            held.file.close()
        } catch (_: IOException) {
            // Closing the file releases the lock anyway.
        }
    }

    private class Held(
        val file: RandomAccessFile,
        val lock: FileLock,
    )

    private class Following(
        val gateway: WaGateway,
        val jobId: String,
    )

    companion object {
        /** The log tag of everything the sync writes. */
        const val TAG = "sync"

        /** How long a slice waits for the stored link to be read ([WaRepository.settled]). */
        const val LINK_WAIT_MS = 20_000L

        /**
         * The worker's slice budget (`people_sync::WORKER_SLICE_MS`): WorkManager stops a background worker at 10 min.
         * A foreground worker (S11) runs without one; this is its fallback when it could not become foreground.
         */
        const val WORKER_SLICE_MS = 8L * 60_000

        /** Between background slices when nothing is throttled (`people_sync::CONTINUE_DELAY_MS`). */
        const val CONTINUE_DELAY_MS = 60_000L

        /** Budget of a live picture pass (`people_sync::LIVE_SLICE_MS`). */
        const val LIVE_SLICE_MS = 30_000L

        /** Numbers asked about per run (`people_sync::MAX_NUMBERS`). */
        const val MAX_NUMBERS = 2_000

        /** The folder under the app's cache dir where `wa.people_sync_bundle` writes a part's pictures. */
        const val BUNDLE_DIR = "people-bundle"

        /** `people_sync::Why::RateLimit` on the wire: a sleep step that waits out a short limit inline. */
        const val WHY_RATE_LIMIT = "rate_limit"

        /**
         * The short side a stored picture is downscaled to (S12): the largest avatar that shows it is 112 dp (the call
         * screen), 448 px at 4x, so 512 px is sharp on every screen and about 40 KB as a JPEG.
         */
        const val PICTURE_PX = 512
        private const val NOT_CONNECTED = "not_connected"

        /** Job states in which the gateway still works or holds the result: a Stop cancels them. */
        private val WORKING = setOf(PeopleJobWire.RUNNING, PeopleJobWire.WAITING, PeopleJobWire.READY)

        /** WhatsApp really answered for this profile (an old gateway answers a throttled one with `fetched_ms: 0`). */
        fun isAnswered(profile: WaProfile): Boolean = profile.fetchedMs > 0 || profile.pictureId != null

        /** A profile as an update; only a real answer says "no picture". */
        fun profileUpdate(profile: WaProfile): PersonUpdate {
            val hasAnswer = isAnswered(profile)
            return PersonUpdate(
                jid = profile.jid,
                pushName = profile.pushName,
                verifiedName = profile.verifiedName,
                pictureId = profile.pictureId,
                pictureRemoved = if (profile.pictureId == null && hasAnswer) true else null,
            )
        }

        /** A gateway error as the planner's report. */
        fun failure(error: RustError): SyncReport {
            error.rateLimit()?.let { return SyncReport.rateLimited(it.retryAfterMs, it.source ?: SyncWire.GATEWAY) }
            val code = ((error.detail as? JsonObject)?.get("code") as? JsonPrimitive)?.content
            val message = error.message.orEmpty()
            return when {
                code == NOT_CONNECTED -> SyncReport.error(SyncWire.NOT_CONNECTED, message)
                error is RustError.Network -> SyncReport.error(SyncWire.NETWORK, message)
                error is RustError.Auth -> SyncReport.error(SyncWire.AUTH, message)
                else -> SyncReport.error(SyncWire.GATEWAY, message)
            }
        }

        /** The stop as a slice result. */
        fun SyncStopped.result(): SliceResult =
            when (outcome) {
                SyncWire.WAITING -> SliceResult.Waiting(untilMs ?: 0)
                SyncWire.INTERRUPTED -> SliceResult.Interrupted(reason ?: SyncWire.CANCELLED)
                SyncWire.FAILED -> SliceResult.Failed(reason ?: SyncWire.OTHER, message.orEmpty())
                else -> SliceResult.Done
            }
    }
}
