package dev.rovsti.platform.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.location.exactAllowed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.json.JSONArray
import org.json.JSONObject

/**
 * The armed payloads of one reminder kind as one JSON array; [read] and [write] are `SharedPreferences` in production.
 * Generic over the item type: [parse] reads one object, [idOf] and [raw] give its key and payload.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/CallReminders.kt (`ReminderStore`)
 */
class ReminderStore<T>(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val parse: (JSONObject) -> T?,
    private val idOf: (T) -> Long,
    private val raw: (T) -> JSONObject,
) {
    private fun items(): List<T> {
        val array =
            try {
                JSONArray(read(ARMED) ?: "[]")
            } catch (_: org.json.JSONException) {
                JSONArray()
            }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(parse) }
    }

    private fun save(list: List<T>) = write(ARMED, JSONArray(list.map(raw)).toString())

    @Synchronized
    fun arm(item: T) = save(items().filter { idOf(it) != idOf(item) } + item)

    /** True when the id was armed. */
    @Synchronized
    fun disarm(id: Long): Boolean {
        val all = items()
        if (all.none { idOf(it) == id }) return false
        save(all.filter { idOf(it) != id })
        return true
    }

    @Synchronized
    fun get(id: Long): T? = items().firstOrNull { idOf(it) == id }

    @Synchronized
    fun all(): List<T> = items()

    companion object {
        const val ARMED = "armed"
    }
}

/** The request code of a reminder's alarm and tap intent: what the GPUI build used, so old alarms are cancellable. */
internal fun reminderCode(id: Long): Int = (id xor (id ushr 32)).toInt()

/**
 * The alarm every reminder kind uses: inexact (`setAndAllowWhileIdle`, no permission) unless [exact] is asked for and
 * the app may schedule exact alarms (`canScheduleExactAlarms`, Android 12 and up), then `setExactAndAllowWhileIdle`. A
 * revoked exact-alarm grant (a `SecurityException`) falls back to the inexact alarm too, so a reminder is never lost.
 */
@android.annotation.SuppressLint("ScheduleExactAlarm")
internal fun setReminderAlarm(
    context: Context,
    atMs: Long,
    operation: PendingIntent,
    exact: Boolean = false,
): Boolean {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
    if (exact && alarms.exactAllowed()) {
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
            return true
        } catch (_: SecurityException) {
            // The grant went away between the check and the call: the inexact alarm below is the fallback.
        }
    }
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
    return true
}

/** The broadcast `PendingIntent` of a reminder alarm: action, package, receiver class and id extra (E3.6 contract). */
internal fun reminderBroadcast(
    context: Context,
    action: String,
    receiver: Class<*>,
    extraId: String,
    id: Long,
): PendingIntent {
    val intent = Intent(action).setPackage(context.packageName).setClass(context, receiver).putExtra(extraId, id)
    return PendingIntent.getBroadcast(
        context,
        reminderCode(id),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Arm, disarm and observe one kind of reminder. [armed] re-emits after every [arm], [disarm], [rearmAll] and after a
 * fired reminder (the receiver emits on `PlatformEvents`).
 */
interface RemindersRepository<T> {
    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(json: String): Boolean

    fun disarm(id: Long): Boolean

    /** The armed items, now and after every change. */
    fun armed(): Flow<List<T>>

    /** Re-sets every stored alarm (a reboot or update clears them); due items fire at once. */
    fun rearmAll(): Boolean
}

/**
 * A [RemindersRepository] over the three `object`s. [changeKinds] are the `PlatformEvents` kinds that mean "the armed
 * set changed" (a change made here or elsewhere, or one reminder fired): the first of them is emitted after every
 * write of this repository.
 */
internal class StoreRemindersRepository<T>(
    private val changeKinds: Set<String>,
    private val emitKind: String,
    private val arm: (String) -> Boolean,
    private val disarm: (Long) -> Boolean,
    private val load: () -> List<T>,
    private val rearm: () -> Boolean,
) : RemindersRepository<T> {
    override fun arm(json: String): Boolean = arm.invoke(json).also { changed() }

    override fun disarm(id: Long): Boolean = disarm.invoke(id).also { changed() }

    override fun armed(): Flow<List<T>> =
        PlatformEvents.events
            .filter { it.kind in changeKinds }
            .map { }
            .onStart { emit(Unit) }
            .map { load() }

    override fun rearmAll(): Boolean = rearm().also { changed() }

    private fun changed() {
        PlatformEvents.emit(emitKind, "{}")
    }
}

/** The three repositories. The boot path is `rearmAll()` on each (the app calls it at launch, ARCH 7.2). */
object ReminderRepositories {
    fun messages(context: Context): RemindersRepository<MessageReminderItem> {
        val app = context.applicationContext
        return StoreRemindersRepository(
            setOf(MessageReminders.EVENT_CHANGED, MessageReminders.EVENT_FIRED),
            MessageReminders.EVENT_CHANGED,
            { MessageReminders.arm(app, it) },
            { MessageReminders.disarm(app, it) },
            { MessageReminders.armed(app) },
            { MessageReminders.rearmAll(app) },
        )
    }

    fun calls(context: Context): RemindersRepository<ReminderItem> {
        val app = context.applicationContext
        return StoreRemindersRepository(
            setOf(CallReminders.EVENT_CHANGED, CallReminders.EVENT_FIRED),
            CallReminders.EVENT_CHANGED,
            { CallReminders.arm(app, it) },
            { CallReminders.disarm(app, it) },
            { CallReminders.armed(app) },
            { CallReminders.rearmAll(app) },
        )
    }

    fun birthdays(context: Context): RemindersRepository<BirthdayItem> {
        val app = context.applicationContext
        return StoreRemindersRepository(
            setOf(BirthdayReminders.EVENT_CHANGED, BirthdayReminders.EVENT_FIRED),
            BirthdayReminders.EVENT_CHANGED,
            { BirthdayReminders.arm(app, it) },
            { BirthdayReminders.disarm(app, it) },
            { BirthdayReminders.armed(app) },
            { BirthdayReminders.rearmAll(app) },
        )
    }

    /** Re-arms all three kinds. */
    fun rearmEverything(context: Context) {
        val app = context.applicationContext
        MessageReminders.rearmAll(app)
        CallReminders.rearmAll(app)
        BirthdayReminders.rearmAll(app)
    }
}

/** Emits a fired-event with the reminder id as `{"id": n}`. */
internal fun emitFired(
    kind: String,
    id: Long,
) {
    PlatformEvents.emit(kind, JSONObject().put("id", id).toString())
}
