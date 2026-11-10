package dev.rovsti.platform.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.rovsti.bridge.MessageReminderReceiver
import dev.rovsti.platform.PlatformPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * One armed message reminder: `{id, tag, message_id, message_at_ms, at_ms, notif_title, notif_body}`.
 * The tag is the notification/thread tag (`sms:<id>`, `wa:<jid>`) the tap opens.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MessageReminders.kt
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
        fun parse(json: String): MessageReminderItem? =
            try {
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
                id,
                tag,
                messageId,
                o.optLong("message_at_ms", 0),
                o.optLong("at_ms", 0),
                o.optString("notif_title", o.optString("title", tag)),
                o.optString("notif_body", o.optString("body", "")),
                o,
            )
        }
    }
}

/**
 * Message reminders: one inexact alarm per item (`setAndAllowWhileIdle`, no exact-alarm permission). The receiver posts
 * the notification cold; the tap opens the launch activity on the chat with the message to glow. There is no boot
 * receiver: the app calls [rearmAll] at launch.
 */
object MessageReminders {
    const val ACTION_FIRE = "dev.rovsti.bridge.MESSAGE_REMINDER_FIRE"
    const val EXTRA_ID = "message_reminder_id"
    const val EXTRA_MESSAGE_ID = "open_message_id"
    const val EXTRA_MESSAGE_AT = "open_message_at"
    const val EVENT_FIRED = "message_reminder_fired"
    const val EVENT_CHANGED = "message_reminders_changed"
    private const val NOTIFICATION_TAG = "message-reminder"

    private fun store(context: Context): ReminderStore<MessageReminderItem> {
        val prefs = context.getSharedPreferences(PlatformPrefs.MESSAGE_REMINDERS, Context.MODE_PRIVATE)
        return ReminderStore(
            { prefs.getString(it, null) },
            { k, v -> prefs.edit().putString(k, v).apply() },
            MessageReminderItem::from,
            { it.id },
            { it.raw },
        )
    }

    private fun pending(
        context: Context,
        id: Long,
    ): PendingIntent = reminderBroadcast(context, ACTION_FIRE, MessageReminderReceiver::class.java, EXTRA_ID, id)

    private fun set(
        context: Context,
        item: MessageReminderItem,
    ): Boolean = setReminderAlarm(context, item.atMs, pending(context, item.id))

    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(
        context: Context,
        json: String,
    ): Boolean {
        val item = MessageReminderItem.parse(json) ?: return false
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
    fun armed(context: Context): List<MessageReminderItem> = store(context).all()

    /** The armed payloads as a JSON array. */
    fun armedJson(context: Context): String = JSONArray(armed(context).map { it.raw }).toString()

    /** Re-sets every stored alarm (a reboot or update clears them); due items fire at once. */
    fun rearmAll(context: Context): Boolean {
        var ok = true
        for (item in store(context).all()) ok = set(context, item) && ok
        return ok
    }

    private fun open(
        context: Context,
        item: MessageReminderItem,
    ): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MessageNotifications.EXTRA_TAG, item.tag)
        if (item.messageId.isNotBlank()) {
            launch.putExtra(EXTRA_MESSAGE_ID, item.messageId).putExtra(EXTRA_MESSAGE_AT, item.messageAtMs)
        }
        return PendingIntent.getActivity(
            context,
            reminderCode(item.id),
            launch,
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
        if (manager != null && PlatformChannels.ensure(context, PlatformChannels.MESSAGE_REMINDERS)) {
            val notification =
                Notification
                    .Builder(context, PlatformChannels.MESSAGE_REMINDERS)
                    .let { NotificationIcon.apply(it, context, NotificationKind.REMINDER) }
                    .setContentTitle(item.title)
                    .setContentText(item.body)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(open(context, item))
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
