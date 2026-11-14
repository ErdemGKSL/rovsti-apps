package dev.rosty.contacts.people

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rosty.contacts.ContactsContainer
import dev.rosty.contacts.MainActivity
import dev.rosty.contacts.OPEN_PEOPLE_SYNC
import dev.rosty.people.sync.ForegroundPeopleSyncWorker
import dev.rosty.people.sync.ForegroundSyncPlan
import dev.rosty.people.sync.PeopleSyncHost
import dev.rosty.people.sync.PeopleSyncWork
import dev.rosty.people.sync.SliceResult
import dev.rosty.shell.HasContainer
import java.util.concurrent.TimeUnit

/**
 * Contacts' people sync worker (X.1b, people sync v2 S6, S11). WorkManager creates it by name
 * (`PeopleSyncWorker(Context, WorkerParameters)`, kept by `proguard-rules.pro`: a periodic request stored in its
 * database survives an update), so the class keeps its name; the work itself is the shared
 * [ForegroundPeopleSyncWorker]: a `dataSync` foreground service with the ongoing notification while a run is under way,
 * the 8-minute slices only when Android refuses the foreground service.
 *
 * Every start comes here: the periodic work ([UNIQUE_NAME]), its continuation ([CONTINUE_NAME], enqueued for when the
 * planner says) and the starts of the app ([startNow]: Sync Now, Continue, the foreground trigger), which are
 * expedited unique work, so a run started from the UI survives the app going to the background.
 */
class PeopleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : ForegroundPeopleSyncWorker(context, params) {
    override suspend fun host(): PeopleSyncHost? =
        ((applicationContext as? HasContainer<*>)?.container as? ContactsContainer)?.peopleSync

    /** The notification opens the Settings tab, where the sync rows are. */
    override fun openIntent(): Intent =
        Intent(applicationContext, MainActivity::class.java)
            .setAction(OPEN_PEOPLE_SYNC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    override fun continueLater(delayMs: Long) = continueLater(applicationContext, delayMs)

    companion object {
        /** The name of the unique periodic work. */
        const val UNIQUE_NAME = "people-sync"

        /** The one-time work that continues a run between periods. */
        const val CONTINUE_NAME = PeopleSyncWork.CONTINUE_NAME

        /** The app's own starts (Sync Now, Continue, the foreground trigger): expedited, one at a time. */
        const val NOW_NAME = PeopleSyncWork.NOW_NAME

        /** A slice that could not reach WhatsApp is tried again after this. */
        const val OFFLINE_DELAY_MS = ForegroundSyncPlan.OFFLINE_DELAY_MS

        /**
         * When the next slice should run after [result] in the background, or null when the periodic work is enough
         * ([ForegroundSyncPlan.continueInMs]).
         */
        fun continueInMs(
            result: SliceResult,
            nowMs: Long,
        ): Long? = ForegroundSyncPlan.continueInMs(result, nowMs)

        /** The request WorkManager repeats: about every six hours, on a network, not while the battery is low. */
        fun request(): androidx.work.PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PeopleSyncWorker>(PeopleSync.PERIOD_MS, TimeUnit.MILLISECONDS)
                .setConstraints(PeopleSyncWork.network(batteryNotLow = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

        /** The continuation: once, after [delayMs], on a network; a newer one replaces it. */
        fun continueRequest(delayMs: Long): androidx.work.OneTimeWorkRequest =
            PeopleSyncWork.continueRequest(PeopleSyncWorker::class.java, delayMs)

        /** A start of the app: expedited (a normal job when the quota is spent), on a network, with its [start]. */
        fun nowRequest(start: String): androidx.work.OneTimeWorkRequest =
            PeopleSyncWork.nowRequest(PeopleSyncWorker::class.java, start)

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
        ) = PeopleSyncWork.continueLater(context, PeopleSyncWorker::class.java, delayMs)

        /**
         * Starts a run now (`SyncWire.START_*`). `KEEP`: while a start of the app is pending or running, a second tap
         * adds nothing (one run at a time; the runner refuses a second slice anyway).
         */
        fun startNow(
            context: Context,
            start: String,
        ) = PeopleSyncWork.startNow(context, PeopleSyncWorker::class.java, start)

        private const val BACKOFF_MINUTES = 30L
    }
}
