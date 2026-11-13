package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/CallNotifications.kt (ARCH 7.3).

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.notify.NotificationIcon
import dev.rovsti.platform.notify.NotificationKind
import dev.rovsti.platform.notify.PlatformChannels

/** Builds the call notifications. Kotlin only: nothing here touches GPUI (AD-17). */
@SuppressLint("MissingPermission") // the permissions are declared by the app manifests
object CallNotifications {
    const val CHANNEL_ONGOING = PlatformChannels.ONGOING_CALL
    const val CHANNEL_INCOMING = PlatformChannels.INCOMING_CALL
    const val ONGOING_ID = 1001
    const val INCOMING_ID = 1002
    const val ACTION_END = "dev.rovsti.phone.END_CALL"
    const val ACTION_DECLINE = "dev.rovsti.phone.DECLINE_CALL"
    const val ACTION_ANSWER = "dev.rovsti.phone.ANSWER_CALL"
    const val ACTION_SHOW_INCOMING = "dev.rovsti.phone.SHOW_INCOMING"

    /** The Answer action: honoured exactly once by the app's activity, stale ids ignored (story 5.3). */
    const val EXTRA_ANSWER_CALL_ID = "answer_call_id"
    const val EXTRA_ANSWER_TOKEN = "answer_token"

    /** The Message action: opens the Incoming screen with the reply sheet already up (story 18.7). */
    const val EXTRA_REPLY_CALL_ID = "reply_sheet_call_id"
    const val ACTION_MESSAGE = "dev.rovsti.phone.MESSAGE_CALL"

    /**
     * Per-process secret carried by the notification's Answer intent. The activity is exported, so
     * without it any app could answer a ringing call by guessing the small sequential call id.
     */
    val answerToken: Long = java.security.SecureRandom().nextLong()
    const val EXTRA_CALL_ID = "call_id"

    // PendingIntents are told apart by request code, one block per kind.
    private const val REQ_SHOW = 20_000
    private const val REQ_ANSWER = 30_000
    private const val REQ_DECLINE = 40_000
    private const val REQ_MESSAGE = 50_000

    /** The app's action receiver for Decline and End (`PlatformComponents.callAction`); Phone installs it. */
    private fun callActionReceiver(): Class<out android.content.BroadcastReceiver> =
        checkNotNull(PlatformComponents.get().callAction) { "PlatformComponents.callAction was not installed" }

    fun ensureChannels(context: Context) {
        PlatformChannels.ensure(context, CHANNEL_ONGOING)
        PlatformChannels.ensure(context, CHANNEL_INCOMING)
    }

    /** The intent that brings the Incoming Call screen up: the full-screen intent and the body tap. */
    fun showIncomingIntent(
        context: Context,
        callId: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_SHOW + callId,
            Intent(context, PlatformComponents.get().mainActivity)
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
    fun incoming(
        context: Context,
        call: IncomingCall,
        fullScreenAllowed: Boolean,
        /** The caller's contact photo (F39); null shows the platform's initials. */
        photo: Icon? = null,
    ): Notification {
        ensureChannels(context)
        val person =
            Person
                .Builder()
                .setName(call.title)
                .setIcon(photo)
                .setImportant(true)
                .build()
        val show = showIncomingIntent(context, call.callId)
        val answer = answerIntent(context, call)
        val decline = declineIntent(context, call)
        // The platform refuses a CallStyle notification that is neither a foreground-service
        // notification nor carries a full-screen intent, and the incoming one is posted by a plain
        // notify(). Without the permission the heads-up falls back to ordinary Answer / Decline
        // actions (AD-2), which never throws.
        if (!fullScreenAllowed) return incomingHeadsUp(context, call, show, answer, decline, photo)
        val builder =
            Notification
                .Builder(context, CHANNEL_INCOMING)
                .let { NotificationIcon.apply(it, context, NotificationKind.CALL_INCOMING) }
                .setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
                .setContentIntent(show)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setFullScreenIntent(show, true)
        call.subtitle?.let { builder.setContentText(it) }
        if (call.canMessage) {
            builder.addAction(
                Notification.Action
                    .Builder(
                        NotificationIcon.small(context, NotificationKind.MESSAGE),
                        "Message",
                        messageIntent(context, call),
                    ).build(),
            )
        }
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
        photo: Icon? = null,
    ): Notification {
        ensureChannels(context)
        val icon = NotificationIcon.small(context, NotificationKind.CALL_INCOMING)
        val builder =
            Notification
                .Builder(context, CHANNEL_INCOMING)
                .let { NotificationIcon.apply(it, context, NotificationKind.CALL_INCOMING) }
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
        photo?.let { builder.setLargeIcon(it) }
        if (call.canMessage) {
            builder.addAction(
                Notification.Action.Builder(icon, "Message", messageIntent(context, call)).build(),
            )
        }
        return builder.build()
    }

    /** Answer is an activity PendingIntent (a broadcast trampoline would be blocked on Android 12+). */
    private fun answerIntent(
        context: Context,
        call: IncomingCall,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_ANSWER + call.callId,
            Intent(context, PlatformComponents.get().mainActivity)
                .setAction(ACTION_ANSWER)
                .putExtra(EXTRA_ANSWER_CALL_ID, call.callId)
                .putExtra(EXTRA_ANSWER_TOKEN, answerToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Message is an activity PendingIntent, never a broadcast: it must raise the app (story 18.7). */
    private fun messageIntent(
        context: Context,
        call: IncomingCall,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_MESSAGE + call.callId,
            Intent(context, PlatformComponents.get().mainActivity)
                .setAction(ACTION_MESSAGE)
                .putExtra(EXTRA_REPLY_CALL_ID, call.callId)
                .putExtra(EXTRA_ANSWER_TOKEN, answerToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Decline never opens the app. */
    private fun declineIntent(
        context: Context,
        call: IncomingCall,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_DECLINE + call.callId,
            Intent(context, callActionReceiver())
                .setAction(ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, call.callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** `CallStyle.forOngoingCall`: caller, chronometer and End. The body opens the app directly. */
    fun ongoing(
        context: Context,
        call: OngoingCall,
        /** The caller's contact photo (F39); null shows the platform's initials. */
        photo: Icon? = null,
    ): Notification {
        ensureChannels(context)
        val person =
            Person
                .Builder()
                .setName(call.title)
                .setIcon(photo)
                .setImportant(true)
                .build()
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, PlatformComponents.get().mainActivity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val end =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, callActionReceiver()).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            Notification
                .Builder(context, CHANNEL_ONGOING)
                .let { NotificationIcon.apply(it, context, NotificationKind.CALL_ONGOING) }
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
        if (call.holding) {
            builder.setContentText(if (subtext != null) "On hold · $subtext" else "On hold")
        } else if (subtext != null) {
            builder.setContentText(subtext)
        }
        return builder.build()
    }
}
