@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.people

import dev.rovsti.core.model.wa.DiskFile
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `people.sync_*` (people sync v2), mirroring `crates/rovsti_ffi/fixtures/people.sync_*` and
// `rovsti_app_core::people_sync`. The Rust enums are internally tagged (`kind`, `outcome`); each is one flat class here
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

    // Step kinds
    const val STEP_LOOKUP = "lookup"
    const val STEP_PROFILES = "profiles"
    const val STEP_PLAN_PICTURES = "plan_pictures"
    const val STEP_PICTURE = "picture"
    const val STEP_SLEEP = "sleep"
    const val STEP_STOP = "stop"

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
)

@Serializable
data class SyncFinished(
    @SerialName("at_ms") val atMs: Long,
    val people: Int,
    val downloaded: Int,
    @SerialName("duration_ms") val durationMs: Long,
)

/** What the settings row shows: the stored run, the last finished one, WhatsApp's penalty. */
@Serializable
data class SyncStatus(
    @EncodeDefault(NEVER) val run: SyncRunStatus? = null,
    @EncodeDefault(NEVER) val last: SyncFinished? = null,
    @SerialName("not_before_ms") @EncodeDefault(NEVER) val notBeforeMs: Long? = null,
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

/** One step: `lookup|profiles {jids}`, `plan_pictures`, `picture {jid, known_id?}`, `sleep {ms, why}`, `stop {stopped}`. */
@Serializable
data class SyncStep(
    val kind: String,
    @EncodeDefault(NEVER) val jids: List<String>? = null,
    @EncodeDefault(NEVER) val jid: String? = null,
    @SerialName("known_id") @EncodeDefault(NEVER) val knownId: String? = null,
    @EncodeDefault(NEVER) val ms: Long? = null,
    @EncodeDefault(NEVER) val why: String? = null,
    @EncodeDefault(NEVER) val stopped: SyncStopped? = null,
)

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
) {
    companion object {
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
)

/** `people.sync_stop`: with [handle] ends that slice; without, interrupts the stored run (unlink). */
@Serializable
data class SyncStopRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @EncodeDefault(NEVER) val handle: Long? = null,
    @SerialName("now_ms") val nowMs: Long,
    val reason: String,
)
