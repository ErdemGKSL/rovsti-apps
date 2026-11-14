package dev.rovsti.contacts.people

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.core.log.RovstiLog
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.people.sync.PeopleSyncRunner
import dev.rovsti.people.sync.SliceResult
import dev.rovsti.shell.HasContainer
import java.util.concurrent.TimeUnit

/**
 * The people sync in the background (X.1b, people sync v2 S6). WorkManager creates it by name
 * (`PeopleSyncWorker(Context, WorkerParameters)`), so R8 keeps that constructor (`proguard-rules.pro`). Every start
 * runs one slice of at most [PeopleSyncRunner.WORKER_SLICE_MS] ([PeopleSync.runSlice]: resume, else an incremental
 * run). The planner owns the timing, so a slice that stops with work left does not ask WorkManager for its back-off:
 * it enqueues the unique one-time work [CONTINUE_NAME] for when the planner says (a rate limit's end, or
 * [PeopleSyncRunner.CONTINUE_DELAY_MS]) and succeeds. A failed run succeeds too (its reason is stored); the next period
 * tries again.
 */
class PeopleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? HasContainer<*>)?.container as? ContactsContainer ?: return Result.failure()
        val result = container.peopleSync.runSlice()
        val now = System.currentTimeMillis()
        val next = continueInMs(result, now)
        RovstiLog.event(
            PeopleSync.TAG,
            "worker",
            "result" to result.javaClass.simpleName,
            "continue_in_ms" to next,
        )
        if (next != null) continueLater(applicationContext, next)
        // A cold process whose stored link was not read in time: try again soon with WorkManager's back-off.
        return if (result == SliceResult.LinkNotReady) Result.retry() else Result.success()
    }

    companion object {
        /** The name of the unique periodic work. */
        const val UNIQUE_NAME = "people-sync"

        /** The one-time work that continues a run between periods. */
        const val CONTINUE_NAME = "people-sync-continue"

        /** A slice that could not reach WhatsApp is tried again after this. */
        const val OFFLINE_DELAY_MS = 15L * 60_000

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

        /** The request WorkManager repeats: about every six hours, on a network, not while the battery is low. */
        fun request(): androidx.work.PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PeopleSyncWorker>(PeopleSync.PERIOD_MS, TimeUnit.MILLISECONDS)
                .setConstraints(constraints(batteryNotLow = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

        /** The continuation: once, after [delayMs], on a network; a newer one replaces it. */
        fun continueRequest(delayMs: Long): androidx.work.OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PeopleSyncWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints(batteryNotLow = false))
                .build()

        /** Makes sure the periodic work exists; an existing one keeps its schedule (`KEEP`). */
        fun schedule(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
        }

        /** Enqueues (or moves) the continuation. */
        fun continueLater(
            context: Context,
            delayMs: Long,
        ) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(CONTINUE_NAME, ExistingWorkPolicy.REPLACE, continueRequest(delayMs))
        }

        private fun constraints(batteryNotLow: Boolean) =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(batteryNotLow)
                .build()

        private const val BACKOFF_MINUTES = 30L
    }
}
