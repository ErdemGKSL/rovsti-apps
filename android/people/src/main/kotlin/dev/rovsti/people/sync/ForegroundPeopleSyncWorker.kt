package dev.rovsti.people.sync

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.rovsti.core.log.LogLevel
import dev.rovsti.core.log.RovstiLog
import dev.rovsti.core.model.people.SyncWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * The people sync as a foreground worker (people sync v2 S11), shared by Contacts and Phone. Every start goes through
 * it: Sync Now, Continue, the app coming to the foreground, the periodic work and its continuation.
 *
 * The worker first asks to run as a `dataSync` foreground service ([setForeground], ongoing notification
 * [SyncNotices]). Foreground, it runs the whole run without a time budget and waits out a short rate limit itself
 * ([ForegroundSyncPlan]); it ends at a long limit, leaving a delayed continuation. If Android refuses the foreground
 * service (a start from the background on Android 12+: `ForegroundServiceStartNotAllowedException`), it falls back to
 * the 8-minute slices of S6 and continues later, as before.
 *
 * Stopping: the Stop button and the notification's Stop cancel the slice through [PeopleSyncRunner.cancel]; the run
 * keeps its checkpoint (`interrupted(cancelled)`) and nothing is scheduled. When the system stops the worker
 * (Android 15's dataSync time budget, `Service.onTimeout`, which WorkManager turns into a stop; a lost constraint), the
 * slice ends `interrupted(slice_budget)` and a continuation is enqueued: never a crash, never a lost run.
 *
 * The app's subclass names its sync ([host]), what the notification opens ([openIntent]) and how it continues
 * ([continueLater]). WorkManager creates it by class name, so the subclasses keep theirs (R8 keeps the constructor).
 */
abstract class ForegroundPeopleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /** The app's sync; null when there is none to run (no container, or Phone with Contacts installed). */
    protected abstract suspend fun host(): PeopleSyncHost?

    /** What a tap on the notification opens. */
    protected abstract fun openIntent(): Intent

    /** Enqueues the one-time continuation after [delayMs]. */
    protected abstract fun continueLater(delayMs: Long)

    private val notices by lazy { SyncNotices(applicationContext, openIntent()) }

    /** The worker's own wait for a limit between two slices: the notification counts it down. */
    private val limitUntil = MutableStateFlow<Long?>(null)

    /** Only asked when WorkManager runs expedited work as a foreground service itself. */
    override suspend fun getForegroundInfo(): ForegroundInfo = notices.info(SyncNotice.STARTING)

    override suspend fun doWork(): Result {
        val host = host() ?: return Result.failure()
        val start = inputData.getString(KEY_START) ?: SyncWire.START_AUTO
        val now = host.runner.status.value
        if (now.active && !now.live) {
            // A run is under way in this process (another start of the same sync): its worker carries it.
            RovstiLog.event(PeopleSyncRunner.TAG, "worker_skipped", "reason" to "already_running", "start" to start)
            return Result.success()
        }
        val foreground = promote()
        active = host
        try {
            return drive(host, start, foreground)
        } finally {
            if (active === host) active = null
        }
    }

    /** Asks to run as a foreground service; false (and logged) when Android refuses. */
    private suspend fun promote(): Boolean =
        try {
            setForeground(notices.info(SyncNotice.STARTING))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (a start from the background) is an IllegalStateException.
            refused(e)
        } catch (e: SecurityException) {
            refused(e)
        }

    private fun refused(e: RuntimeException): Boolean {
        RovstiLog.event(
            LogLevel.Warn,
            PeopleSyncRunner.TAG,
            "foreground_refused",
            "error" to e.javaClass.simpleName,
            "fallback_slice_ms" to PeopleSyncRunner.WORKER_SLICE_MS,
        )
        return false
    }

    private suspend fun drive(
        host: PeopleSyncHost,
        first: String,
        foreground: Boolean,
    ): Result {
        var start = first
        var waits = 0
        while (true) {
            val seen = host.runner.stops.value
            val result =
                try {
                    withNotices(host, foreground) {
                        host.runSlice(start, if (foreground) null else PeopleSyncRunner.WORKER_SLICE_MS)
                    }
                } catch (e: CancellationException) {
                    return stopped(host, byUser = host.runner.stops.value != seen, e)
                }
            val next =
                ForegroundSyncPlan.afterSlice(
                    result,
                    host.now(),
                    foreground,
                    waits,
                    liveActive = result == SliceResult.AlreadyRunning && host.runner.status.value.live,
                )
            RovstiLog.event(
                PeopleSyncRunner.TAG,
                "worker",
                "result" to result.javaClass.simpleName,
                "foreground" to foreground,
                "next" to next.javaClass.simpleName,
                "ms" to
                    when (next) {
                        is ForegroundSyncPlan.Next.Wait -> next.ms
                        is ForegroundSyncPlan.Next.Continue -> next.delayMs
                        else -> null
                    },
            )
            when (next) {
                is ForegroundSyncPlan.Next.Wait -> {
                    waits++
                    val limit = result is SliceResult.Waiting || result is SliceResult.Refused
                    val waitSeen = host.runner.stops.value
                    val stoppedByUser =
                        try {
                            withNotices(host, foreground) { waitOrStop(host, next.ms, waitSeen, limit) }
                        } catch (e: CancellationException) {
                            return stopped(host, byUser = host.runner.stops.value != waitSeen, e)
                        }
                    if (stoppedByUser) {
                        // Stop during a limit's wait: no slice holds the run, so it is interrupted here.
                        host.runner.interruptStored(SyncWire.CANCELLED)
                        RovstiLog.event(PeopleSyncRunner.TAG, "worker_stopped", "by" to "user", "during" to "wait")
                        return Result.success()
                    }
                    start = SyncWire.START_AUTO
                }

                is ForegroundSyncPlan.Next.Continue -> {
                    continueLater(next.delayMs)
                    return Result.success()
                }

                ForegroundSyncPlan.Next.Retry -> {
                    return Result.retry()
                }

                ForegroundSyncPlan.Next.End -> {
                    return Result.success()
                }
            }
        }
    }

    /** The run was cancelled: by the user (no continuation) or by the system (continue later; no rethrow, no crash). */
    private suspend fun stopped(
        host: PeopleSyncHost,
        byUser: Boolean,
        e: CancellationException,
    ): Result {
        val bySystem = isStopped || !currentCoroutineContext().isActive
        if (!byUser && !bySystem) throw e
        val next = ForegroundSyncPlan.afterStop(byUser)
        RovstiLog.event(
            PeopleSyncRunner.TAG,
            "worker_stopped",
            "by" to if (byUser) "user" else "system",
            "stop_reason" to if (isStopped) stopReason else null,
        )
        // Enqueueing is not suspending: it runs even though this coroutine is cancelled.
        if (next is ForegroundSyncPlan.Next.Continue) continueLater(next.delayMs)
        return Result.success()
    }

    /** Waits [ms] on the sync's clock; true when the user pressed Stop meanwhile. */
    private suspend fun waitOrStop(
        host: PeopleSyncHost,
        ms: Long,
        seen: Long,
        limit: Boolean,
    ): Boolean {
        if (limit) limitUntil.value = host.now() + ms
        try {
            return coroutineScope {
                val waited = async { host.pause(ms) }
                val stop = async { host.runner.stops.first { it != seen } }
                val stopped =
                    select {
                        waited.onAwait { false }
                        stop.onAwait { true }
                    }
                waited.cancel()
                stop.cancel()
                stopped
            }
        } finally {
            limitUntil.value = null
        }
    }

    /** Runs [block] while the notification follows the runner (foreground only), at most one update per [UPDATE_MS]. */
    private suspend fun <T> withNotices(
        host: PeopleSyncHost,
        foreground: Boolean,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            val updates =
                if (foreground) {
                    launch {
                        combine(host.runner.status, limitUntil, ticks()) { state, until, _ ->
                            SyncNotice.of(state, host.now(), until)
                        }.distinctUntilChanged()
                            .conflate()
                            .collect {
                                notices.post(it)
                                delay(UPDATE_MS)
                            }
                    }
                } else {
                    null
                }
            try {
                block()
            } finally {
                updates?.cancel()
            }
        }

    /** A limit's minutes count down while nothing else changes. */
    private fun ticks() =
        flow {
            while (true) {
                emit(Unit)
                delay(TICK_MS)
            }
        }

    companion object {
        /** The input key of the start (`SyncWire.START_*`); missing means `auto`. */
        const val KEY_START = "start"

        /** The notification is updated at most this often. */
        const val UPDATE_MS = 2_000L

        private const val TICK_MS = 15_000L

        /** The sync of the worker that runs now in this process: the notification's Stop reaches it. */
        @Volatile var active: PeopleSyncHost? = null
            internal set
    }
}
