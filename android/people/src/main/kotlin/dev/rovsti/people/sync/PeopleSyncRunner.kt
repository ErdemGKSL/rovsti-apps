package dev.rovsti.people.sync

import android.util.Base64
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.log.LogLevel
import dev.rovsti.core.log.RovstiLog
import dev.rovsti.core.model.people.PersonUpdate
import dev.rovsti.core.model.people.SyncBeginRequest
import dev.rovsti.core.model.people.SyncNextRequest
import dev.rovsti.core.model.people.SyncPictureResult
import dev.rovsti.core.model.people.SyncProgress
import dev.rovsti.core.model.people.SyncReport
import dev.rovsti.core.model.people.SyncReportRequest
import dev.rovsti.core.model.people.SyncStatus
import dev.rovsti.core.model.people.SyncStatusRequest
import dev.rovsti.core.model.people.SyncStep
import dev.rovsti.core.model.people.SyncStopRequest
import dev.rovsti.core.model.people.SyncStopped
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.ops.PeopleOps
import dev.rovsti.core.ops.WaGateway
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import dev.rovsti.wa.avatars.AvatarCodec
import dev.rovsti.wa.avatars.BitmapAvatarCodec
import dev.rovsti.wa.avatars.rateLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

    /** Resumable; [reason] is a `SyncWire` interrupt reason. */
    data class Interrupted(
        val reason: String,
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
    /** The planner's status, re-read after every slice and every committed part. */
    val stored: SyncStatus? = null,
)

/**
 * Runs the people sync planner (`rovsti_app_core::people_sync`, through the `people.sync_*` ops; plan
 * `_bmad-output/plans/people-sync-v2.md`) against the gateway, one slice at a time. Shared by Contacts and Phone.
 *
 * The loop: `people.sync_next` says what to do; a lookup, profile or picture step calls the gateway and hands the
 * answer to `people.sync_report`, which commits it (book first, checkpoint second); a sleep waits ([pause]); a stop
 * ends the slice. After every committed part with changes the dropped files are deleted and [onPart] is called (the
 * app coalesces it, see [PartNotifier]). Timing, batching, backoff and resumption are all the planner's: this class
 * only performs.
 *
 * One slice at a time: a second [run] while one is active answers [SliceResult.AlreadyRunning] at once, and a file
 * lock ([lockFile]) guards against a second process. A cancelled slice (the worker was stopped, [cancel]) still
 * reports `cancelled` and ends the slice, so the stored run is never left `running`.
 */
