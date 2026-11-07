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

/** One armed birthday reminder: `{id, contact_id, at_ms, title, body}`. */
data class BirthdayItem(
    val id: Long,
    val contactId: Long,
    val atMs: Long,
    val title: String,
    val body: String,
    val raw: JSONObject,
) {
    companion object {
        /** Pure: null for malformed JSON, a non-positive id or a non-positive contact id. */
        fun parse(json: String): BirthdayItem? = try {
            from(JSONObject(json))
        } catch (_: org.json.JSONException) {
            null
        }

        fun from(o: JSONObject): BirthdayItem? {
            val id = o.optLong("id", 0)
            val contactId = o.optLong("contact_id", 0)
            if (id <= 0 || contactId <= 0) return null
            return BirthdayItem(id, contactId, o.optLong("at_ms", 0), o.optString("title", ""), o.optString("body", ""), o)
        }
    }
}

/**
 * Birthday reminders: one inexact alarm per item (`setAndAllowWhileIdle`, no exact-alarm permission).
 * The receiver posts the notification on the `birthdays` channel; the tap launches the app with
 * [EXTRA_CONTACT_ID], which `RovstiBridge.deliverOpenContact` turns into `open_contact`. There is no
 * boot receiver: the app calls [rearmAll] at launch.
 */
object BirthdayReminders {
    const val ACTION_FIRE = "dev.rovsti.bridge.BIRTHDAY_REMINDER_FIRE"
    const val EXTRA_ID = "birthday_reminder_id"
    const val EXTRA_CONTACT_ID = "open_contact_id"
    const val CHANNEL = "birthdays"
    private const val PREFS = "rovsti_birthday_reminders"
    private const val NOTIFICATION_TAG = "birthday-reminder"

    private fun store(context: Context): ReminderStore<BirthdayItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReminderStore(
            { prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() },
            BirthdayItem::from, { it.id }, { it.raw },
        )
    }

    private fun code(id: Long): Int = (id xor (id ushr 32)).toInt()

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(ACTION_FIRE).setPackage(context.packageName)
            .setClass(context, BirthdayReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, code(id), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun set(context: Context, item: BirthdayItem): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, pending(context, item.id))
        return true
    }

    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(context: Context, json: String): Boolean {
        val item = BirthdayItem.parse(json) ?: return false
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

    private fun open(context: Context, item: BirthdayItem): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONTACT_ID, item.contactId)
        return PendingIntent.getActivity(
            context, code(item.id), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Posts the notification and forgets the item. Runs on the receiver's async thread. */
    fun fire(context: Context, id: Long) {
        val store = store(context)
        val item = store.get(id) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Birthdays", NotificationManager.IMPORTANCE_DEFAULT),
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
    }
}

/** Alarm target for [BirthdayReminders]; declare it in the host manifest (not exported, no boot action). */
class BirthdayReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BirthdayReminders.ACTION_FIRE) return
        val id = intent.getLongExtra(BirthdayReminders.EXTRA_ID, 0)
        if (id <= 0) return
        val result = goAsync()
        Thread {
            try {
                BirthdayReminders.fire(context.applicationContext, id)
            } finally {
                result.finish()
            }
        }.start()
    }
}
