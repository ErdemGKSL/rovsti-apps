package dev.rosty.people.sync

import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.model.people.SyncJobView
import dev.rosty.core.model.people.SyncWire

/**
 * What the sync's ongoing notification says (people sync v2 S11), before it is put in words: a pure function of the
 * runner's state, so the wording and the progress are tested without a notification manager. [SyncNotices] resolves
 * it into strings and builds the notification.
 */
data class SyncNotice(
    val line: Line,
    /** The progress bar: [max] 0 means indeterminate (starting, nothing counted yet). */
    val done: Int = 0,
    val max: Int = 0,
) {
    /** The text line under the title. */
    sealed interface Line {
        /** Nothing counted yet. */
        data object Starting : Line

        /** `SyncWire.STAGE_*` with its count, "Checking numbers 12/340". */
        data class Stage(
            val stage: String,
            val done: Int,
            val total: Int,
        ) : Line

        /** A short WhatsApp limit is waited out: "WhatsApp limit, continues in [minutes] min". */
        data class Limit(
            val minutes: Int,
        ) : Line

        /** People sync v3: the gateway's job works ("Gateway is checking numbers 12/340"); [phase] is a `PHASE_*`. */
        data class Server(
            val phase: String,
            val done: Int,
            val total: Int,
        ) : Line

        /** People sync v3: the job's result is downloaded, "Downloading changes 3/12". */
        data class Download(
            val done: Int,
            val total: Int,
        ) : Line
    }

    val indeterminate: Boolean get() = max <= 0

    companion object {
        val STARTING = SyncNotice(Line.Starting)

        /**
         * The notice for [state] at [nowMs]. [limitUntilMs] is the worker's own wait for a limit between two slices
         * (the runner is idle then); the runner's inline rate-limit sleep ([RunnerState.limited]) reads the same.
         * The bar keeps the stage's progress while a limit is waited out. In job mode (people sync v3) the gateway's
         * phase and count are shown, and a limit the gateway waits for reads as a limit too.
         */
        fun of(
            state: RunnerState,
            nowMs: Long,
            limitUntilMs: Long? = null,
        ): SyncNotice {
            val job = state.job ?: state.stored?.run?.job
            val jobStage = state.stage ?: state.stored?.run?.stage
            if (limitUntilMs == null && job != null && jobStage in JOB_STAGES) {
                return ofJob(state, job, jobStage.orEmpty(), nowMs)
            }
            val run = state.stored?.run
            val progress = state.progress ?: run?.progress
            val stage = state.stage ?: run?.stage ?: SyncWire.STAGE_LOOKUP
            val total = (progress?.total ?: 0).coerceAtLeast(0)
            val done = (progress?.done ?: 0).coerceIn(0, total)
            val until = limitUntilMs ?: state.sleepingUntilMs?.takeIf { state.limited }
            return when {
                until != null -> SyncNotice(Line.Limit(minutes(until - nowMs)), done, total)
                total > 0 -> SyncNotice(Line.Stage(stage, done, total), done, total)
                else -> STARTING
            }
        }

        private fun ofJob(
            state: RunnerState,
            job: SyncJobView,
            stage: String,
            nowMs: Long,
        ): SyncNotice {
            if (stage == SyncWire.STAGE_DOWNLOAD) {
                val progress = state.progress ?: state.stored?.run?.progress
                val total = (progress?.total ?: 0).coerceAtLeast(0)
                val done = (progress?.done ?: 0).coerceIn(0, total)
                return SyncNotice(Line.Download(done, total), done, total)
            }
            val total = job.total.coerceAtLeast(0)
            val done = job.done.coerceIn(0, total)
            val until = job.waitUntilMs
            val limited = job.waitSource == PeopleJobWire.WAIT_WHATSAPP || job.waitSource == PeopleJobWire.WAIT_GATEWAY
            return when {
                job.state == PeopleJobWire.WAITING && limited && until != null && until > nowMs -> {
                    SyncNotice(Line.Limit(minutes(until - nowMs)), done, total)
                }

                job.phase in SERVER_PHASES && total > 0 -> {
                    SyncNotice(Line.Server(job.phase, done, total), done, total)
                }

                else -> {
                    STARTING
                }
            }
        }

        private val JOB_STAGES = setOf(SyncWire.STAGE_SERVER, SyncWire.STAGE_DOWNLOAD)

        private val SERVER_PHASES =
            setOf(PeopleJobWire.PHASE_LOOKUP, PeopleJobWire.PHASE_PROFILES, PeopleJobWire.PHASE_PICTURES)

        /** Whole minutes left, rounded up, at least one ("continues in 0 min" would be wrong while it still waits). */
        fun minutes(ms: Long): Int = ((ms.coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)

        private const val MINUTE_MS = 60_000L
    }
}
