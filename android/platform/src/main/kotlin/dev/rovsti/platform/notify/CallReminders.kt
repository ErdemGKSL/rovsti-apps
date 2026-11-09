package dev.rovsti.platform.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.rovsti.bridge.CallReminderReceiver
import dev.rovsti.platform.PlatformPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * One armed call-back reminder: `{id, number, at_ms, title, body, ...}` (`reminder::arm_json`).
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/CallReminders.kt
 */
data class ReminderItem(
    val id: Long,
    val number: String,
    val atMs: Long,
    val title: String,
    val body: String,
    val raw: JSONObject,
) {
    companion object {
        /** Pure: null for malformed JSON, a non-positive id or an empty number. */
        fun parse(json: String): ReminderItem? =
            try {
                from(JSONObject(json))
            } catch (_: org.json.JSONException) {
                null
            }

        fun from(o: JSONObject): ReminderItem? {
            val id = o.optLong("id", 0)
            val number = o.optString("number", "")
            if (id <= 0 || number.isEmpty()) return null
            return ReminderItem(
                id,
                number,
                o.optLong("at_ms", 0),
                o.optString("title", number),
                o.optString("body", ""),
                o,
            )
        }
    }
}

/**
 * Call-back reminders: one inexact alarm per item (`setAndAllowWhileIdle`, so no exact-alarm permission). The receiver
 * posts the notification cold; the tap and the "Call back" action open this app's own dialer with the number
 * (`ACTION_DIAL tel:`), and nothing is dialled without a tap. There is no boot receiver: the app calls [rearmAll] at
 * launch.
 */
object CallReminders {
    const val ACTION_FIRE = "dev.rovsti.bridge.CALL_REMINDER_FIRE"
    const val EXTRA_ID = "reminder_id"
    const val EVENT_FIRED = "reminder_fired"
    const val EVENT_CHANGED = "reminders_changed"
    private const val NOTIFICATION_TAG = "call-reminder"

    private fun store(context: Context): ReminderStore<ReminderItem> {
        val prefs = context.getSharedPreferences(PlatformPrefs.CALL_REMINDERS, Context.MODE_PRIVATE)
        return ReminderStore(
            { prefs.getString(it, null) },
            { k, v -> prefs.edit().putString(k, v).apply() },
            ReminderItem::from,
            { it.id },
            { it.raw },
        )
    }

    private fun pending(
        context: Context,
        id: Long,
    ): PendingIntent = reminderBroadcast(context, ACTION_FIRE, CallReminderReceiver::class.java, EXTRA_ID, id)

    private fun set(
        context: Context,
        item: ReminderItem,
    ): Boolean = setReminderAlarm(context, item.atMs, pending(context, item.id))

    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(
        context: Context,
        json: String,
    ): Boolean {
        val item = ReminderItem.parse(json) ?: return false
        if (!set(context, item)) return false
        store(context).arm(item)
        return true
    }

    fun disarm(
        context: Context,
        id: Long,
    ): Boolean {
        context.getSystemService(android.app.AlarmManager::class.java)?.cancel(pending(context, id))
        return store(context).disarm(id)
    }

    /** The armed items. */
    fun armed(context: Context): List<ReminderItem> = store(context).all()

    /** The armed payloads as a JSON array. */
    fun armedJson(context: Context): String = JSONArray(armed(context).map { it.raw }).toString()

    /** Re-sets every stored alarm (a reboot or update clears them); due items fire at once. */
    fun rearmAll(context: Context): Boolean {
        var ok = true
        for (item in store(context).all()) ok = set(context, item) && ok
        return ok
    }

    private fun dial(
        context: Context,
        item: ReminderItem,
        code: Int,
    ): PendingIntent {
        val intent =
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", item.number, null))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            code + reminderCode(item.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Posts the notification, forgets the item and tells the app. Runs on the receiver's async thread. */
    fun fire(
        context: Context,
        id: Long,
    ) {
        val store = store(context)
        val item = store.get(id) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null && PlatformChannels.ensure(context, PlatformChannels.CALL_REMINDERS)) {
            val notification =
                Notification
                    .Builder(context, PlatformChannels.CALL_REMINDERS)
                    .setSmallIcon(NotificationIcon.small(context))
                    .setContentTitle(item.title)
                    .setContentText(item.body)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(dial(context, item, 0))
                    .addAction(Notification.Action.Builder(null, "Call back", dial(context, item, 1)).build())
                    .setAutoCancel(true)
                    .build()
            try {
                manager.notify(NOTIFICATION_TAG, reminderCode(id), notification)
            } catch (_: SecurityException) {
            }
        }
        store.disarm(id)
        emitFired(EVENT_FIRED, id)
    }
}
