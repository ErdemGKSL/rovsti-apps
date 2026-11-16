@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.people

import dev.rosty.core.model.wa.DiskFile
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `people.sync_*` (people sync v2), mirroring `crates/rosty_ffi/fixtures/people.sync_*` and
// `rosty_app_core::people_sync`. The Rust enums are internally tagged (`kind`, `outcome`); each is one flat class here
// with the fields of every variant optional, so the strict decoder accepts each shape. Wire values are the constants
// in [SyncWire].

/** The wire names of the planner's enums. */
object SyncWire {
    // Start
    const val START_RESUME = "resume"
    const val START_RESTART = "restart"
    const val START_AUTO = "auto"
    const val START_LIVE_PICTURES = "live_pictures"

    // Stage
    const val STAGE_LOOKUP = "lookup"
    const val STAGE_PROFILES = "profiles"
    const val STAGE_PICTURES = "pictures"

    /** People sync v3, job mode: the gateway works (or the job is being started). */
    const val STAGE_SERVER = "server"

    /** People sync v3, job mode: the bundle is being downloaded. */
    const val STAGE_DOWNLOAD = "download"

    // Step kinds
    const val STEP_LOOKUP = "lookup"
    const val STEP_PROFILES = "profiles"
    const val STEP_PLAN_PICTURES = "plan_pictures"
    const val STEP_PICTURE = "picture"
    const val STEP_SLEEP = "sleep"
    const val STEP_STOP = "stop"

    // Job-mode steps (people sync v3): perform with `WaGateway.peopleSyncStart|Status|Bundle|Cancel`.
    const val STEP_JOB_START = "job_start"
    const val STEP_JOB_WATCH = "job_watch"
    const val STEP_JOB_FETCH = "job_fetch"
    const val STEP_JOB_DONE = "job_done"

    // Job-mode report kinds.
    const val REPORT_JOB_STARTED = "job_started"
    const val REPORT_JOB_UNSUPPORTED = "job_unsupported"
    const val REPORT_JOB = "job"
    const val REPORT_JOB_GONE = "job_gone"
    const val REPORT_BUNDLE = "bundle"
    const val REPORT_JOB_CLOSED = "job_closed"

    // Phase kinds and stop outcomes
    const val RUNNING = "running"
    const val DONE = "done"
    const val WAITING = "waiting"
    const val INTERRUPTED = "interrupted"
    const val FAILED = "failed"

    // Interrupt reasons
    const val PROCESS_DEATH = "process_death"
    const val NETWORK = "network"
    const val WA_DISCONNECTED = "wa_disconnected"
    const val SLICE_BUDGET = "slice_budget"
    const val CANCELLED = "cancelled"
    const val LINK_NOT_READY = "link_not_ready"

    /** Job mode: the slice ended while the gateway still works; continue at the job's `eta_ms`. */
    const val SERVER_WORKING = "server_working"

    // Fail reasons and error kinds
    const val AUTH = "auth"
    const val CORE = "core"
    const val GATEWAY = "gateway"
    const val OTHER = "other"
    const val NOT_CONNECTED = "not_connected"

    // Limit sources
    const val WHATSAPP = "whatsapp"
    const val GATEWAY_LEGACY = "gateway_legacy"

    // Refusals
    const val NOT_BEFORE = "not_before"
    const val NOTHING_TO_RESUME = "nothing_to_resume"
}

@Serializable
data class SyncProgress(
    val done: Int,
    val total: Int,
)

@Serializable
data class SyncStats(
    val batches: Int,
    @SerialName("people_found") val peopleFound: Int,
    val downloaded: Int,
    val dropped: Int,
    @SerialName("waits_ms") val waitsMs: Long,
    @SerialName("rate_limits") val rateLimits: Int,
)

/** `Phase`: `running`, `waiting {until_ms, source}`, `interrupted {reason}`, `failed {reason, message}`. */
@Serializable
data class SyncPhase(
    val kind: String,
    @SerialName("until_ms") @EncodeDefault(NEVER) val untilMs: Long? = null,
    @EncodeDefault(NEVER) val source: String? = null,
    @EncodeDefault(NEVER) val reason: String? = null,
    @EncodeDefault(NEVER) val message: String? = null,
)

/**
 * People sync v3: the gateway job as the settings row and the notification show it. [state], [phase] and [waitSource]
 * are `PeopleJobWire` values; [done]/[total] belong to the phase; [records]/[pictures]/[bytes] once it is ready.
 */
