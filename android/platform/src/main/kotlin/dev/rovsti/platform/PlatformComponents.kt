package dev.rovsti.platform

import android.app.Activity
import android.content.BroadcastReceiver

/**
 * The app-owned classes `:platform` must start or point a `PendingIntent` at. The library never names a
 * `dev.rovsti.<app>.*` class; the app passes them once from its `Application` via [PlatformComponents.install].
 */
data class PlatformComponents(
    /** The app's launcher activity (notification taps, reminders). */
    val mainActivity: Class<out Activity>,
    /** The call-notification action receiver (Phone only). */
    val callAction: Class<out BroadcastReceiver>? = null,
    /**
     * The WhatsApp call-notification action receiver (Answer, Decline, Hang up; Phone only). Without it the actions
     * fall back to activity intents, which the system runs only after the phone is unlocked.
     */
    val waCallAction: Class<out BroadcastReceiver>? = null,
) {
    companion object {
        @Volatile private var current: PlatformComponents? = null

        fun install(components: PlatformComponents) {
            current = components
        }

        /** The installed set; fails loudly when the app forgot [install]. */
        fun get(): PlatformComponents = checkNotNull(current) { "PlatformComponents.install was not called" }

        fun getOrNull(): PlatformComponents? = current
    }
}
