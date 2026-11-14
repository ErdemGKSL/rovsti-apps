package dev.rosty.people.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import dev.rosty.core.model.people.SyncWire
import dev.rosty.people.R
import dev.rosty.platform.notify.NotificationIcon
import dev.rosty.platform.notify.NotificationKind
import dev.rosty.platform.notify.PlatformChannels

/**
 * The people sync's ongoing notification (people sync v2 S11) on its own low, silent channel
 * ([PlatformChannels.PEOPLE_SYNC]): the title, the stage with "x/y" and a determinate bar, the WhatsApp glyph, a tap
 * that opens [open] (Contacts' settings, or Phone) and a Stop action that reaches [PeopleSyncStopReceiver].
 *
 * Without POST_NOTIFICATIONS the foreground service still runs; Android just does not show it. Updates go through
 * [post], which is a no-op then.
 */
class SyncNotices(
    private val context: Context,
    private val open: Intent?,
) {
    /** The words of [notice] in today's language. */
    fun text(notice: SyncNotice): String =
        when (val line = notice.line) {
            SyncNotice.Line.Starting -> {
                context.getString(R.string.people_sync_notice_starting)
            }

            is SyncNotice.Line.Stage -> {
                context.getString(stageText(line.stage), line.done, line.total)
            }

            is SyncNotice.Line.Limit -> {
                context.resources.getQuantityString(R.plurals.people_sync_notice_limit, line.minutes, line.minutes)
            }
        }

    fun build(notice: SyncNotice): Notification {
        PlatformChannels.ensure(context, PlatformChannels.PEOPLE_SYNC)
        val builder =
            Notification
                .Builder(context, PlatformChannels.PEOPLE_SYNC)
                .setSmallIcon(NotificationIcon.small(context, KIND))
                .setColor(KIND.accent)
                .setContentTitle(context.getString(R.string.people_sync_notice_title))
                .setContentText(text(notice))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setProgress(notice.max, notice.done, notice.indeterminate)
                .addAction(
                    Notification.Action
                        .Builder(null, context.getString(R.string.people_sync_notice_stop), stopIntent())
                        .build(),
                )
        open?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_OPEN,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build().apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    /**
     * What the worker hands WorkManager to run as a `dataSync` foreground service. `:people` declares no component; each
     * syncing app gives WorkManager's `SystemForegroundService` the `dataSync` type in its own manifest (pinned by
     * `ContactsManifestTest` and `PhoneManifestTest`), which this library's lint cannot see.
     */
    @SuppressLint("SpecifyForegroundServiceType")
    fun info(notice: SyncNotice): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, build(notice), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Updates the shown notification; nothing when notifications are off or not granted. */
    fun post(notice: SyncNotice) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(NOTIFICATION_ID, build(notice))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked meanwhile: the sync goes on without a visible notification.
        }
    }

    private fun stopIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            Intent(context, PeopleSyncStopReceiver::class.java).setAction(PeopleSyncStopReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** One notification per app ("PY"); WorkManager takes it down when the work ends. */
        const val NOTIFICATION_ID = 0x5059

        /** The WhatsApp glyph and green of the app's other WhatsApp notifications. */
        val KIND = NotificationKind.WA_MESSAGE

        private const val REQUEST_OPEN = 0x5059
        private const val REQUEST_STOP = 0x505A

        fun stageText(stage: String): Int =
            when (stage) {
                SyncWire.STAGE_PROFILES -> R.string.people_sync_notice_profiles
                SyncWire.STAGE_PICTURES -> R.string.people_sync_notice_pictures
                else -> R.string.people_sync_notice_lookup
            }
    }
}
