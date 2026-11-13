package dev.rovsti.platform.telephony

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.PlatformPrefs
import dev.rovsti.platform.R
import dev.rovsti.platform.identity.CallerIdentity
import dev.rovsti.platform.identity.CallerLookup
import dev.rovsti.platform.identity.PhoneNumbers
import dev.rovsti.platform.notify.NotificationIcon
import dev.rovsti.platform.notify.NotificationKind
import dev.rovsti.platform.notify.NotifyPhotos
import dev.rovsti.platform.notify.PlatformChannels
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * The notifications of missed calls (E5.12). Every missed call is its own notification (the kind's missed-call icon in
 * red, the contact's photo as the large icon) with three actions: Call back and Remind me go through the app's
 * `CallActionReceiver` (no screen is needed), Message opens the messaging app (a receiver may not start an activity from
 * a notification on Android 12 and up). From the second call on they sit under one summary notification, the digest
 * ("3 missed calls", one line per caller). Opening Recents clears all of them ([clear]).
 *
 * The unseen calls are kept in the app's preferences as one JSON array so the digest survives a process restart.
 */
object MissedCallNotifications {
    const val ACTION_CALL_BACK = "dev.rovsti.phone.MISSED_CALL_BACK"
    const val ACTION_REMIND = "dev.rovsti.phone.MISSED_REMIND"
    const val EXTRA_NUMBER = "missed_number"
    const val EXTRA_NAME = "missed_name"
    const val EXTRA_NOTIFICATION_ID = "missed_notification_id"
    const val GROUP = "rovsti_missed_calls"
    const val TAG = "missed-call"
    const val SUMMARY_ID = 0x4D495353
    private const val KEEP = 20

    /** One unseen missed call. */
    data class Missed(
        val number: String,
        val name: String?,
        val atMs: Long,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PlatformPrefs.MISSED_CALLS, Context.MODE_PRIVATE)

    /** The unseen missed calls, oldest first. */
    fun unseen(context: Context): List<Missed> =
        try {
            val array = JSONArray(prefs(context).getString("unseen", "[]"))
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let {
                    Missed(
                        it.optString("number"),
                        if (it.isNull("name")) null else it.optString("name"),
                        it.optLong("at_ms"),
                    )
                }
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }

    private fun save(
        context: Context,
        list: List<Missed>,
    ) {
        val array = JSONArray()
        for (m in list) {
            array.put(JSONObject().put("number", m.number).put("name", m.name ?: JSONObject.NULL).put("at_ms", m.atMs))
        }
        prefs(context).edit().putString("unseen", array.toString()).apply()
    }

    /** The notification id of a number: the same caller twice replaces its own notification. */
    fun notificationId(number: String): Int = number.hashCode() and 0x3FFFFFFF

    /** Posts the missed call of [number] (and the digest when there is more than one). Runs off the main thread. */
    fun post(
        context: Context,
        number: String,
        atMs: Long,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!PlatformChannels.ensure(context, PlatformChannels.MISSED_CALLS)) return
        val person = NotifyPhotos.person(context, number)
        val missed = Missed(number, person.name, atMs)
        // The digest names every caller as the contacts are now (F39): a contact added since an earlier call shows.
        val lookup = CallerLookup.of(context)
        val earlier =
            unseen(context).filterNot { it.number == number }.map { m ->
                val now =
                    try {
                        lookup.identify(m.number)?.title
                    } catch (_: RuntimeException) {
                        m.name
                    }
                m.copy(name = now)
            }
        val all = (earlier + missed).takeLast(KEEP)
        save(context, all)
        val id = notificationId(number)
        try {
            manager.notify(TAG, id, single(context, missed, id, person.photo))
            if (all.size > 1) manager.notify(TAG, SUMMARY_ID, summary(context, all))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was not granted: nothing to show, the call stays in Recents.
        }
    }

    /** Takes every missed-call notification down and forgets the unseen calls (Recents was opened). */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (m in unseen(context)) manager?.cancel(TAG, notificationId(m.number))
        manager?.cancel(TAG, SUMMARY_ID)
        save(context, emptyList())
    }

    /** Takes one call's notification down (an action was used); the digest follows the remaining count. */
    fun dismiss(
        context: Context,
        number: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(TAG, notificationId(number))
        val rest = unseen(context).filterNot { it.number == number }
        save(context, rest)
        if (rest.size < 2) manager?.cancel(TAG, SUMMARY_ID)
    }

    private fun title(
        context: Context,
        m: Missed,
    ): String =
        CallerLookup.titleOf(m.number, m.name?.let { CallerIdentity(name = it) }) {
            PhoneNumbers.forReading(it, PhoneNumbers.homeRegion(context))
        } ?: context.getString(R.string.notif_missed_call)

    private fun time(
        context: Context,
        ms: Long,
    ): String = DateFormat.getTimeFormat(context).format(Date(ms))

    private fun broadcast(
        context: Context,
        action: String,
        m: Missed,
        id: Int,
        code: Int,
    ): PendingIntent {
        val receiver = checkNotNull(PlatformComponents.get().callAction) { "callAction was not installed" }
        val intent =
            Intent(context, receiver)
                .setAction(action)
                .putExtra(EXTRA_NUMBER, m.number)
                .putExtra(EXTRA_NAME, m.name)
                .putExtra(EXTRA_NOTIFICATION_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id + code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun open(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            Intent(context, PlatformComponents.get().mainActivity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun single(
        context: Context,
        m: Missed,
        id: Int,
        photo: android.graphics.drawable.Icon?,
    ): Notification {
        val sms =
            PendingIntent.getActivity(
                context,
                id + 2,
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", m.number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun action(
            label: Int,
            op: PendingIntent,
        ) = Notification.Action.Builder(null, context.getString(label), op).build()
        val builder =
            Notification
                .Builder(context, PlatformChannels.MISSED_CALLS)
                .let { NotificationIcon.apply(it, context, NotificationKind.CALL_MISSED, photo) }
                .setContentTitle(title(context, m))
                .setContentText(context.getString(R.string.notif_missed_call) + " · " + time(context, m.atMs))
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setWhen(m.atMs)
                .setShowWhen(true)
                .setGroup(GROUP)
                .setContentIntent(open(context))
                .setAutoCancel(true)
        if (m.number.isNotBlank()) {
            builder
                .addAction(action(R.string.notif_call_back, broadcast(context, ACTION_CALL_BACK, m, id, 1)))
                .addAction(action(R.string.notif_message, sms))
                .addAction(action(R.string.notif_remind_me, broadcast(context, ACTION_REMIND, m, id, 3)))
        }
        return builder.build()
    }

    private fun summary(
        context: Context,
        all: List<Missed>,
    ): Notification {
        val inbox = Notification.InboxStyle()
        for (m in all.asReversed()) inbox.addLine(title(context, m) + " · " + time(context, m.atMs))
        val newest = all.last()
        val heading = context.getString(R.string.notif_missed_calls, all.size)
        inbox.setBigContentTitle(heading)
        return Notification
            .Builder(context, PlatformChannels.MISSED_CALLS)
            .let { NotificationIcon.apply(it, context, NotificationKind.CALL_MISSED) }
            .setContentTitle(heading)
            .setContentText(context.getString(R.string.notif_missed_from, title(context, newest), all.size - 1))
            .setStyle(inbox)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(open(context))
            .setAutoCancel(true)
            .build()
    }
}
