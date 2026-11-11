package dev.rovsti.platform.wa

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.R
import dev.rovsti.platform.notify.NotificationIcon
import dev.rovsti.platform.notify.NotificationKind
import dev.rovsti.platform.notify.PlatformChannels

/** The WhatsApp call the ongoing notification shows: its id, the caller's title and when it connected (0: not yet). */
data class WaOngoingCall(
    val callId: String,
    val title: String,
    val connectedMs: Long = 0L,
)

/**
 * Keeps the process allowed to use the microphone while a WhatsApp call runs with the app in the background (Android
 * 14+ stops background capture unless a foreground service of type `microphone` is running). The phone app declares
 * it as `foregroundServiceType="microphone|phoneCall"`; the type given to `startForeground` is [typesFor]'s subset.
 *
 * It only holds the foreground state and the "ongoing call" notification; the audio threads are `WaCallAudio`'s.
 * `AndroidCallAudioPump` starts it together with the audio (so after the call is connected) and stops it with it.
 * The notification is `CallStyle.forOngoingCall` with Hang up (like a cellular call's) once the app said which call
 * runs ([describe]); its body opens the In-Call screen of that call.
 */
class WaCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!showForeground(ongoing)) stopSelf()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    /** `startForeground` with the notification for [call]; false when the system refuses. */
    private fun showForeground(call: WaOngoingCall?): Boolean =
        try {
            val notification = notification(this, call)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, typesFor(Build.VERSION.SDK_INT, isDialer(this)))
            } else {
                startForeground(ID, notification)
            }
            true
        } catch (e: RuntimeException) {
            // Started from the background without an exemption, or a type the app may not use: the call still
            // works while its screen is open.
            Log.w("Rovsti", "WA call foreground service refused", e)
            false
        }

    companion object {
        /** The id of the ongoing notification. */
        const val ID = 4107

        /** The broadcast action of Hang up (to `PlatformComponents.waCallAction`), with `WaIncoming.EXTRA_CALL_ID`. */
        const val ACTION_HANG_UP = "dev.rovsti.wa.HANG_UP"

        /** On the body's activity intent: the WhatsApp call whose In-Call screen to open. */
        const val EXTRA_OPEN_CALL = "wa_open_call"

        private const val REQ_OPEN = 41_070
        private const val REQ_HANG_UP = 41_071

        @Volatile private var ongoing: WaOngoingCall? = null

        @Volatile private var running: WaCallService? = null

        /**
         * The app tells which WhatsApp call runs (null: none). A running service shows it at once; a service started
         * later shows it from its first notification.
         */
        fun describe(call: WaOngoingCall?) {
            if (call == ongoing) return
            ongoing = call
            if (call == null) return
            Handler(Looper.getMainLooper()).post { running?.showForeground(call) }
        }

        /**
         * The notification: `CallStyle.forOngoingCall` with Hang up when [call] is known and the app installed its
         * action receiver, else a plain ongoing notice. The body opens the app on the call.
         */
        fun notification(
            context: Context,
            call: WaOngoingCall?,
        ): Notification {
            PlatformChannels.ensure(context, PlatformChannels.ONGOING_CALL)
            val title = call?.title?.takeIf { it.isNotBlank() && it != WaIncomingNotice.FALLBACK_TITLE }
            val builder =
                Notification
                    .Builder(context, PlatformChannels.ONGOING_CALL)
                    .let { NotificationIcon.apply(it, context, NotificationKind.WA_CALL) }
                    .setContentTitle(title ?: context.getString(R.string.wa_call_title))
                    .setContentText(context.getString(R.string.wa_call_ongoing))
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    .setContentIntent(openIntent(context, call?.callId))
            if (call != null && call.connectedMs > 0) {
                builder.setWhen(call.connectedMs).setShowWhen(true).setUsesChronometer(true)
            }
            val hangUp = call?.let { hangUpIntent(context, it.callId) }
            if (call != null && hangUp != null) {
                val person =
                    Person
                        .Builder()
                        .setName(title ?: context.getString(R.string.wa_call_title))
                        .setImportant(true)
                        .build()
                builder.setStyle(Notification.CallStyle.forOngoingCall(person, hangUp))
            }
            return builder.build()
        }

        private fun openIntent(
            context: Context,
            callId: String?,
        ): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            callId?.let { launch.putExtra(EXTRA_OPEN_CALL, it) }
            return PendingIntent.getActivity(
                context,
                REQ_OPEN,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun hangUpIntent(
            context: Context,
            callId: String,
        ): PendingIntent? {
            val receiver = PlatformComponents.getOrNull()?.waCallAction ?: return null
            return PendingIntent.getBroadcast(
                context,
                REQ_HANG_UP,
                Intent(context, receiver).setAction(ACTION_HANG_UP).putExtra(WaIncoming.EXTRA_CALL_ID, callId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

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
