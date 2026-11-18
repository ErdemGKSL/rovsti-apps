package dev.rosty.wa.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * "Lock now" in one Rosty app locks WhatsApp in all of them (round 7a). Since a sibling whose vault is unlocked opens
 * this app's vault with no prompt ([WaLinkShare], `vault.unlock_link`), a "Lock now" that left the siblings open would
 * be undone at the next start. So the app that locks tells the others, in memory only:
 *
 * - [send] broadcasts [ACTION] to each sibling package, receivable only by apps that hold [WaLinkShare.PERMISSION]
 *   (signature);
 * - [register] listens with a receiver registered at run time, so a cold process is never started for it (it has
 *   nothing unlocked), and only a sender that holds the same signature permission is heard.
 *
 * The intent carries nothing: no key, no instance. The other lock rules (screen off, the background delay) stay per
 * app: screen off already reaches every app, and the delay is each app's own choice.
 */
object WaLockAll {
    const val ACTION = "dev.rosty.action.WA_LOCK_ALL"

    /** Tells every sibling of [own] to lock WhatsApp now. */
    fun send(
        context: Context,
        own: String = context.packageName,
    ) {
        for (sibling in WaLinkShare.siblingsOf(own)) {
            try {
                context.sendBroadcast(Intent(ACTION).setPackage(sibling), WaLinkShare.PERMISSION)
            } catch (_: RuntimeException) {
                // A sibling that is not installed or refuses: nothing of it is unlocked by this app either.
            }
        }
    }

    /** Runs [onLock] when a sibling signed like this app asks every Rosty app to lock. */
    fun register(
        context: Context,
        onLock: () -> Unit,
    ): BroadcastReceiver {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action == ACTION) onLock()
                }
            }
        context.applicationContext.registerReceiver(
            receiver,
            IntentFilter(ACTION),
            WaLinkShare.PERMISSION,
            null,
            Context.RECEIVER_EXPORTED,
        )
        return receiver
    }
}
