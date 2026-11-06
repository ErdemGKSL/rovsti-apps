package dev.rovsti.bridge

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** One armed call-back reminder: `{id, number, at_ms, title, body, ...}` (`reminder::arm_json`). */
data class ReminderItem(val id: Long, val number: String, val atMs: Long, val title: String, val body: String, val raw: JSONObject) {
    companion object {
        /** Pure: null for malformed JSON, a non-positive id or an empty number. */
        fun parse(json: String): ReminderItem? = try {
            from(JSONObject(json))
        } catch (_: org.json.JSONException) {
            null
        }

        fun from(o: JSONObject): ReminderItem? {
            val id = o.optLong("id", 0)
            val number = o.optString("number", "")
            if (id <= 0 || number.isEmpty()) return null
            return ReminderItem(id, number, o.optLong("at_ms", 0), o.optString("title", number), o.optString("body", ""), o)
        }
    }
}

/** The armed payloads as one JSON array; [read] and [write] are SharedPreferences in production. */
class ReminderStore(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    private fun items(): List<ReminderItem> {
        val array = try {
            JSONArray(read(ARMED) ?: "[]")
        } catch (_: org.json.JSONException) {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(ReminderItem::from) }
    }

    private fun save(list: List<ReminderItem>) = write(ARMED, JSONArray(list.map { it.raw }).toString())

    @Synchronized
    fun arm(item: ReminderItem) = save(items().filter { it.id != item.id } + item)

    /** True when the id was armed. */
    @Synchronized
    fun disarm(id: Long): Boolean {
        val all = items()
        if (all.none { it.id == id }) return false
        save(all.filter { it.id != id })
        return true
    }

    @Synchronized
    fun get(id: Long): ReminderItem? = items().firstOrNull { it.id == id }

    @Synchronized
    fun all(): List<ReminderItem> = items()

    companion object {
        const val ARMED = "armed"
    }
}

/**
 * Call-back reminders: one inexact alarm per item (`setAndAllowWhileIdle`, so no exact-alarm
 * permission). The receiver posts the notification cold; the tap and the "Call back" action open this
 * app's own dialer with the number (`ACTION_DIAL tel:`), and nothing is dialled without a tap.
 * There is no boot receiver: the app calls [rearmAll] at launch.
 */
object CallReminders {
    const val ACTION_FIRE = "dev.rovsti.bridge.CALL_REMINDER_FIRE"
    const val EXTRA_ID = "reminder_id"
    const val CHANNEL = "reminders"
    private const val PREFS = "rovsti_call_reminders"

    private fun store(context: Context): ReminderStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReminderStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() })
    }

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(ACTION_FIRE).setPackage(context.packageName)
            .setClass(context, CallReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, (id xor (id ushr 32)).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun set(context: Context, item: ReminderItem): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, pending(context, item.id))
        return true
    }

    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(context: Context, json: String): Boolean {
        val item = ReminderItem.parse(json) ?: return false
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

    private fun dial(context: Context, item: ReminderItem, code: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", item.number, null))
            .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, code + (item.id xor (item.id ushr 32)).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Posts the notification, forgets the item and tells Rust. Runs on the receiver's async thread. */
    fun fire(context: Context, id: Long) {
        val store = store(context)
        val item = store.get(id) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Call reminders", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val tap = dial(context, item, 0)
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(NotificationIcon.small(context))
                .setContentTitle(item.title)
                .setContentText(item.body)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(tap)
                .addAction(Notification.Action.Builder(null, "Call back", dial(context, item, 1)).build())
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(NOTIFICATION_TAG, (id xor (id ushr 32)).toInt(), notification)
            } catch (_: SecurityException) {
            }
        }
        store.disarm(id)
        RovstiBridge.emit("reminder_fired", JSONObject().put("id", id).toString())
    }

    private const val NOTIFICATION_TAG = "call-reminder"
}

/** Alarm target for [CallReminders]; declare it in the host manifest (not exported, no boot action). */
class CallReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CallReminders.ACTION_FIRE) return
        val id = intent.getLongExtra(CallReminders.EXTRA_ID, 0)
        if (id <= 0) return
        val result = goAsync()
        Thread {
            try {
                CallReminders.fire(context.applicationContext, id)
            } finally {
                result.finish()
            }
        }.start()
    }
}
