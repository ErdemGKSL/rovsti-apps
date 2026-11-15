package dev.rosty.stops.offline

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import dev.rosty.platform.notify.NotificationIcon
import dev.rosty.platform.notify.NotificationKind
import dev.rosty.platform.notify.PlatformChannels
import dev.rosty.stops.MainActivity
import dev.rosty.stops.R

/** The progress fraction as a whole number of [OfflineNotices.PROGRESS_MAX] steps (pure). */
fun progressSteps(state: OfflineState): Int =
    when (state) {
        is OfflineState.Running -> (state.fraction.coerceIn(0f, 1f) * OfflineNotices.PROGRESS_MAX).toInt()
        is OfflineState.Finished -> OfflineNotices.PROGRESS_MAX
        else -> 0
    }

/**
 * The offline download's notifications (S10) on the silent [PlatformChannels.MAP_DOWNLOAD] channel: while it runs an
 * ongoing one with a determinate bar and a Cancel action ([OfflineCancelReceiver]); when it ends a short one that says
 * how it went. Both wear the Stops trip glyph, accent and glass large icon from `:platform` ([NotificationIcon]) and
 * open the app. Without POST_NOTIFICATIONS the download still runs; Android just does not show them.
 */
class OfflineNotices(
    private val context: Context,
) {
    /** The line under the title. */
    fun text(state: OfflineState): String = offlineText(context, state)

    fun build(state: OfflineState): Notification {
        PlatformChannels.ensure(context, PlatformChannels.MAP_DOWNLOAD)
        val running = state.active
        val builder =
            Notification
                .Builder(context, PlatformChannels.MAP_DOWNLOAD)
                .setContentTitle(
                    context.getString(
                        if (running) R.string.offline_notice_title else R.string.offline_notice_done_title,
                    ),
                ).setContentText(text(state))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setShowWhen(!running)
                .setLocalOnly(true)
                .setContentIntent(openIntent())
        NotificationIcon.apply(builder, context, KIND)
        if (running) {
            builder
                .setOngoing(true)
                .setProgress(PROGRESS_MAX, progressSteps(state), state == OfflineState.Starting)
                .addAction(
                    Notification.Action
                        .Builder(null, context.getString(R.string.offline_cancel), cancelIntent())
                        .build(),
                )
        } else {
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    /** What the worker hands WorkManager to run as a `dataSync` foreground service (declared in the manifest). */
    @SuppressLint("SpecifyForegroundServiceType")
    fun info(state: OfflineState): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, build(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Updates the running notification; nothing when notifications are off or not granted. */
    fun post(state: OfflineState) = notify(NOTIFICATION_ID, state)

    /** The closing notification, under its own id: WorkManager takes the running one down when the work ends. */
    fun postDone(state: OfflineState) = notify(DONE_ID, state)

    private fun notify(
        id: Int,
        state: OfflineState,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, build(state))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked meanwhile: the download goes on without a visible notification.
        }
    }

    private fun openIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CANCEL,
            Intent(context, OfflineCancelReceiver::class.java).setAction(OfflineCancelReceiver.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** "OD": the running download. */
        const val NOTIFICATION_ID = 0x4F44

        /** "OE": how it ended. */
        const val DONE_ID = 0x4F45

        const val PROGRESS_MAX = 100

        /** The Stops glyph and accent of the trip notifications. */
        val KIND = NotificationKind.TRIP

        private const val REQUEST_OPEN = 0x4F44
        private const val REQUEST_CANCEL = 0x4F45
    }
}

/** The words of [state] in today's language: the notification's line and the Settings progress row. */
fun offlineText(
    context: Context,
    state: OfflineState,
): String {
    val res = context.resources
    return when (state) {
        OfflineState.Idle,
        OfflineState.Starting,
        -> {
            context.getString(R.string.offline_starting)
        }

        is OfflineState.Running -> {
            res.getQuantityString(R.plurals.offline_progress, state.total, state.done, state.total)
        }

        is OfflineState.Finished -> {
            when {
                state.cancelled -> {
                    res.getQuantityString(R.plurals.offline_cancelled, state.total, state.saved, state.total)
                }

                state.failed > 0 -> {
                    res.getQuantityString(R.plurals.offline_failed, state.failed, state.failed, state.total)
                }

                else -> {
                    res.getQuantityString(R.plurals.offline_done, state.saved, state.saved)
                }
            }
        }

        is OfflineState.Failed -> {
            state.message.ifBlank { context.getString(R.string.offline_could_not_start) }
        }
    }
}
