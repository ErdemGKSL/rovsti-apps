package dev.rovsti.platform.wa

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.rovsti.platform.R
import dev.rovsti.platform.notify.PlatformChannels

/**
 * Keeps the process allowed to use the microphone while a WhatsApp call runs with the app in the background (Android
 * 14+ stops background capture unless a foreground service of type `microphone` is running). The phone app declares
 * it as `foregroundServiceType="microphone|phoneCall"`; the type given to `startForeground` is [typesFor]'s subset.
 *
 * It only holds the foreground state and an "ongoing call" notification; the audio threads are `WaCallAudio`'s.
 * `AndroidCallAudioPump` starts it together with the audio (so after the call is connected) and stops it with it.
 */
class WaCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        PlatformChannels.ensure(this, PlatformChannels.ONGOING_CALL)
        val notification: Notification =
            Notification
                .Builder(this, PlatformChannels.ONGOING_CALL)
                .setSmallIcon(R.drawable.ic_notif_wa_call)
                .setContentTitle("WhatsApp call")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, typesFor(Build.VERSION.SDK_INT, isDialer(this)))
            } else {
                startForeground(ID, notification)
            }
        } catch (e: RuntimeException) {
            // Started from the background without an exemption, or a type the app may not use: the call still
            // works while its screen is open.
            Log.w("Rovsti", "WA call foreground service refused", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        /** The id of the ongoing notification. */
        const val ID = 4107

        /**
         * Pure: the foreground service types to claim. `microphone` always (API 30+); `phoneCall` too when the app
         * holds the dialer role, because the system refuses the type otherwise. Below API 30 there are no types.
         */
        fun typesFor(
            sdk: Int,
            dialer: Boolean,
        ): Int {
            if (sdk < Build.VERSION_CODES.R) return 0
            val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            return if (dialer) microphone or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else microphone
        }

        private fun isDialer(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            val roles = context.getSystemService(android.app.role.RoleManager::class.java) ?: return false
            return roles.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)
        }

        /** Starts the service; false when the system refuses (background start). */
        fun start(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(context, Intent(context, WaCallService::class.java))
                true
            } catch (_: RuntimeException) {
                false
            }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WaCallService::class.java))
            } catch (_: RuntimeException) {
            }
        }
    }
}
