package dev.rosty.platform

/**
 * Every `SharedPreferences` file the platform layer opens. The names are the ones the GPUI apps already wrote, so an
 * update keeps the user's data (ARCH 7.1): never rename one. `PrefsNamesTest` pins the set and scans `src/main`.
 */
object PlatformPrefs {
    const val BRIDGE = "rosty_bridge"
    const val SCHEDULED_SMS = "rosty_scheduled_sms"
    const val BIRTHDAY_REMINDERS = "rosty_birthday_reminders"
    const val ALARM = "rosty_alarm"
    const val CALL_REMINDERS = "rosty_call_reminders"
    const val MESSAGE_REMINDERS = "rosty_message_reminders"
    const val PHONE_SETTINGS = "rosty_phone_settings"
    const val TRIP = "rosty_trip"

    /** New in E5.12: the unseen missed calls the digest notification lists. */
    const val MISSED_CALLS = "rosty_missed_calls"

    val ALL: Set<String> =
        setOf(
            BRIDGE,
            SCHEDULED_SMS,
            BIRTHDAY_REMINDERS,
            ALARM,
            CALL_REMINDERS,
            MESSAGE_REMINDERS,
            PHONE_SETTINGS,
            TRIP,
            MISSED_CALLS,
        )
}
