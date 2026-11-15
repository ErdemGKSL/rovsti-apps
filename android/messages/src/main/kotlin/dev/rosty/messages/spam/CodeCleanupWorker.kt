package dev.rosty.messages.spam

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rosty.core.log.RostyLog
import dev.rosty.messages.MessagesContainer
import dev.rosty.shell.HasContainer
import java.util.concurrent.TimeUnit

/**
 * The M47 job: a one-time WorkManager job at the time the next passcode message is due. WorkManager keeps it across app
 * kills and restarts; [CodeCleanup.sweep] deletes what is due and books the next run. No periodic work.
 */
class CodeCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? HasContainer<*>)?.container as? MessagesContainer ?: return Result.failure()
        running = true
        try {
            container.codeCleanup.sweep()
        } finally {
            running = false
        }
        return Result.success()
    }

    companion object {
        const val NAME = "code-cleanup"
        private const val TAG = "messages"

        @Volatile private var running = false

        /**
         * Runs the job in [delayMs], replacing the one waiting: every caller asks for the earliest booking still
         * pending, so nothing is pushed later. A booking made by the running job itself is appended, so the job is not
         * cancelled under its own feet.
         */
        fun schedule(
            context: Context,
            delayMs: Long,
        ) {
            val request =
                OneTimeWorkRequestBuilder<CodeCleanupWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    NAME,
                    if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                    request,
                )
            } catch (e: IllegalStateException) {
                RostyLog.w(TAG, "the passcode cleanup job could not be scheduled", e)
            }
        }
    }
}
