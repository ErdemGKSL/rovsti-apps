package dev.rovsti.people.sync

import dev.rovsti.core.model.people.SyncWire

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
    }

    val indeterminate: Boolean get() = max <= 0

    companion object {
        val STARTING = SyncNotice(Line.Starting)

        /**
         * The notice for [state] at [nowMs]. [limitUntilMs] is the worker's own wait for a limit between two slices
         * (the runner is idle then); the runner's inline rate-limit sleep ([RunnerState.limited]) reads the same.
         * The bar keeps the stage's progress while a limit is waited out.
         */
        fun of(
            state: RunnerState,
            nowMs: Long,
            limitUntilMs: Long? = null,
        ): SyncNotice {
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

        /** Whole minutes left, rounded up, at least one ("continues in 0 min" would be wrong while it still waits). */
        fun minutes(ms: Long): Int = ((ms.coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)

        private const val MINUTE_MS = 60_000L
    }
}
