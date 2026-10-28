package dev.rovsti.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.app.Person

/** Builds the call notifications. Kotlin only: nothing here touches GPUI (AD-17). */
object CallNotifications {
    const val CHANNEL_ONGOING = "ongoing"
    const val CHANNEL_INCOMING = "incoming"
    const val ONGOING_ID = 1001
    const val INCOMING_ID = 1002
    const val ACTION_END = "dev.rovsti.phone.END_CALL"
    const val ACTION_DECLINE = "dev.rovsti.phone.DECLINE_CALL"
    const val ACTION_ANSWER = "dev.rovsti.phone.ANSWER_CALL"
    const val ACTION_SHOW_INCOMING = "dev.rovsti.phone.SHOW_INCOMING"

    /** The Answer action: honoured exactly once by `MainActivity`, stale ids ignored (story 5.3). */
    const val EXTRA_ANSWER_CALL_ID = "answer_call_id"
    const val EXTRA_ANSWER_TOKEN = "answer_token"

    /**
     * Per-process secret carried by the notification's Answer intent. MainActivity is exported, so
     * without it any app could answer a ringing call by guessing the small sequential call id.
     */
    val answerToken: Long = java.security.SecureRandom().nextLong()
    const val EXTRA_CALL_ID = "call_id"

    // PendingIntents are told apart by request code, one block per kind.
    private const val REQ_SHOW = 20_000
    private const val REQ_ANSWER = 30_000
    private const val REQ_DECLINE = 40_000

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows the call in progress with an End button"
                    setShowBadge(false)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shows a ringing call with Answer and Decline"
                    setShowBadge(false)
                    // Telecom rings and vibrates (IN_CALL_SERVICE_RINGING is not set, AD-2):
                    // the channel stays silent so nothing rings twice.
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    /** The intent that brings the Incoming Call screen up: the full-screen intent and the body tap. */
    fun showIncomingIntent(context: Context, callId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_SHOW + callId,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_SHOW_INCOMING)
            .putExtra(EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * `CallStyle.forIncomingCall(person, decline, answer)`: the argument order is
     * fixed by the platform. Decline never opens the app; Answer is an activity
     * PendingIntent (a broadcast trampoline would be blocked on Android 12+) so
     * the In-Call screen follows. Nothing here needs GPUI (AD-2).
     */
    fun incoming(context: Context, call: IncomingCall, fullScreenAllowed: Boolean): Notification {
        ensureChannels(context)
        val person = Person.Builder().setName(call.title).setImportant(true).build()
        val show = showIncomingIntent(context, call.callId)
        val answer = answerIntent(context, call)
        val decline = declineIntent(context, call)
        // The platform refuses a CallStyle notification that is neither a foreground-service
        // notification nor carries a full-screen intent, and the incoming one is posted by a plain
        // notify(). Without the permission the heads-up falls back to ordinary Answer / Decline
        // actions (AD-2), which never throws.
        if (!fullScreenAllowed) return incomingHeadsUp(context, call, show, answer, decline)
        val builder = Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_stat_call))
            .setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
            .setContentIntent(show)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setFullScreenIntent(show, true)
        call.subtitle?.let { builder.setContentText(it) }
        return builder.build()
    }

    /**
     * The heads-up without a full-screen intent: a plain notification on the high-importance
     * channel with working Answer and Decline buttons. Also the fallback when the platform
     * refuses the CallStyle one.
     */
    fun incomingHeadsUp(
        context: Context,
        call: IncomingCall,
        show: PendingIntent = showIncomingIntent(context, call.callId),
        answer: PendingIntent = answerIntent(context, call),
        decline: PendingIntent = declineIntent(context, call),
    ): Notification {
        ensureChannels(context)
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_call)
        val builder = Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(icon)
            .setContentTitle(call.title)
            .setContentText(call.subtitle ?: "Incoming call")
            .setContentIntent(show)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(Notification.Action.Builder(icon, "Decline", decline).build())
            .addAction(Notification.Action.Builder(icon, "Answer", answer).build())
        return builder.build()
    }

    /** Answer is an activity PendingIntent (a broadcast trampoline would be blocked on Android 12+). */
    private fun answerIntent(context: Context, call: IncomingCall): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_ANSWER + call.callId,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_ANSWER)
            .putExtra(EXTRA_ANSWER_CALL_ID, call.callId)
            .putExtra(EXTRA_ANSWER_TOKEN, answerToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Decline never opens the app. */
    private fun declineIntent(context: Context, call: IncomingCall): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQ_DECLINE + call.callId,
        Intent(context, CallActionReceiver::class.java)
            .setAction(ACTION_DECLINE)
            .putExtra(EXTRA_CALL_ID, call.callId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** `CallStyle.forOngoingCall`: caller, chronometer and End. The body opens the app directly. */
    fun ongoing(context: Context, call: OngoingCall): Notification {
        ensureChannels(context)
        val person = Person.Builder().setName(call.title).setImportant(true).build()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, CallActionReceiver::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_stat_call))
            .setStyle(Notification.CallStyle.forOngoingCall(person, end))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (call.connectTimeMs > 0) {
            builder.setWhen(call.connectTimeMs).setShowWhen(true).setUsesChronometer(true)
        }
        // End on the notification ends every call (documented); in-app End ends the foreground one.
        val subtext = if (call.callCount > 1) "${call.callCount} calls" else null
        if (call.holding) builder.setContentText(if (subtext != null) "On hold · $subtext" else "On hold")
        else if (subtext != null) builder.setContentText(subtext)
        return builder.build()
    }
}
