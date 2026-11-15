package dev.rosty.stops.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.rosty.core.log.LogLevel
import dev.rosty.core.log.RostyLog
import dev.rosty.shell.HasContainer
import dev.rosty.stops.StopsContainer

/**
 * The offline area download as a foreground worker (S10, the people-sync S11 pattern): it asks to run as a `dataSync`
 * foreground service with [OfflineNotices]' ongoing notification, so a large area keeps downloading when the app is
 * closed, then hands the work to the container's [OfflineDownloads]. If Android refuses the foreground service (a start
 * from the background), the download runs anyway for as long as WorkManager lets it. WorkManager creates the worker
 * by name, so the class keeps its name (R8 keeps the constructor).
 */
class OfflineAreaWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val notices by lazy { OfflineNotices(applicationContext) }

    override suspend fun getForegroundInfo(): ForegroundInfo = notices.info(OfflineState.Starting)

    override suspend fun doWork(): Result {
        val offline = downloads(applicationContext) ?: return Result.failure()
        val area = areaOf(inputData) ?: return Result.failure()
        promote()
        val end = offline.run(area) { state -> if (state.active) notices.post(state) }
        notices.postDone(end)
        RostyLog.event(TAG, "offline_done", "state" to end.javaClass.simpleName)
        return Result.success()
    }

    private suspend fun promote() {
        try {
            setForeground(notices.info(OfflineState.Starting))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (a start from the background) is an IllegalStateException.
            refused(e)
        } catch (e: SecurityException) {
            refused(e)
        }
    }

    private fun refused(e: RuntimeException) =
        RostyLog.event(LogLevel.Warn, TAG, "foreground_refused", "error" to e.javaClass.simpleName)

    companion object {
        const val TAG = "offline"

        /** The unique work: one download at a time. */
        const val UNIQUE_NAME = "offline-area"

        private const val KEY_SOUTH = "south"
        private const val KEY_WEST = "west"
        private const val KEY_NORTH = "north"
        private const val KEY_EAST = "east"

        fun dataOf(area: MapArea): Data =
            workDataOf(KEY_SOUTH to area.south, KEY_WEST to area.west, KEY_NORTH to area.north, KEY_EAST to area.east)

        /** The area of [data], or null when a key is missing. */
        fun areaOf(data: Data): MapArea? {
            val keys = listOf(KEY_SOUTH, KEY_WEST, KEY_NORTH, KEY_EAST)
            if (keys.any { data.keyValueMap[it] !is Double }) return null
            return MapArea(
                data.getDouble(KEY_SOUTH, 0.0),
                data.getDouble(KEY_WEST, 0.0),
                data.getDouble(KEY_NORTH, 0.0),
                data.getDouble(KEY_EAST, 0.0),
            )
        }

        /** Expedited (a normal job when the quota is spent), so a download asked for in Settings starts at once. */
        fun request(area: MapArea): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<OfflineAreaWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(dataOf(area))
                .addTag(TAG)
                .build()

        fun enqueue(
            context: Context,
            area: MapArea,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request(area))
        }

        fun cancelQueued(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        internal fun downloads(context: Context): OfflineDownloads? =
            ((context as? HasContainer<*>)?.container as? StopsContainer)?.offline
    }
}

/** The notification's Cancel: the same path as the Settings row's Cancel button. Not exported. */
class OfflineCancelReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_CANCEL) return
        OfflineAreaWorker.downloads(context.applicationContext)?.cancel()
    }

    companion object {
        const val ACTION_CANCEL = "dev.rosty.stops.offline.CANCEL"
    }
}
