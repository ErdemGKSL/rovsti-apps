package dev.rosty.messages.wa

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rosty.core.log.RostyLog
import dev.rosty.messages.MessagesContainer
import dev.rosty.shell.HasContainer
import java.util.concurrent.TimeUnit

/**
 * The WhatsApp outbox job (E6.4s): a one-time WorkManager job with a CONNECTED constraint. It runs when the phone has
 * a network again, also after the app was killed or the phone restarted (the queue is in the store's database), and at
 * the time the store names for the next retry. It fetches what the sync plan owes and drains the outbox; when a row
 * still waits it schedules itself again. There is no periodic work and no polling.
 */
class WaOutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? HasContainer<*>)?.container as? MessagesContainer ?: return Result.failure()
        WaOutboxWork.started()
        try {
            container.waOffline.runFromWork()
        } finally {
            WaOutboxWork.finished()
        }
        return Result.success()
    }
}

/** Schedules [WaOutboxWorker]; the [WaWake] of the real container. */
class WaOutboxWork(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : WaWake {
    override fun at(delayMs: Long) {
        val due = now() + delayMs
        synchronized(lock) {
            // A job that runs no later than this one is already waiting.
            val pending = pendingAt
            if (pending != null && pending <= due) return
            pendingAt = due
        }
        val request =
            OneTimeWorkRequestBuilder<WaOutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
        // Replacing the running job would cancel it mid-send: a wake asked while it runs is appended instead.
        val policy = if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        } catch (e: IllegalStateException) {
            // No WorkManager in this process (a test application): the next trigger drains.
            synchronized(lock) { pendingAt = null }
            RostyLog.w(TAG, "the outbox job could not be scheduled", e)
        }
    }

    companion object {
        const val NAME = "wa-outbox"
        private const val TAG = "wa-outbox"
        private val lock = Any()
        private var pendingAt: Long? = null

        @Volatile private var running = false

        internal fun started() {
            synchronized(lock) { pendingAt = null }
            running = true
        }

        internal fun finished() {
            running = false
        }
    }
}