@Serializable
data class SyncJobView(
    @SerialName("job_id") val jobId: String,
    val state: String,
    val phase: String,
    val done: Int,
    val total: Int,
    val numbers: Int,
    @SerialName("wait_until_ms") @EncodeDefault(NEVER) val waitUntilMs: Long? = null,
    @SerialName("wait_source") @EncodeDefault(NEVER) val waitSource: String? = null,
    @SerialName("eta_ms") @EncodeDefault(NEVER) val etaMs: Long? = null,
    @EncodeDefault(NEVER) val records: Int? = null,
    @EncodeDefault(NEVER) val pictures: Int? = null,
    @EncodeDefault(NEVER) val bytes: Long? = null,
    /** False: another device's job; this run waits for it to end. */
    @EncodeDefault(NEVER) val mine: Boolean = true,
)

@Serializable
data class SyncRunStatus(
    val id: Long,
    val mode: String,
    val stage: String,
    val phase: SyncPhase,
    val progress: SyncProgress,
    val resumable: Boolean,
    @SerialName("heartbeat_ms") val heartbeatMs: Long,
    val stats: SyncStats,
    /** Job mode (stage `server` / `download`): the gateway job. */
    @EncodeDefault(NEVER) val job: SyncJobView? = null,
)

@Serializable
data class SyncFinished(
    @SerialName("at_ms") val atMs: Long,
    val people: Int,
    val downloaded: Int,
    @SerialName("duration_ms") val durationMs: Long,
    /** People the run added or changed in the book; null for a run of an older build. */
    @EncodeDefault(NEVER) val changed: Int? = null,
    /** Job mode: bundle bytes downloaded. */
    @EncodeDefault(NEVER) val bytes: Long = 0,
)

/** What the settings row shows: the stored run, the last finished one, WhatsApp's penalty. */
@Serializable
data class SyncStatus(
    @EncodeDefault(NEVER) val run: SyncRunStatus? = null,
    @EncodeDefault(NEVER) val last: SyncFinished? = null,
    @SerialName("not_before_ms") @EncodeDefault(NEVER) val notBeforeMs: Long? = null,
    /** When a run was last started or resumed (the triggers' debounce). */
    @SerialName("attempt_ms") @EncodeDefault(NEVER) val attemptMs: Long? = null,
)

/** `people.sync_status`. */
@Serializable
data class SyncStatusRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("now_ms") val nowMs: Long,
)

/** `people.sync_begin`: [numbers] are E.164, recent callers first; [start] is a `SyncWire.START_*`. */
@Serializable
data class SyncBeginRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("gateway_url") val gatewayUrl: String,
    val numbers: List<String>,
    val start: String,
    @SerialName("slice_ms") @EncodeDefault(NEVER) val sliceMs: Long? = null,
    /**
     * People sync v3: true when the caller performs the `job_*` steps; a new run then uses the gateway's people job
     * (falling back to the v2 steps on a gateway without it). False: the v2 steps only.
     */
    @EncodeDefault(NEVER) val jobs: Boolean = false,
)

/** `refused`: `not_before {until_ms}` or `nothing_to_resume`. */
@Serializable
data class SyncRefused(
    val kind: String,
    @SerialName("until_ms") @EncodeDefault(NEVER) val untilMs: Long? = null,
)

@Serializable
data class SyncBeginAnswer(
    @EncodeDefault(NEVER) val handle: Long? = null,
    @EncodeDefault(NEVER) val refused: SyncRefused? = null,
    val status: SyncStatus,
)

/** `people.sync_next`; [rand] is any number (the jitter of the next gap). */
@Serializable
data class SyncNextRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val rand: Int,
)

/** How a slice ended: `done`, `waiting {until_ms, source}`, `interrupted {reason}`, `failed {reason, message}`. */
@Serializable
data class SyncStopped(
    val outcome: String,
    @SerialName("until_ms") @EncodeDefault(NEVER) val untilMs: Long? = null,
    @EncodeDefault(NEVER) val source: String? = null,
    @EncodeDefault(NEVER) val reason: String? = null,
    @EncodeDefault(NEVER) val message: String? = null,
)

/**
 * One step: `lookup|profiles {jids}`, `plan_pictures`, `picture {jid, known_id?}`, `sleep {ms, why}`, `stop {stopped}`;
 * job mode (people sync v3): `job_start {mode, entries}`, `job_watch {job_id, rev, wait_ms}`,
 * `job_fetch {job_id, after, max_bytes}`, `job_done {job_id}`.
 */
