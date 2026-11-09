package dev.rovsti.platform.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.rovsti.bridge.ScheduledSmsReceiver
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.PlatformPrefs
import org.json.JSONArray
import org.json.JSONObject

/** One armed scheduled SMS: `{id, at_ms, to, body, sub_id?}` (what `sms::arm_scheduled` receives). */
data class ScheduledItem(
    val id: Long,
    val atMs: Long,
    val to: String,
    val body: String,
    val subId: Int?,
) {
    fun toJson(): JSONObject {
        val o =
            JSONObject()
                .put("id", id)
                .put("at_ms", atMs)
                .put("to", to)
                .put("body", body)
        subId?.let { o.put("sub_id", it) }
        return o
    }

    companion object {
        /** Pure: null for malformed JSON, a non-positive id or an empty recipient. */
        fun parse(json: String): ScheduledItem? =
            try {
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
 * The armed-payload store and the fired list, as plain JSON so it is testable on the JVM. [read] and [write] persist
 * the two JSON arrays (SharedPreferences in production).
 */
class ScheduledStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    private fun load(key: String): JSONArray =
        try {
            JSONArray(read(key) ?: "[]")
        } catch (_: org.json.JSONException) {
            JSONArray()
        }

    private fun items(): List<ScheduledItem> {
        val armed = load(ARMED)
        return (0 until armed.length()).mapNotNull { ScheduledItem.from(armed.getJSONObject(it)) }
    }

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
    fun recordFired(
        id: Long,
        ok: Boolean,
        atMs: Long,
    ) {
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

/** How an alarm was set (AD-MSG6): the UI tells the user an [Inexact] one may be a few minutes late. */
enum class ArmMode { Exact, Inexact }

/**
 * Scheduled SMS (AD-MSG6): one AlarmManager alarm per item. The alarm receiver sends cold through [SmsSend], so no
 * Rust and no activity is needed. Exact alarms when `canScheduleExactAlarms()`, else `setAndAllowWhileIdle` (a window
 * the system may stretch); the [ArmMode] says which one was used.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/ScheduledSms.kt
 */
object ScheduledSms {
    const val ACTION_FIRE = "dev.rovsti.bridge.SCHEDULED_SMS_FIRE"
    const val EXTRA_ID = "scheduled_id"

    /** The `PlatformEvents` kind a fired item emits. */
    const val EVENT_FIRED = "scheduled_fired"

    private fun store(context: Context): ScheduledStore {
        val prefs = context.getSharedPreferences(PlatformPrefs.SCHEDULED_SMS, Context.MODE_PRIVATE)
        return ScheduledStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() })
    }

    /** Same request code and extras as the GPUI build, so an alarm set before an update is the one cancelled after it. */
    fun requestCode(id: Long): Int = (id xor (id ushr 32)).toInt()

    private fun pending(
        context: Context,
        id: Long,
    ): PendingIntent {
        val intent =
            Intent(ACTION_FIRE)
                .setPackage(context.packageName)
                .setClass(context, ScheduledSmsReceiver::class.java)
                .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            requestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stores the payload and sets the alarm. Null for a malformed item. */
    fun arm(
        context: Context,
        json: String,
    ): ArmMode? = ScheduledItem.parse(json)?.let { arm(context, it) }

    fun arm(
        context: Context,
        item: ScheduledItem,
    ): ArmMode? {
        if (item.id <= 0 || item.to.isEmpty()) return null
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return null
        store(context).arm(item)
        val op = pending(context, item.id)
        return try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, op)
                ArmMode.Exact
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, op)
                ArmMode.Inexact
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMs, op)
            ArmMode.Inexact
        }
    }

    fun disarm(
        context: Context,
        id: Long,
    ): Boolean {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, id))
        return store(context).disarm(id)
    }

    fun pullFired(context: Context): String = store(context).drainFired()

    /** The items still armed. */
    fun armed(context: Context): List<ScheduledItem> = store(context).all()

    /** Re-sets every stored alarm (after boot or an app update clears them); due items fire at once. */
    fun rearmAll(context: Context) {
        for (item in store(context).all()) arm(context, item)
    }

    /** Sends the item now and records the outcome. Runs on the receiver's async thread. */
    fun fire(
        context: Context,
        id: Long,
    ) {
        val store = store(context)
        val item = store.get(id) ?: return
        val ok = SmsSend.sendScheduled(context, item)
        val now = SmsClock.nowMillis()
        store.disarm(id)
        store.recordFired(id, ok, now)
        PlatformEvents.emit(
            EVENT_FIRED,
            JSONObject()
                .put("id", id)
                .put("ok", ok)
                .put("at_ms", now)
                .toString(),
        )
    }
}
