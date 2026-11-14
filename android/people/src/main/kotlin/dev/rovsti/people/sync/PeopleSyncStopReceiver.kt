package dev.rovsti.people.sync

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rovsti.core.log.RovstiLog

/**
 * The "Stop" action of the sync notification (people sync v2 S11): the same path as the settings row's Stop button
 * ([PeopleSyncHost.stop]), so the run ends `interrupted(cancelled)` with its checkpoint and Continue resumes it. Not
 * exported; each syncing app (Contacts, Phone) declares it in its manifest (`:people` keeps no component).
 */
class PeopleSyncStopReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_STOP) return
        val host = ForegroundPeopleSyncWorker.active
        RovstiLog.event(PeopleSyncRunner.TAG, "notice_stop", "running" to (host != null))
        if (host != null) {
            host.stop()
        } else {
            // No run in this process (it died with the notification still up): just take the notification away.
            context.getSystemService(NotificationManager::class.java)?.cancel(SyncNotices.NOTIFICATION_ID)
        }
    }

    companion object {
        const val ACTION_STOP = "dev.rovsti.people.sync.STOP"
    }
}
