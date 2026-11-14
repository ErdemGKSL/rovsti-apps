package dev.rosty.people.sync

import dev.rosty.core.model.people.SyncWire

/**
 * What the people sync worker does after a slice (people sync v2 S6, S11). Pure, so the stop, timeout and continuation
 * rules are tested without WorkManager.
 *
 * A foreground worker has no time budget: it runs the whole run, and waits out a rate limit of at most
 * [SHORT_WAIT_MS] itself (the notification says how long), then goes on. A longer limit ends the foreground run and
 * leaves a delayed continuation to WorkManager, so no notification stays up for half an hour. A worker that could not
 * become foreground keeps the 8-minute slices and continues every stop later, as before.
 */
object ForegroundSyncPlan {
    /** A rate limit at most this long is waited out by the foreground worker. */
    const val SHORT_WAIT_MS = 5L * 60_000

    /** Added to a waited-out limit, so the planner does not refuse the next slice by a few milliseconds. */
    const val WAIT_MARGIN_MS = 1_000L

    /** Waits one foreground run may do before it leaves the rest to a continuation (a guard, not reached normally). */
    const val MAX_WAITS = 20

    /** A live picture pass ends within `LIVE_SLICE_MS`; a start it refused is tried again after this. */
    const val LIVE_RETRY_MS = 3_000L

    /** A slice that could not reach WhatsApp is tried again after this. */
    const val OFFLINE_DELAY_MS = 15L * 60_000

    /** What comes next. */
    sealed interface Next {
        /** Stay foreground, wait [ms] (the notification shows the limit) and run the next slice. */
        data class Wait(
            val ms: Long,
        ) : Next

        /** End this run and let WorkManager start the next slice after [delayMs]. */
        data class Continue(
            val delayMs: Long,
        ) : Next

        /** A cold process whose stored link was not read in time: WorkManager's back-off tries again. */
        data object Retry : Next

        /** Nothing to continue; the periodic work is enough. */
        data object End : Next
    }

    /**
     * After a slice that ended with [result]; [foreground] when this run holds the notification, [waits] so far.
     * [liveActive]: the slice was refused because a short live picture pass holds the runner, so a tap on Sync Now is
     * not lost; the worker tries again in [LIVE_RETRY_MS].
     */
    fun afterSlice(
        result: SliceResult,
        nowMs: Long,
        foreground: Boolean,
        waits: Int = 0,
        liveActive: Boolean = false,
    ): Next {
        if (result == SliceResult.LinkNotReady) return Next.Retry
        if (result == SliceResult.AlreadyRunning && liveActive && waits < MAX_WAITS) return Next.Wait(LIVE_RETRY_MS)
        val until =
            when (result) {
                is SliceResult.Waiting -> result.untilMs
                is SliceResult.Refused -> result.untilMs
                else -> null
            }
        if (foreground && until != null && waits < MAX_WAITS) {
            val wait = (until - nowMs).coerceAtLeast(0)
            if (wait <= SHORT_WAIT_MS) return Next.Wait(wait + WAIT_MARGIN_MS)
        }
        return continueInMs(result, nowMs)?.let { Next.Continue(it) } ?: Next.End
    }

    /**
     * After the run was cancelled: the user's Stop ([byUser]) keeps the checkpoint and schedules nothing (Continue
     * resumes it); the system's stop (the dataSync time budget, `Service.onTimeout`, a constraint) continues later.
     */
    fun afterStop(byUser: Boolean): Next = if (byUser) Next.End else Next.Continue(PeopleSyncRunner.CONTINUE_DELAY_MS)

    /**
     * When the next slice should run after [result], or null when the periodic work is enough (done, not linked,
     * failed, or another slice is running).
     */
    fun continueInMs(
        result: SliceResult,
        nowMs: Long,
    ): Long? =
        when (result) {
            is SliceResult.Waiting -> {
                (result.untilMs - nowMs).coerceAtLeast(PeopleSyncRunner.CONTINUE_DELAY_MS)
            }

            is SliceResult.Refused -> {
                result.untilMs?.let { (it - nowMs).coerceAtLeast(PeopleSyncRunner.CONTINUE_DELAY_MS) }
            }

            is SliceResult.Interrupted -> {
                when (result.reason) {
                    SyncWire.WA_DISCONNECTED, SyncWire.NETWORK -> OFFLINE_DELAY_MS
                    else -> PeopleSyncRunner.CONTINUE_DELAY_MS
                }
            }

            SliceResult.Done, SliceResult.NotLinked, SliceResult.LinkNotReady, SliceResult.AlreadyRunning,
            is SliceResult.Failed,
            -> {
                null
            }
        }
}