@Serializable
data class SyncStep(
    val kind: String,
    @EncodeDefault(NEVER) val jids: List<String>? = null,
    @EncodeDefault(NEVER) val jid: String? = null,
    @SerialName("known_id") @EncodeDefault(NEVER) val knownId: String? = null,
    @EncodeDefault(NEVER) val ms: Long? = null,
    @EncodeDefault(NEVER) val why: String? = null,
    @EncodeDefault(NEVER) val stopped: SyncStopped? = null,
    @EncodeDefault(NEVER) val mode: String? = null,
    @EncodeDefault(NEVER) val entries: List<PeopleEntry>? = null,
    @SerialName("job_id") @EncodeDefault(NEVER) val jobId: String? = null,
    @EncodeDefault(NEVER) val rev: Long? = null,
    @SerialName("wait_ms") @EncodeDefault(NEVER) val waitMs: Long? = null,
    @EncodeDefault(NEVER) val after: Int? = null,
    @SerialName("max_bytes") @EncodeDefault(NEVER) val maxBytes: Long? = null,
)

/** Job mode: what became of a bundle record's picture bytes: `stored {file}` or `failed`. */
@Serializable
data class SyncJobPicture(
    val kind: String,
    @EncodeDefault(NEVER) val file: String? = null,
) {
    companion object {
        const val STORED = "stored"
        const val FAILED = "failed"

        fun stored(file: String) = SyncJobPicture(STORED, file)

        val failed = SyncJobPicture(FAILED)
    }
}

/**
 * Job mode: one bundle record as reported back: the gateway's fields (copy them from [PeopleBundleRecord]) and
 * [picture], absent when the record carried no picture bytes.
 */
@Serializable
data class SyncJobRecord(
    val seq: Int,
    val number: String,
    val hash: String,
    @SerialName("on_whatsapp") val onWhatsapp: Boolean,
    @EncodeDefault(NEVER) val jid: String? = null,
    @EncodeDefault(NEVER) val lid: String? = null,
    @SerialName("push_name") @EncodeDefault(NEVER) val pushName: String? = null,
    @SerialName("verified_name") @EncodeDefault(NEVER) val verifiedName: String? = null,
    @SerialName("picture_id") @EncodeDefault(NEVER) val pictureId: String? = null,
    @SerialName("picture_missing") @EncodeDefault(NEVER) val pictureMissing: Boolean = false,
    @EncodeDefault(NEVER) val picture: SyncJobPicture? = null,
) {
    companion object {
        /** The record of [record] with what became of its picture ([picture] null when it carried none). */
        fun of(
            record: PeopleBundleRecord,
            picture: SyncJobPicture?,
        ) = SyncJobRecord(
            seq = record.seq,
            number = record.number,
            hash = record.hash,
            onWhatsapp = record.onWhatsapp,
            jid = record.jid,
            lid = record.lid,
            pushName = record.pushName,
            verifiedName = record.verifiedName,
            pictureId = record.pictureId,
            pictureMissing = record.pictureMissing,
            picture = picture,
        )
    }
}

/** A picture's answer: `stored {picture_id, file}`, `unchanged`, `none`, `failed`. */
@Serializable
data class SyncPictureResult(
    val kind: String,
    @SerialName("picture_id") @EncodeDefault(NEVER) val pictureId: String? = null,
    @EncodeDefault(NEVER) val file: String? = null,
) {
    companion object {
        const val STORED = "stored"
        const val UNCHANGED = "unchanged"
        const val NONE = "none"
        const val FAILED = "failed"
    }
}

/**
 * What the caller got: `lookup {asked, found}`, `profiles {asked, answered, unanswered}`, `files {files, max_bytes}`,
 * `picture {jid, result}`, `rate_limited {retry_after_ms?, source}`, `error {error, message}`, `cancelled`. Build one
 * with the companion's functions.
 */
