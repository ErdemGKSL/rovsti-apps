package dev.rovsti.bridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * One armed message reminder: `{id, tag, message_id, message_at_ms, at_ms, notif_title, notif_body}`.
 * The tag is the notification/thread tag (`sms:<id>`, `wa:<jid>`) the tap opens.
 */
data class MessageReminderItem(
    val id: Long,
    val tag: String,
    val messageId: String,
    val messageAtMs: Long,
    val atMs: Long,
    val title: String,
    val body: String,
    val raw: JSONObject,
) {
    companion object {
        /** Pure: null for malformed JSON, a non-positive id or a blank tag. A blank message id is a chat-level reminder. */
        fun parse(json: String): MessageReminderItem? = try {
            from(JSONObject(json))
        } catch (_: org.json.JSONException) {
            null
        }

        fun from(o: JSONObject): MessageReminderItem? {
            val id = o.optLong("id", 0)
            val tag = o.optString("tag", "")
            val messageId = o.optString("message_id", "")
            if (id <= 0 || tag.isBlank()) return null
            return MessageReminderItem(
                id, tag, messageId, o.optLong("message_at_ms", 0), o.optLong("at_ms", 0),
                o.optString("notif_title", o.optString("title", tag)), o.optString("notif_body", o.optString("body", "")), o,
            )
        }
    }
}

/**
 * Message reminders: one inexact alarm per item (`setAndAllowWhileIdle`, no exact-alarm permission).
 * The receiver posts the notification cold; the tap opens the launch activity on the chat with the
 * message to glow. There is no boot receiver: the app calls [rearmAll] at launch.
 */
object MessageReminders {
    const val ACTION_FIRE = "dev.rovsti.bridge.MESSAGE_REMINDER_FIRE"
    const val EXTRA_ID = "message_reminder_id"
    const val EXTRA_MESSAGE_ID = "open_message_id"
    const val EXTRA_MESSAGE_AT = "open_message_at"
    const val CHANNEL = "message_reminders"
    private const val PREFS = "rovsti_message_reminders"
    private const val NOTIFICATION_TAG = "message-reminder"

    private fun store(context: Context): ReminderStore<MessageReminderItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReminderStore(
            { prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() },
            MessageReminderItem::from, { it.id }, { it.raw },
        )
    }

    private fun code(id: Long): Int = (id xor (id ushr 32)).toInt()

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(ACTION_FIRE).setPackage(context.packageName)
            .setClass(context, MessageReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, code(id), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun set(context: Context, item: MessageReminderItem): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, pending(context, item.id))
        return true
    }

    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(context: Context, json: String): Boolean {
        val item = MessageReminderItem.parse(json) ?: return false
        if (!set(context, item)) return false
        store(context).arm(item)
        return true
    }

    fun disarm(context: Context, id: Long): Boolean {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, id))
        return store(context).disarm(id)
    }

    /** The armed payloads as a JSON array. */
    fun armedJson(context: Context): String = JSONArray(store(context).all().map { it.raw }).toString()

    /** Re-sets every stored alarm (a reboot or update clears them); due items fire at once. */
    fun rearmAll(context: Context): Boolean {
        var ok = true
        for (item in store(context).all()) ok = set(context, item) && ok
        return ok
    }

    private fun open(context: Context, item: MessageReminderItem): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MessageNotifications.EXTRA_TAG, item.tag)
        if (item.messageId.isNotBlank()) {
            launch.putExtra(EXTRA_MESSAGE_ID, item.messageId).putExtra(EXTRA_MESSAGE_AT, item.messageAtMs)
        }
        return PendingIntent.getActivity(
            context, code(item.id), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Posts the notification, forgets the item and tells Rust. Runs on the receiver's async thread. */
    fun fire(context: Context, id: Long) {
        val store = store(context)
        val item = store.get(id) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Message reminders", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(NotificationIcon.small(context))
                .setContentTitle(item.title)
                .setContentText(item.body)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open(context, item))
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(NOTIFICATION_TAG, code(id), notification)
            } catch (_: SecurityException) {
            }
        }
        store.disarm(id)
        RovstiBridge.emit("message_reminder_fired", JSONObject().put("id", id).toString())
    }
}

/** Alarm target for [MessageReminders]; declare it in the host manifest (not exported, no boot action). */
class MessageReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MessageReminders.ACTION_FIRE) return
        val id = intent.getLongExtra(MessageReminders.EXTRA_ID, 0)
        if (id <= 0) return
        val result = goAsync()
        Thread {
            try {
                MessageReminders.fire(context.applicationContext, id)
            } finally {
                result.finish()
            }
        }.start()
    }
}
