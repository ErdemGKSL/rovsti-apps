@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.people

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// People sync v3 (`_bmad-output/kotlin-ui/plans/people-sync-v3.md`): the gateway's people job, mirroring
// `rosty_wa_proto::people` and the `wa.people_sync_*` ops (`crates/rosty_ffi/src/ops/wa/people.rs`). Enum values
// travel as strings (`PeopleJobWire`); an unknown one from a newer gateway reads as `other` on the Rust side.

/** The wire names of the people job's enums. */
object PeopleJobWire {
    // PeopleSyncMode
    const val MODE_AUTO = "auto"
    const val MODE_FULL = "full"

    // PeopleJobState
    const val RUNNING = "running"
    const val WAITING = "waiting"
    const val READY = "ready"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val OTHER = "other"

    // PeopleJobPhase
    const val PHASE_LOOKUP = "lookup"
    const val PHASE_PROFILES = "profiles"
    const val PHASE_PICTURES = "pictures"
    const val PHASE_BUNDLE = "bundle"

    // PeopleWaitSource
    const val WAIT_WHATSAPP = "whatsapp"
    const val WAIT_GATEWAY = "gateway"
    const val WAIT_NOT_CONNECTED = "not_connected"

    // PeopleJobError.code
    const val ERROR_EXPIRED = "expired"
    const val ERROR_WHATSAPP = "whatsapp"
    const val ERROR_INTERNAL = "internal"
}

/** One number of a start: [n] in E.164, [h] the hash the gateway gave last time, [p] the picture id held on disk. */
@Serializable
data class PeopleEntry(
    val n: String,
    @EncodeDefault(NEVER) val h: String? = null,
    @EncodeDefault(NEVER) val p: String? = null,
)

@Serializable
data class PeopleWait(
    @SerialName("until_ms") val untilMs: Long,
    /** A `PeopleJobWire.WAIT_*`. */
    val source: String,
)

@Serializable
data class PeopleJobResult(
    val records: Int,
    val pictures: Int,
    val bytes: Long,
)

@Serializable
data class PeopleJobError(
    /** A `PeopleJobWire.ERROR_*`. */
    val code: String,
    val message: String,
)

/** What the gateway says about its people job. [state] and [phase] are `PeopleJobWire` values. */
@Serializable
data class PeopleJobStatus(
    @SerialName("job_id") val jobId: String,
    val rev: Long,
    /** False on a start answer: another device's job works on the instance. */
    @EncodeDefault(NEVER) val mine: Boolean = true,
    val state: String,
    val phase: String,
    val done: Int = 0,
    val total: Int = 0,
    val numbers: Int = 0,
    @EncodeDefault(NEVER) val wait: PeopleWait? = null,
    @SerialName("eta_ms") @EncodeDefault(NEVER) val etaMs: Long? = null,
    @EncodeDefault(NEVER) val result: PeopleJobResult? = null,
    @EncodeDefault(NEVER) val error: PeopleJobError? = null,
    @SerialName("account_epoch") val accountEpoch: Int = 0,
    @SerialName("created_ms") val createdMs: Long = 0,
    @SerialName("updated_ms") val updatedMs: Long = 0,
    @SerialName("expires_ms") @EncodeDefault(NEVER) val expiresMs: Long? = null,
)

/** `wa.people_sync_start`: [mode] is a `PeopleJobWire.MODE_*` (the planner's `JobStart` step carries both). */
@Serializable
data class PeopleSyncStartBody(
    val mode: String,
    val entries: List<PeopleEntry>,
)

/** [supported] false: the gateway has no job routes; report `SyncReport.jobUnsupported`. */
@Serializable
data class PeopleSyncStartAnswer(
    val supported: Boolean,
    @EncodeDefault(NEVER) val status: PeopleJobStatus? = null,
)

/** `wa.people_sync_status`: with [rev] and [waitMs] (at most 25 000) the gateway answers on the next change. */
@Serializable
data class PeopleSyncStatusBody(
    @SerialName("job_id") val jobId: String,
    @EncodeDefault(NEVER) val rev: Long? = null,
    @SerialName("wait_ms") @EncodeDefault(NEVER) val waitMs: Long? = null,
)

/** [gone]: the gateway no longer has the job; report `SyncReport.jobGone`. */
@Serializable
data class PeopleSyncStatusAnswer(
    @EncodeDefault(NEVER) val gone: Boolean = false,
    @EncodeDefault(NEVER) val status: PeopleJobStatus? = null,
)

/** `wa.people_sync_bundle`: the records after [after]; [maxBytes] null is the gateway's 8 MiB. */
@Serializable
data class PeopleSyncBundleBody(
    @SerialName("job_id") val jobId: String,
    val after: Int,
    @SerialName("max_bytes") @EncodeDefault(NEVER) val maxBytes: Long? = null,
)

/**
 * One record of a bundle part. [picturePath] is a temp file with the full picture (downscale and store it, then delete
 * it); [pictureMissing]: the gateway has a picture it could not get (the old file keeps showing).
 */
@Serializable
data class PeopleBundleRecord(
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
    @SerialName("picture_path") @EncodeDefault(NEVER) val picturePath: String? = null,
)

/** One part: [lastSeq] is the next `after`, [more] another part follows. [gone]: report `SyncReport.jobGone`. */
@Serializable
data class PeopleSyncBundleAnswer(
    @EncodeDefault(NEVER) val gone: Boolean = false,
    val records: List<PeopleBundleRecord>,
    @SerialName("last_seq") val lastSeq: Int,
    val more: Boolean,
    val bytes: Long,
)

/** `wa.people_sync_cancel`. */
@Serializable
data class PeopleSyncJobBody(
    @SerialName("job_id") val jobId: String,
)