class PeopleSyncRunner(
    private val store: PeopleStore,
    private val ops: PeopleOps,
    private val wa: WaRepository,
    private val numbers: suspend () -> List<String>,
    /** The stored link's gateway URL (part of the checkpoint's link identity); empty when unknown. */
    private val gatewayUrl: suspend () -> String = { "" },
    private val codec: AvatarCodec = BitmapAvatarCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val random: () -> Int = { Random.nextInt(0, Int.MAX_VALUE) },
    private val onPart: () -> Unit = {},
    /** Held for the slice; null: no cross-process lock (tests). */
    private val lockFile: File? = null,
) {
    private val running = Mutex()
    private val state = MutableStateFlow(RunnerState())

    @Volatile private var job: Job? = null

    /** What the runner is doing, and the planner's last status. */
    val status: StateFlow<RunnerState> = state.asStateFlow()

    /** Re-reads the planner's status (the settings row, after a process start). */
    suspend fun refresh(): SyncStatus? {
        val stored = (ops.syncStatus(SyncStatusRequest(dir = store.dataDir, nowMs = clock())) as? Outcome.Ok)?.value
        if (stored != null) state.update { it.copy(stored = stored) }
        return stored
    }

    /** Stops the active slice (the "Stop" button, the worker's `onStopped`): it ends `interrupted(cancelled)`. */
    fun cancel() {
        job?.cancel(CancellationException("people sync cancelled"))
    }

    /** WhatsApp was unlinked: a stored run becomes `interrupted(wa_disconnected)` (dropped when another link begins). */
    suspend fun unlinked() {
        cancel()
        val req = SyncStopRequest(dir = store.dataDir, nowMs = clock(), reason = SyncWire.WA_DISCONNECTED)
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
            RovstiLog.event(TAG, "slice_skipped", "reason" to "already_running", "start" to start)
            return SliceResult.AlreadyRunning
        }
        try {
            job = currentCoroutineContext()[Job]
            val lock = lockFile?.let(::tryLock)
            if (lockFile != null && lock == null) {
                RovstiLog.event(TAG, "slice_skipped", "reason" to "file_lock", "start" to start)
                return SliceResult.AlreadyRunning
            }
            try {
                return slice(start, sliceMs)
            } finally {
                lock?.let(::release)
            }
        } finally {
            job = null
            state.update { it.copy(active = false, live = false, sleepingUntilMs = null) }
            running.unlock()
        }
    }

    private suspend fun slice(
        start: String,
        sliceMs: Long?,
    ): SliceResult {
        val waitStart = clock()
        val settled = withTimeoutOrNull(LINK_WAIT_MS) { wa.settled.first { it } } != null
        RovstiLog.event(TAG, "link_wait", "settled" to settled, "ms" to clock() - waitStart)
        if (!settled) return SliceResult.LinkNotReady
        val link = wa.linkState.value
        if (link !is WaLinkState.Connected) {
            RovstiLog.event(TAG, "slice_skipped", "reason" to "not_linked", "link" to link.javaClass.simpleName)
            return SliceResult.NotLinked
        }
        if (link.status.state == InstanceState.Disconnected) {
            RovstiLog.event(TAG, "slice_skipped", "reason" to SyncWire.WA_DISCONNECTED)
            return SliceResult.Interrupted(SyncWire.WA_DISCONNECTED)
        }
        val gateway =
            wa.gateway() ?: run {
                RovstiLog.event(LogLevel.Warn, TAG, "slice_skipped", "reason" to "no_gateway")
                return SliceResult.Interrupted(SyncWire.NETWORK)
            }
        val live = start == SyncWire.START_LIVE_PICTURES
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
                RovstiLog.event(TAG, "slice_refused", "kind" to refused?.kind, "until_ms" to refused?.untilMs)
                return SliceResult.Refused(refused?.untilMs)
            }
        state.update {
            it.copy(
                active = true,
                live = live,
                stage = begun.status.run?.stage,
                progress = begun.status.run?.progress,
            )
        }
        var parts = 0
        var stopped: SyncStopped? = null
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
                        RovstiLog.event(TAG, "pace", "sleep_ms" to ms, "why" to step.why)
                        state.update { it.copy(sleepingUntilMs = clock() + ms) }
                        pause(ms)
                        state.update { it.copy(sleepingUntilMs = null) }
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
                        state.update { it.copy(stage = committed.stage ?: it.stage, progress = committed.progress) }
                        if (committed.changed > 0 || committed.dropFiles.isNotEmpty()) {
                            store.delete(committed.dropFiles)
                            parts++
                            RovstiLog.event(
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
                ops.syncReport(SyncReportRequest(handle, clock(), rand(), SyncReport.cancelled))
                end(parts, SyncStopped(SyncWire.INTERRUPTED, reason = SyncWire.CANCELLED))
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                val req = SyncStopRequest(handle = handle, nowMs = clock(), reason = SyncWire.CANCELLED)
                (ops.syncStop(req) as? Outcome.Ok)?.value?.let { stored -> state.update { it.copy(stored = stored) } }
            }
        }
        val end = stopped ?: SyncStopped(SyncWire.DONE)
        end(parts, end)
        return end.result()
    }

    private fun end(
        parts: Int,
        stopped: SyncStopped,
    ) {
        val progress = state.value.progress
        RovstiLog.event(
            if (stopped.outcome == SyncWire.FAILED) LogLevel.Warn else LogLevel.Info,
            TAG,
            "slice_end",
            "outcome" to stopped.outcome,
            "reason" to (stopped.reason ?: stopped.source),
            "stage" to state.value.stage,
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

                else -> {
                    SyncReport.error(SyncWire.OTHER, "unknown step ${step.kind}")
                }
            }
        } catch (e: IOException) {
            SyncReport.error(SyncWire.NETWORK, e.message ?: "I/O error")
        }.also { report ->
            if (report.kind == "error" || report.kind == "rate_limited") {
                RovstiLog.event(
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
        when (val answer = gateway.picture(PictureBody(jid, knownId = knownId))) {
            is Outcome.Ok -> {
                SyncReport.picture(jid, pictureResult(jid, answer.value))
            }

            is Outcome.Err -> {
                failure(answer.error)
            }
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
        RovstiLog.event(LogLevel.Error, TAG, "error", "kind" to "core", "op" to op, "message" to error.message)
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

    companion object {
        /** The log tag of everything the sync writes. */
        const val TAG = "sync"

        /** How long a slice waits for the stored link to be read ([WaRepository.settled]). */
        const val LINK_WAIT_MS = 20_000L

        /** The worker's slice budget (`people_sync::WORKER_SLICE_MS`): WorkManager stops a worker at 10 min. */
        const val WORKER_SLICE_MS = 8L * 60_000

        /** Between background slices when nothing is throttled (`people_sync::CONTINUE_DELAY_MS`). */
        const val CONTINUE_DELAY_MS = 60_000L

        /** Budget of a live picture pass (`people_sync::LIVE_SLICE_MS`). */
        const val LIVE_SLICE_MS = 30_000L

        /** Numbers asked about per run (`people_sync::MAX_NUMBERS`). */
        const val MAX_NUMBERS = 2_000

        private const val PICTURE_PX = 256
        private const val NOT_CONNECTED = "not_connected"

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
