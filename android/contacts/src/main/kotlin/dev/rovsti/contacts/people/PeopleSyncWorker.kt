package dev.rovsti.contacts.people

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.shell.HasContainer
import java.util.concurrent.TimeUnit

/**
 * The periodic people sync (X.1b). WorkManager creates it by name (`PeopleSyncWorker(Context, WorkerParameters)`), so
 * R8 keeps that constructor (`proguard-rules.pro`). It does what "Sync now" does ([PeopleSync.run]); with no link it
 * finishes at once, and a gateway error asks WorkManager to retry with its back-off.
 */
class PeopleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? HasContainer<*>)?.container as? ContactsContainer ?: return Result.failure()
        return when (container.peopleSync.run()) {
            SyncResult.Failed -> Result.retry()
            SyncResult.NotLinked, is SyncResult.Synced -> Result.success()
        }
    }

    companion object {
        /** The name of the unique periodic work. */
        const val UNIQUE_NAME = "people-sync"

        /** The request WorkManager repeats: about every six hours, on a network, not while the battery is low. */
        fun request(): androidx.work.PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PeopleSyncWorker>(PeopleSync.PERIOD_MS, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

        /** Makes sure the periodic work exists; an existing one keeps its schedule (`KEEP`). */
        fun schedule(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
        }

        private const val BACKOFF_MINUTES = 30L
    }
}
