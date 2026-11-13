package dev.rovsti.platform.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.annotation.StringRes
import dev.rovsti.platform.R

/**
 * Every notification channel the platform layer posts on. The ids and importances are the GPUI build's: a user's
 * per-channel settings survive the update only if they never change, so `NotificationChannelsTest` pins the table.
 * Callers never write a channel id as a string; they use these constants and [ensure]. Names and descriptions are
 * string resources (en + tr, F32), applied again on every [ensure] and by [relabel] when the language changes.
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

    /** One channel: id, user-visible name and description (resources), importance and the GPUI build's settings. */
    class Spec internal constructor(
        val id: String,
        @StringRes val name: Int,
        val importance: Int,
        @StringRes val description: Int? = null,
        internal val configure: NotificationChannel.() -> Unit = {},
    ) {
        internal fun create(context: Context): NotificationChannel =
            NotificationChannel(id, context.getString(name), importance).apply {
                configure()
                this@Spec.description?.let { description = context.getString(it) }
            }

        /** Gives [channel] (the system's copy, with the user's own settings) this build's words in today's language. */
        internal fun relabel(
            context: Context,
            channel: NotificationChannel,
        ): Boolean {
            val words = context.getString(name)
            val about = this.description?.let(context::getString)
            if (channel.name?.toString() == words && channel.description == about) return false
            channel.name = words
            channel.description = about
            return true
        }
    }

    /** Built when the alarm channel is created, so reading [all] touches no Android class (plain JVM tests do). */
    private fun alarmAttrs(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** The full table, in declaration order. */
    val all: List<Spec> =
        listOf(
            Spec(
                MESSAGES,
                R.string.channel_messages,
                NotificationManager.IMPORTANCE_HIGH,
                R.string.channel_messages_desc,
            ),
            Spec(BIRTHDAYS, R.string.channel_birthdays, NotificationManager.IMPORTANCE_DEFAULT),
            Spec(CALL_REMINDERS, R.string.channel_reminders, NotificationManager.IMPORTANCE_DEFAULT),
            Spec(MESSAGE_REMINDERS, R.string.channel_message_reminders, NotificationManager.IMPORTANCE_DEFAULT),
            Spec(
                WA_CALLS,
                R.string.channel_wa_calls,
                NotificationManager.IMPORTANCE_HIGH,
                R.string.channel_wa_calls_desc,
            ) {
                setSound(null, null)
            },
            Spec(
                WA_MISSED,
                R.string.channel_wa_missed,
                NotificationManager.IMPORTANCE_DEFAULT,
                R.string.channel_wa_missed_desc,
            ),
            Spec(TRIP_PROBLEM, R.string.channel_trip_problem, NotificationManager.IMPORTANCE_HIGH),
            Spec(TRIP, R.string.channel_trip, NotificationManager.IMPORTANCE_LOW, R.string.channel_trip_desc) {
                setShowBadge(false)
            },
            Spec(
                TRIP_ALARM,
                R.string.channel_trip_alarm,
                NotificationManager.IMPORTANCE_HIGH,
                R.string.channel_trip_alarm_desc,
            ) {
                // Alarm usage: Do Not Disturb's "alarms" exception applies. The loop itself is AlarmSound's.
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAttrs())
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
            },
            Spec(
                ONGOING_CALL,
                R.string.channel_ongoing,
                NotificationManager.IMPORTANCE_LOW,
                R.string.channel_ongoing_desc,
            ) {
                setShowBadge(false)
            },
            Spec(
                INCOMING_CALL,
                R.string.channel_incoming,
                NotificationManager.IMPORTANCE_HIGH,
                R.string.channel_incoming_desc,
            ) {
                setShowBadge(false)
                // Telecom rings and vibrates (IN_CALL_SERVICE_RINGING is not set, AD-2): the channel stays silent so
                // nothing rings twice.
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            Spec(
                MISSED_CALLS,
                R.string.notif_missed_channel,
                NotificationManager.IMPORTANCE_DEFAULT,
                R.string.notif_missed_channel_desc,
            ),
        )

    private val byId = all.associateBy { it.id }

    fun spec(id: String): Spec = checkNotNull(byId[id]) { "unknown channel id $id" }

    /**
     * Creates channel [id] when the system has none. An existing channel keeps the user's own settings (sound,
     * importance) and only gets its name and description again in today's language: a channel made before the
     * update, or in another language, would keep its old words otherwise. False when there is no notification manager.
     */
    fun ensure(
        context: Context,
        id: String,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val spec = spec(id)
        val existing = manager.getNotificationChannel(id)
        when {
            existing == null -> manager.createNotificationChannel(spec.create(context))

            // createNotificationChannel on an existing id updates its name and description and nothing the user set.
            spec.relabel(context, existing) -> manager.createNotificationChannel(existing)
        }
        return true
    }

    /** Gives every channel that exists today's words (a start-up and a language change call it). */
    fun relabel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (channel in manager.notificationChannels) {
            val spec = byId[channel.id] ?: continue
            if (spec.relabel(context, channel)) manager.createNotificationChannel(channel)
        }
    }

    /**
     * Keeps the channel words in the phone's language for the life of the process: relabels now (off the main thread,
     * a start for a ringing call must not wait on it) and after each language change, which reaches the application's
     * component callbacks while the process lives.
     */
    fun followLocale(app: Application) {
        Thread({ relabel(app) }, "channel-words").start()
        app.registerComponentCallbacks(
            object : ComponentCallbacks {
                private var locales = app.resources.configuration.locales

                override fun onConfigurationChanged(newConfig: Configuration) {
                    if (newConfig.locales == locales) return
                    locales = newConfig.locales
                    relabel(app)
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            },
        )
    }
}
