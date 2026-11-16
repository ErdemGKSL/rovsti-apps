package dev.rosty.people.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.rosty.core.log.RostyLog
import java.util.concurrent.TimeUnit

/**
 * The one-time work of the people sync (people sync v2 S11), the same in Contacts and Phone; each app passes its own
 * [ForegroundPeopleSyncWorker] subclass. Unique names keep one run at a time: [NOW_NAME] for the app's own starts
 * (expedited, `KEEP`), [CONTINUE_NAME] for the delayed continuation (`REPLACE`: the newest delay wins). A start that
 * finds a slice running in the process gets `AlreadyRunning` from the runner and ends at once.
 */
object PeopleSyncWork {
    /** The one-time work that continues a run later (a long rate limit, a system stop, the 8-minute fallback). */
    const val CONTINUE_NAME = "people-sync-continue"

    /** The app's own starts: Sync Now, Continue, the foreground trigger, a (re)connected link. */
    const val NOW_NAME = "people-sync-now"

    fun network(batteryNotLow: Boolean = false): Constraints =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(batteryNotLow)
            .build()

    /** A start of the app: expedited (a normal job when the quota is spent), on a network, with its start. */
    fun nowRequest(
        worker: Class<out ListenableWorker>,
        start: String,
    ): OneTimeWorkRequest =
        OneTimeWorkRequest
            .Builder(worker)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(ForegroundPeopleSyncWorker.KEY_START to start))
            .setConstraints(network())
            .build()

    /** The continuation: once, after [delayMs], on a network. */
    fun continueRequest(
        worker: Class<out ListenableWorker>,
        delayMs: Long,
    ): OneTimeWorkRequest =
        OneTimeWorkRequest
            .Builder(worker)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(network())
            .build()

    /** Starts a run now; while a start is pending or running a second one adds nothing (`KEEP`). */
    fun startNow(
        context: Context,
        worker: Class<out ListenableWorker>,
        start: String,
    ) {
        RostyLog.event(PeopleSyncRunner.TAG, "start_requested", "start" to start)
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, nowRequest(worker, start))
    }

    /** Drops a pending continuation: the user stopped the run between two slices (people sync v3). */
    fun dropContinuation(context: Context) {
        RostyLog.event(PeopleSyncRunner.TAG, "continue_dropped")
        WorkManager.getInstance(context).cancelUniqueWork(CONTINUE_NAME)
    }

    /** Enqueues (or moves) the continuation. */
    fun continueLater(
        context: Context,
        worker: Class<out ListenableWorker>,
        delayMs: Long,
    ) {
        RostyLog.event(PeopleSyncRunner.TAG, "continue_scheduled", "delay_ms" to delayMs)
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(CONTINUE_NAME, ExistingWorkPolicy.REPLACE, continueRequest(worker, delayMs))
    }
}
