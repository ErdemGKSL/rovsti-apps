package dev.rovsti.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Starts another app's screen (the SMS composer, the contact editor, the share sheet) for the Phone hosts. When no app
 * takes the intent, [say] gets the localized sentence (F34), as Contacts does with `contacts_no_app`: Message or Add
 * contact never does nothing without a word.
 */
class SystemStarter(
    private val context: Context,
    private val say: (String) -> Unit,
) {
    /** Starts [intent] as a new task; [opened] runs when an app took it. False (and the toast) when none did. */
    fun start(
        intent: Intent,
        opened: () -> Unit = {},
    ): Boolean {
        val started =
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                // The only app that matches is not exported to us: the same as none for the user.
                false
            }
        if (started) opened() else say(context.getString(R.string.phone_no_app))
        return started
    }
}
