package dev.rovsti.contacts.data

import android.content.Context
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.BirthdayAlarm
import dev.rovsti.core.model.contacts.ReminderRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.platform.notify.BirthdayReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TimeZone

/** The alarms of birthday reminders: what is armed, and arming and cancelling one (E4.15 C22). */
interface ReminderPort {
    /** Ids of the alarms that are armed now. */
    fun armed(): List<Long>

    fun arm(alarm: BirthdayAlarm)

    fun disarm(id: Long)

    /** Whether the clock shows 12 hours ("9 AM") or 24 ("09:00"). */
    fun hour12(): Boolean
}

/** [ReminderPort] on `BirthdayReminders` (one inexact alarm per birthday). */
class AndroidReminderPort(
    private val context: Context,
) : ReminderPort {
    override fun armed(): List<Long> = BirthdayReminders.armed(context).map { it.id }

    override fun arm(alarm: BirthdayAlarm) {
        val json =
            JSONObject()
                .put("id", alarm.id)
                .put("contact_id", alarm.contactId)
                .put("at_ms", alarm.atMs)
                .put("title", alarm.title)
                .put("body", alarm.body)
        BirthdayReminders.arm(context, json.toString())
    }

    override fun disarm(id: Long) {
        BirthdayReminders.disarm(context, id)
    }

    override fun hour12(): Boolean =
        !android.text.format.DateFormat
            .is24HourFormat(context)
}

/**
 * Keeps the armed alarms equal to what `contacts.birthday_reminders` plans for the book, the switch and the hour. It
 * runs on every change of those three; with the switch off the plan is empty, so everything armed is cancelled. The op
 * decides which alarms to arm and which to cancel; this class only carries it out.
 */
class BirthdayAlarms(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val ops: ContactsOps,
    private val port: ReminderPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneOffsetS: (Long) -> Int = { TimeZone.getDefault().getOffset(it) / MS_PER_S },
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                store.load()
                combine(
                    book.book,
                    store.prefs.map { it.birthdayReminders to it.hour() }.distinctUntilChanged(),
                ) { b, s ->
                    b to s
                }.collect { (b, setting) ->
                    val ready = b as? Book.Ready
                    when {
                        !setting.first -> cancelAll()
                        ready != null -> sync(ready, setting.second)
                    }
                }
            }
    }

    internal fun cancelAllForTest() = cancelAll()

    private fun cancelAll() {
        for (id in port.armed()) port.disarm(id)
    }

    /** One pass: plan, cancel the stale, arm the new. */
    internal suspend fun sync(
        ready: Book.Ready,
        hour: Int,
    ) {
        val now = clock()
        val request = ReminderRequest(ready.drafts, now, zoneOffsetS(now), hour, port.hour12(), port.armed())
        val answer = (ops.birthdayReminders(request) as? Outcome.Ok)?.value ?: return
        for (id in answer.disarm) port.disarm(id)
        val byId = answer.alarms.associateBy { it.id }
        for (id in answer.arm) byId[id]?.let(port::arm)
    }

    private companion object {
        const val MS_PER_S = 1000
    }
}

private fun dev.rovsti.core.model.contacts.ContactsPrefs.hour(): Int = reminderHour.coerceIn(0, 23)
