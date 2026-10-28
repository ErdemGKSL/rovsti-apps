package dev.rovsti.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives `TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION` (AD-33).
 *
 * Declared `android:enabled="false"` in the manifest, so the system never
 * delivers anything to it. Spike S9 enables it with `setComponentEnabledSetting`
 * and the story that owns voicemail fills this in (count into the Kotlin
 * mirror, `voicemail_changed`, its own "New voicemail" notification). Until
 * then it must do nothing at all.
 */
class VoicemailReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty while disabled.
    }
}
