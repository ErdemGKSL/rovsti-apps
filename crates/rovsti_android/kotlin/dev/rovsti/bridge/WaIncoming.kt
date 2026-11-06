package dev.rovsti.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import org.json.JSONObject

/**
 * Pure: the caller title for an FCM data message: `name`, else the user part of `from`
 * (`123:4@s.whatsapp.net` gives `123`), else [WaIncomingNotice.FALLBACK_TITLE].
 */
internal fun waCallerTitle(data: Map<String, String>): String {
    val name = data["name"]?.trim().orEmpty()
    val user = data["from"].orEmpty().substringBefore('@').substringBefore(':').trim()
    return name.ifEmpty { user.ifEmpty { WaIncomingNotice.FALLBACK_TITLE } }
}

/** What an FCM `kind=call` data message says about an incoming WhatsApp call. */
data class WaIncomingNotice(val callId: String, val title: String) {
    companion object {
        const val FALLBACK_TITLE = "WhatsApp call"

        /** Pure: the notice for [data], or null when it is not a call or has no `call_id`. */
        fun of(data: Map<String, String>): WaIncomingNotice? {
            if (data["kind"] != "call") return null
            val id = data["call_id"]?.takeIf { it.isNotBlank() } ?: return null
            return WaIncomingNotice(id, waCallerTitle(data))
        }
    }
}

/** What an FCM `kind=call_end` data message with `missed=1` says about a call nobody answered. */
data class WaMissedNotice(val callId: String, val title: String) {
    companion object {
        /** Pure: the notice for [data], or null when it is not a missed `call_end` or has no `call_id`. */
        fun of(data: Map<String, String>): WaMissedNotice? {
            if (data["kind"] != "call_end" || data["missed"] != "1") return null
            val id = data["call_id"]?.takeIf { it.isNotBlank() } ?: return null
            return WaMissedNotice(id, waCallerTitle(data))
        }
    }
}

/**
 * The incoming WhatsApp call notification: `CallStyle.forIncomingCall` on channel `wa_calls`, a full-screen
 * intent to the launch activity, and Answer and Decline actions. All three open the launch activity with
 * `wa_call_id` (and `wa_call_action`); `RovstiBridge.deliverWaCallAction` turns that into an event.
 */
object WaIncoming {
    const val CHANNEL = "wa_calls"
    const val MISSED_CHANNEL = "wa_missed"
    const val EXTRA_CALL_ID = "wa_call_id"
    const val EXTRA_ACTION = "wa_call_action"
    const val ANSWER = "answer"
    const val DECLINE = "decline"

    /** How long a ring notice stays: the gateway gives up on an unanswered call after 60 s (`wa::calls::RING_TIMEOUT`). */
    const val RING_TIMEOUT_MS = 60_000L
    private const val MISSED_ID_MASK = 0x5A5A5A5A

    /** Pure: the `wa_call_action` payload, or null for an unknown [action] or empty [callId]. */
    fun actionJson(callId: String, action: String): String? {
        if (callId.isEmpty() || (action != ANSWER && action != DECLINE)) return null
        return JSONObject().put("call_id", callId).put("action", action).toString()
    }

    /** Pure: a stable notification id per call. */
    fun notificationId(callId: String): Int = callId.hashCode()

    /** Pure: the missed-call notification id, distinct from [notificationId] for the same call. */
    fun missedNotificationId(callId: String): Int = notificationId(callId) xor MISSED_ID_MASK

    fun show(context: Context, callId: String, title: String): Boolean =
        show(context, WaIncomingNotice(callId, title))

    fun show(context: Context, notice: WaIncomingNotice): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return try {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "WhatsApp calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming WhatsApp calls"
                    setSound(null, null)
                },
            )
            val open = pending(context, notice.callId, null, 0)
            val answer = pending(context, notice.callId, ANSWER, 1)
            val decline = pending(context, notice.callId, DECLINE, 2)
            val caller = Person.Builder().setName(notice.title).setImportant(true).build()
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(context.applicationInfo.icon)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                // A cold process never learns that the caller hung up: do not leave an ongoing notice behind.
                .setTimeoutAfter(RING_TIMEOUT_MS)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            } else {
                builder.setContentTitle(notice.title)
                    .setContentText("Incoming WhatsApp call")
                    .addAction(Notification.Action.Builder(null as Icon?, "Decline", decline).build())
                    .addAction(Notification.Action.Builder(null as Icon?, "Answer", answer).build())
            }
            manager.notify(notificationId(notice.callId), builder.build())
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Posts the "Missed WhatsApp call" notification; tapping it opens the launch activity. */
    fun showMissed(context: Context, notice: WaMissedNotice): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return try {
            manager.createNotificationChannel(
                NotificationChannel(MISSED_CHANNEL, "Missed WhatsApp call", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "WhatsApp calls that were not answered"
                },
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open = PendingIntent.getActivity(
                context, missedNotificationId(notice.callId), launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, MISSED_CHANNEL)
                .setSmallIcon(context.applicationInfo.icon)
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setContentTitle(notice.title)
                .setContentText("Missed WhatsApp call")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            manager.notify(missedNotificationId(notice.callId), notification)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancel(context: Context, callId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val id = notificationId(callId)
        val shown = manager.activeNotifications.any { it.id == id }
        manager.cancel(id)
        return shown
    }

    private fun pending(context: Context, callId: String, action: String?, slot: Int): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch.putExtra(EXTRA_CALL_ID, callId)
        action?.let { launch.putExtra(EXTRA_ACTION, it) }
        // Distinct request codes per call and slot, or the system reuses one PendingIntent for all three.
        val code = notificationId(callId) * 3 + slot
        return PendingIntent.getActivity(
            context, code, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
