package dev.rosty.platform.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.rosty.bridge.BirthdayReminderReceiver
import dev.rosty.platform.PlatformPrefs
import dev.rosty.platform.R
import dev.rosty.platform.contacts.Shortcuts
import org.json.JSONArray
import org.json.JSONObject

/**
 * One armed birthday reminder: `{id, contact_id, at_ms, title, body}`.
 *
 * Ported from crates/rosty_android/kotlin/dev/rosty/bridge/BirthdayReminders.kt
 */
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
        fun parse(json: String): BirthdayItem? =
            try {
                from(JSONObject(json))
            } catch (_: org.json.JSONException) {
                null
            }

        fun from(o: JSONObject): BirthdayItem? {
            val id = o.optLong("id", 0)
            val contactId = o.optLong("contact_id", 0)
            if (id <= 0 || contactId <= 0) return null
            return BirthdayItem(
                id,
                contactId,
                o.optLong("at_ms", 0),
                o.optString("title", ""),
                o.optString("body", ""),
                o,
            )
        }
    }
}

/**
 * Birthday reminders: one inexact alarm per item (`setAndAllowWhileIdle`, no exact-alarm permission). The receiver posts
 * the notification on the `birthdays` channel; the tap launches the app with [EXTRA_CONTACT_ID], which the host turns
 * into `open_contact`. There is no boot receiver: the app calls [rearmAll] at launch.
 */
object BirthdayReminders {
    const val ACTION_FIRE = "dev.rosty.bridge.BIRTHDAY_REMINDER_FIRE"
    const val EXTRA_ID = "birthday_reminder_id"
    const val EXTRA_CONTACT_ID = Shortcuts.EXTRA_CONTACT_ID

    /** Boolean extra of the "Make a Card" action: open the contact with the Card Studio sheet already up. */
    const val EXTRA_MAKE_CARD = "open_card_studio"
    const val EVENT_FIRED = "birthday_reminder_fired"
    const val EVENT_CHANGED = "birthday_reminders_changed"
    private const val NOTIFICATION_TAG = "birthday-reminder"

    private fun store(context: Context): ReminderStore<BirthdayItem> {
        val prefs = context.getSharedPreferences(PlatformPrefs.BIRTHDAY_REMINDERS, Context.MODE_PRIVATE)
        return ReminderStore(
            { prefs.getString(it, null) },
            { k, v -> prefs.edit().putString(k, v).apply() },
            BirthdayItem::from,
            { it.id },
            { it.raw },
        )
    }

    private fun pending(
        context: Context,
        id: Long,
    ): PendingIntent = reminderBroadcast(context, ACTION_FIRE, BirthdayReminderReceiver::class.java, EXTRA_ID, id)

    private fun set(
        context: Context,
        item: BirthdayItem,
    ): Boolean = setReminderAlarm(context, item.atMs, pending(context, item.id))

    /** Stores the payload and sets the alarm. False for a malformed item. */
    fun arm(
        context: Context,
        json: String,
    ): Boolean {
        val item = BirthdayItem.parse(json) ?: return false
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
    fun armed(context: Context): List<BirthdayItem> = store(context).all()

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
        item: BirthdayItem,
        makeCard: Boolean = false,
    ): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONTACT_ID, item.contactId)
        if (makeCard) launch.putExtra(EXTRA_MAKE_CARD, true)
        return PendingIntent.getActivity(
            context,
            // A second code for the action: the two PendingIntents differ only in an extra, which does not count.
            reminderCode(item.id) * 31 + (if (makeCard) 1 else 0),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The "Make a Card" action: it opens the contact with the Card Studio sheet open, with the birthday icon. */
    private fun makeCardAction(
        context: Context,
        item: BirthdayItem,
    ): Notification.Action =
        Notification.Action
            .Builder(
                Icon.createWithResource(context, R.drawable.ic_notif_birthday),
                context.getString(R.string.notif_make_card),
                open(context, item, makeCard = true),
            ).build()

    /** Posts the notification and forgets the item. Runs on the receiver's async thread. */
    fun fire(
        context: Context,
        id: Long,
    ) {
        val store = store(context)
        val item = store.get(id) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null && PlatformChannels.ensure(context, PlatformChannels.BIRTHDAYS)) {
            val notification =
                Notification
                    .Builder(context, PlatformChannels.BIRTHDAYS)
                    .let { NotificationIcon.apply(it, context, NotificationKind.BIRTHDAY) }
                    .setContentTitle(item.title)
                    .setContentText(item.body)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(open(context, item))
                    .addAction(makeCardAction(context, item))
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