@Serializable
data class SyncReport(
    val kind: String,
    @EncodeDefault(NEVER) val asked: List<String>? = null,
    @EncodeDefault(NEVER) val found: List<PersonUpdate>? = null,
    @EncodeDefault(NEVER) val answered: List<PersonUpdate>? = null,
    @EncodeDefault(NEVER) val unanswered: List<String>? = null,
    @EncodeDefault(NEVER) val files: List<DiskFile>? = null,
    @SerialName("max_bytes") @EncodeDefault(NEVER) val maxBytes: Long? = null,
    @EncodeDefault(NEVER) val jid: String? = null,
    @EncodeDefault(NEVER) val result: SyncPictureResult? = null,
    @SerialName("retry_after_ms") @EncodeDefault(NEVER) val retryAfterMs: Long? = null,
    @EncodeDefault(NEVER) val source: String? = null,
    @EncodeDefault(NEVER) val error: String? = null,
    @EncodeDefault(NEVER) val message: String? = null,
    @EncodeDefault(NEVER) val status: PeopleJobStatus? = null,
    @SerialName("job_id") @EncodeDefault(NEVER) val jobId: String? = null,
    @EncodeDefault(NEVER) val records: List<SyncJobRecord>? = null,
    @SerialName("last_seq") @EncodeDefault(NEVER) val lastSeq: Int? = null,
    @EncodeDefault(NEVER) val more: Boolean? = null,
    @EncodeDefault(NEVER) val bytes: Long? = null,
) {
    companion object {
        /** Job mode: the answer of `wa.people_sync_start` with `supported = true`. */
        fun jobStarted(status: PeopleJobStatus) = SyncReport(SyncWire.REPORT_JOB_STARTED, status = status)

        /** Job mode: `wa.people_sync_start` answered `supported = false` (an old gateway). */
        val jobUnsupported = SyncReport(SyncWire.REPORT_JOB_UNSUPPORTED)

        /** Job mode: the answer of `wa.people_sync_status`. */
        fun job(status: PeopleJobStatus) = SyncReport(SyncWire.REPORT_JOB, status = status)

        /** Job mode: `wa.people_sync_status` or `wa.people_sync_bundle` answered `gone = true`. */
        val jobGone = SyncReport(SyncWire.REPORT_JOB_GONE)

        /** Job mode: one bundle part, its pictures stored (or failed). */
        fun bundle(
            jobId: String,
            records: List<SyncJobRecord>,
            lastSeq: Int,
            more: Boolean,
            bytes: Long,
        ) = SyncReport(
            SyncWire.REPORT_BUNDLE,
            jobId = jobId,
            records = records,
            lastSeq = lastSeq,
            more = more,
            bytes = bytes,
        )

        /** Job mode: `wa.people_sync_cancel` was sent (its answer does not matter). */
        val jobClosed = SyncReport(SyncWire.REPORT_JOB_CLOSED)

        fun lookup(
            asked: List<String>,
            found: List<PersonUpdate>,
        ) = SyncReport("lookup", asked = asked, found = found)

        fun profiles(
            asked: List<String>,
            answered: List<PersonUpdate>,
            unanswered: List<String>,
        ) = SyncReport("profiles", asked = asked, answered = answered, unanswered = unanswered)

        fun files(
            files: List<DiskFile>,
            maxBytes: Long,
        ) = SyncReport("files", files = files, maxBytes = maxBytes)

        fun picture(
            jid: String,
            result: SyncPictureResult,
        ) = SyncReport("picture", jid = jid, result = result)

        fun rateLimited(
            retryAfterMs: Long?,
            source: String,
        ) = SyncReport("rate_limited", retryAfterMs = retryAfterMs, source = source)

        /** [error] is a `SyncWire` error kind: network, not_connected, auth, core, gateway, other. */
        fun error(
            error: String,
            message: String,
        ) = SyncReport("error", error = error, message = message)

        val cancelled = SyncReport("cancelled")
    }
}

/** `people.sync_report`. */
@Serializable
data class SyncReportRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val rand: Int,
    val report: SyncReport,
)

@Serializable
data class SyncCommitted(
    val changed: Int,
    /** Files (names or paths) to delete. */
    @SerialName("drop_files") val dropFiles: List<String>,
    @EncodeDefault(NEVER) val stage: String? = null,
    val progress: SyncProgress,
    /** Job mode: the gateway job now (for the live row and notification). */
    @EncodeDefault(NEVER) val job: SyncJobView? = null,
)

/** `people.sync_stop`: with [handle] ends that slice; without, interrupts the stored run (unlink). */
@Serializable
data class SyncStopRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @EncodeDefault(NEVER) val handle: Long? = null,
    @SerialName("now_ms") val nowMs: Long,
    val reason: String,
)
