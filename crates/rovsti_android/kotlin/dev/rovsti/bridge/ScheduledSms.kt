package dev.rovsti.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** One armed scheduled SMS: `{id, at_ms, to, body, sub_id?}` (what `sms::arm_scheduled` receives). */
data class ScheduledItem(val id: Long, val atMs: Long, val to: String, val body: String, val subId: Int?) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("id", id).put("at_ms", atMs).put("to", to).put("body", body)
        subId?.let { o.put("sub_id", it) }
        return o
    }

    companion object {
        /** Pure: null for malformed JSON, a non-positive id or an empty recipient. */
        fun parse(json: String): ScheduledItem? = try {
            from(JSONObject(json))
        } catch (_: org.json.JSONException) {
            null
        }

        fun from(o: JSONObject): ScheduledItem? {
            val id = o.optLong("id", 0)
            val to = o.optString("to", "")
            if (id <= 0 || to.isEmpty()) return null
            val sub = if (o.has("sub_id") && !o.isNull("sub_id")) o.optInt("sub_id") else null
            return ScheduledItem(id, o.optLong("at_ms", 0), to, o.optString("body", ""), sub)
        }
    }
}

/**
 * The armed-payload store and the fired list, as plain JSON so it is testable on the JVM. [read] and
 * [write] persist the two JSON arrays (SharedPreferences in production).
 */
class ScheduledStore(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    private fun load(key: String): JSONArray = try {
        JSONArray(read(key) ?: "[]")
    } catch (_: org.json.JSONException) {
        JSONArray()
    }

    private fun items(): List<ScheduledItem> =
        (0 until load(ARMED).length()).mapNotNull { ScheduledItem.from(load(ARMED).getJSONObject(it)) }

    @Synchronized
    fun arm(item: ScheduledItem) {
        val rest = items().filter { it.id != item.id } + item
        write(ARMED, JSONArray(rest.map { it.toJson() }).toString())
    }

    /** True when the id was armed. */
    @Synchronized
    fun disarm(id: Long): Boolean {
        val all = items()
        if (all.none { it.id == id }) return false
        write(ARMED, JSONArray(all.filter { it.id != id }.map { it.toJson() }).toString())
        return true
    }

    @Synchronized
    fun get(id: Long): ScheduledItem? = items().firstOrNull { it.id == id }

    @Synchronized
    fun all(): List<ScheduledItem> = items()

    @Synchronized
    fun recordFired(id: Long, ok: Boolean, atMs: Long) {
        val fired = load(FIRED)
        fired.put(JSONObject().put("id", id).put("ok", ok).put("at_ms", atMs))
        write(FIRED, fired.toString())
    }

    /** The fired list as a JSON array, then cleared. */
    @Synchronized
    fun drainFired(): String {
        val out = load(FIRED).toString()
        write(FIRED, "[]")
        return out
    }

    companion object {
        const val ARMED = "armed"
        const val FIRED = "fired"
    }
}

/**
 * Scheduled SMS (AD-MSG6): one AlarmManager alarm per item. The alarm receiver sends cold through
 * [SmsSend], so no Rust and no activity is needed. Exact alarms when `canScheduleExactAlarms()`,
 * else `setAndAllowWhileIdle` (the UI says it may be a few minutes late).
 */
object ScheduledSms {
    const val ACTION_FIRE = "dev.rovsti.bridge.SCHEDULED_SMS_FIRE"
    const val EXTRA_ID = "scheduled_id"
    private const val PREFS = "rovsti_scheduled_sms"

    private fun store(context: Context): ScheduledStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ScheduledStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() })
    }

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(ACTION_FIRE).setPackage(context.packageName)
            .setClass(context, ScheduledSmsReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, (id xor (id ushr 32)).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(context: Context, json: String): Boolean {
        val item = ScheduledItem.parse(json) ?: return false
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        store(context).arm(item)
        val op = pending(context, item.id)
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, op)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, op)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, op)
        }
        return true
    }

    fun disarm(context: Context, id: Long): Boolean {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, id))
        return store(context).disarm(id)
    }

    fun pullFired(context: Context): String = store(context).drainFired()

    /** Re-sets every stored alarm (after boot or an app update clears them); due items fire at once. */
    fun rearmAll(context: Context) {
        for (item in store(context).all()) arm(context, item.toJson().toString())
    }

    /** Sends the item now and records the outcome. Runs on the receiver's async thread. */
    fun fire(context: Context, id: Long) {
        val store = store(context)
        val item = store.get(id) ?: return
        val ok = SmsSend.sendScheduled(context, item)
        store.disarm(id)
        store.recordFired(id, ok, System.currentTimeMillis())
        RovstiBridge.emit("scheduled_fired", JSONObject().put("id", id).put("ok", ok).toString())
    }
}

/** Alarm target and boot receiver for [ScheduledSms]; declare it in the host manifest (`BOOT_COMPLETED`, [ScheduledSms.ACTION_FIRE]). */
class ScheduledSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScheduledSms.rearmAll(context)
            return
        }
        val id = intent.getLongExtra(ScheduledSms.EXTRA_ID, 0)
        if (id <= 0) return
        val result = goAsync()
        Thread {
            try {
                ScheduledSms.fire(context.applicationContext, id)
            } finally {
                result.finish()
            }
        }.start()
    }
}
