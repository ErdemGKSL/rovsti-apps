package dev.rovsti.platform.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * Every notification channel the platform layer posts on. The ids and importances are the GPUI build's: a user's
 * per-channel settings survive the update only if they never change, so `NotificationChannelsTest` pins the table.
 * Callers never write a channel id as a string; they use these constants and [ensure].
 */
object PlatformChannels {
    const val MESSAGES = "messages"
    const val BIRTHDAYS = "birthdays"
    const val CALL_REMINDERS = "reminders"
    const val MESSAGE_REMINDERS = "message_reminders"
    const val WA_CALLS = "wa_calls"
    const val WA_MISSED = "wa_missed"
    const val TRIP_PROBLEM = "trip_problem"
    const val TRIP = "trip"
    const val TRIP_ALARM = "trip_alarm_v2"
    const val ONGOING_CALL = "ongoing"
    const val INCOMING_CALL = "incoming"
    const val MISSED_CALLS = "missed_calls"

    /** The channel the destination alarm used before `trip_alarm_v2`; `TripAlarm` deletes it. */
    const val TRIP_ALARM_OLD = "trip_alarm"

    /** One channel: id, user-visible name, importance and the extra settings the GPUI build gave it. */
    class Spec internal constructor(
        val id: String,
        val name: String,
        val importance: Int,
        internal val configure: NotificationChannel.() -> Unit = {},
    ) {
        internal fun create(): NotificationChannel = NotificationChannel(id, name, importance).apply(configure)
    }

    private val alarmAttrs: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** The full table, in declaration order. */
    val all: List<Spec> =
        listOf(
            Spec(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH) { description = "New messages" },
            Spec(BIRTHDAYS, "Birthdays", NotificationManager.IMPORTANCE_DEFAULT),
            Spec(CALL_REMINDERS, "Call reminders", NotificationManager.IMPORTANCE_DEFAULT),
            Spec(MESSAGE_REMINDERS, "Message reminders", NotificationManager.IMPORTANCE_DEFAULT),
            Spec(WA_CALLS, "WhatsApp calls", NotificationManager.IMPORTANCE_HIGH) {
                description = "Incoming WhatsApp calls"
                setSound(null, null)
            },
            Spec(WA_MISSED, "Missed WhatsApp call", NotificationManager.IMPORTANCE_DEFAULT) {
                description = "WhatsApp calls that were not answered"
            },
            Spec(TRIP_PROBLEM, "Trip paused", NotificationManager.IMPORTANCE_HIGH),
            Spec(TRIP, "Trip in progress", NotificationManager.IMPORTANCE_LOW) {
                description = "Shown while Stops follows your trip"
                setShowBadge(false)
            },
            Spec(TRIP_ALARM, "Destination alarm", NotificationManager.IMPORTANCE_HIGH) {
                description = "Rings when you are about to arrive"
                // Alarm usage: Do Not Disturb's "alarms" exception applies. The loop itself is AlarmSound's.
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAttrs)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
            },
            Spec(ONGOING_CALL, "Ongoing call", NotificationManager.IMPORTANCE_LOW) {
                description = "Shows the call in progress with an End button"
                setShowBadge(false)
            },
            Spec(INCOMING_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH) {
                description = "Shows a ringing call with Answer and Decline"
                setShowBadge(false)
                // Telecom rings and vibrates (IN_CALL_SERVICE_RINGING is not set, AD-2): the channel stays silent so
                // nothing rings twice.
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            Spec(MISSED_CALLS, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT) {
                description = "Calls that were not answered"
            },
        )

    private val byId = all.associateBy { it.id }

    fun spec(id: String): Spec = checkNotNull(byId[id]) { "unknown channel id $id" }

    /**
     * Creates channel [id] when the system has none. An existing channel is left alone, so the user's own settings
     * (sound, importance) are never reset. False when there is no notification manager.
     */
    fun ensure(
        context: Context,
        id: String,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(spec(id).create())
        return true
    }
}
