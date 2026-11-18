package dev.rosty.platform.wa

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.PhoneNumberUtils
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.WaIds
import dev.rosty.platform.PlatformComponents
import dev.rosty.platform.R
import dev.rosty.platform.identity.PhoneNumbers
import dev.rosty.platform.notify.NotificationIcon
import dev.rosty.platform.notify.NotificationKind
import dev.rosty.platform.notify.PlatformChannels
import org.json.JSONObject

/**
 * Pure: the caller's number for an FCM data message, `+<digits>` from a phone JID in `sender`
 * (`905551234567:4@s.whatsapp.net`); for a `@lid` sender the number [lidNumber] knows (the app's own identity table,
 * WA-R2); null for a LID nobody mapped, a group or nothing. Gateways before the rename sent `from`, which FCM reserves
 * (it refused those pushes); it is still read.
 */
internal fun waCallerNumber(
    data: Map<String, String>,
    lidNumber: (String) -> String? = { null },
): String? {
    val sender = data["sender"] ?: data["from"]
    return WaIds.number(sender) ?: sender?.takeIf { WaIds.isLid(it) }?.let(lidNumber)
}

/**
 * Pure: the caller title for an FCM data message (F28), as the app names a WhatsApp caller: the device contact that
 * owns the number ([contactName], resolved on the phone, so a cold process woken by the push names the caller too),
 * else the WhatsApp name the gateway sent (`name`), else the `+` number, else [WaIncomingNotice.FALLBACK_TITLE].
 */
internal fun waCallerTitle(
    data: Map<String, String>,
    contactName: (String) -> String? = { null },
    lidNumber: (String) -> String? = { null },
): String {
    val number = waCallerNumber(data, lidNumber)
    number
        ?.let(contactName)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    val name = data["name"]?.trim().orEmpty()
    return name.ifEmpty { number ?: WaIncomingNotice.FALLBACK_TITLE }
}

/** What an FCM `kind=call` data message says about an incoming WhatsApp call. */
data class WaIncomingNotice(
    val callId: String,
    val title: String,
) {
    companion object {
        /** The title when the push names nobody; the notifications show `R.string.wa_call_title` in its place. */
        const val FALLBACK_TITLE = "WhatsApp call"

        /** Pure: the notice for [data], or null when it is not a call or has no `call_id`; see [waCallerTitle]. */
        fun of(
            data: Map<String, String>,
            contactName: (String) -> String? = { null },
            lidNumber: (String) -> String? = { null },
        ): WaIncomingNotice? {
            if (data["kind"] != "call") return null
            val id = data["call_id"]?.takeIf { it.isNotBlank() } ?: return null
            return WaIncomingNotice(id, waCallerTitle(data, contactName, lidNumber))
        }

        /**
         * Pure: the notice of a call while the app's WhatsApp vault is locked (`plans/wa-vault.md` 13.3): the push's
         * `call_id` only, titled [FALLBACK_TITLE] ("WhatsApp call"); the caller's name and number are not shown.
         */
        fun contentFree(data: Map<String, String>): WaIncomingNotice? {
            if (data["kind"] != "call") return null
            val id = data["call_id"]?.takeIf { it.isNotBlank() } ?: return null
            return WaIncomingNotice(id, FALLBACK_TITLE)
        }
    }
}

/** What an FCM `kind=call_end` data message with `missed=1` says about a call nobody answered. */
data class WaMissedNotice(
    val callId: String,
    val title: String,
) {
    companion object {
        /** Pure: the notice for [data], or null when it is not a missed `call_end` or has no `call_id`. */
        fun of(
            data: Map<String, String>,
            contactName: (String) -> String? = { null },
            lidNumber: (String) -> String? = { null },
        ): WaMissedNotice? {
            if (data["kind"] != "call_end" || data["missed"] != "1") return null
            val id = data["call_id"]?.takeIf { it.isNotBlank() } ?: return null
            return WaMissedNotice(id, waCallerTitle(data, contactName, lidNumber))
        }

        /** Pure: [of] while the vault is locked: "WhatsApp call", "Missed call", nobody named. */
        fun contentFree(data: Map<String, String>): WaMissedNotice? =
            of(data)?.copy(title = WaIncomingNotice.FALLBACK_TITLE)
    }
}

/**
 * The incoming WhatsApp call notification: `CallStyle.forIncomingCall` on channel `wa_calls`, a full-screen
 * intent to the launch activity, and Answer and Decline actions. The full-screen and body intents open the launch
 * activity with `wa_call_id`. Answer and Decline are broadcasts to the app's receiver (`PlatformComponents.waCallAction`)
 * so they work on the lock screen without unlocking (the system unlocks first for an activity action); the receiver
 * hands the intent to [takeAction] and, for Answer, calls [openAnswered]. Without a receiver they open the activity,
 * which hands the intent to [takeAction] itself.
 *
 * Showing the notification starts the ring ([WaRinger]); [cancel] stops it.
 *
 * Ported from crates/rosty_android/kotlin/dev/rosty/bridge/WaIncoming.kt
 */
object WaIncoming {
    const val CHANNEL = PlatformChannels.WA_CALLS
    const val MISSED_CHANNEL = PlatformChannels.WA_MISSED
    const val EXTRA_CALL_ID = "wa_call_id"
    const val EXTRA_ACTION = "wa_call_action"
    const val ANSWER = "answer"
    const val DECLINE = "decline"

    /** The broadcast action of Answer and Decline (to `PlatformComponents.waCallAction`). */
    const val ACTION_CALL = "dev.rosty.wa.CALL_ACTION"

    /** On the activity intent [openAnswered] starts: the call was answered on the notification, show it over the keyguard. */
    const val EXTRA_ANSWERED = "wa_call_answered"

    /** On the locked-call activity's intent: the caller's title as the ringing notice shows it (round 7b). */
    const val EXTRA_TITLE = "wa_call_title"

    /** How long the "Answering…" notice of [openAnswered] stays at most. */
    private const val ANSWERING_TIMEOUT_MS = 15_000L
    private const val ANSWERING_ID_MASK = 0x3C3C3C3C

    /**
     * The calls whose ring is over here (answered, declined, ended): a late or repeated `call` push for one of them
     * must not post the ringing notification again (it would ring and pop the call screen over an answered call).
     */
    private val over = LinkedHashSet<String>()
    private const val OVER_MEMORY = 32
    private const val TAG = "Rosty"

    /** True when the ring of [callId] was already dealt with in this process. */
    fun isOver(callId: String): Boolean = synchronized(over) { callId in over }

    private fun markOver(callId: String) {
        synchronized(over) {
            over.remove(callId)
            over.add(callId)
            if (over.size > OVER_MEMORY) over.remove(over.first())
        }
        overListeners.forEach { listener ->
            try {
                listener(callId)
            } catch (_: RuntimeException) {
            }
        }
    }

    /** Told when a ring is over here (the locked-call activity closes then). */
    private val overListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

    fun addOverListener(listener: (String) -> Unit) {
        overListeners.add(listener)
    }

    fun removeOverListener(listener: (String) -> Unit) {
        overListeners.remove(listener)
    }

    /** How long a ring notice stays: the gateway gives up on an unanswered call after 60 s (`wa::calls::RING_TIMEOUT`). */
    const val RING_TIMEOUT_MS = 60_000L
    private const val MISSED_ID_MASK = 0x5A5A5A5A

    /** Pure: the `wa_call_action` payload, or null for an unknown [action] or empty [callId]. */
    fun actionJson(
        callId: String,
        action: String,
    ): String? {
        if (callId.isEmpty() || (action != ANSWER && action != DECLINE)) return null
        return JSONObject().put("call_id", callId).put("action", action).toString()
    }

    /** Pure: a stable notification id per call. */
    fun notificationId(callId: String): Int = callId.hashCode()

    /** Pure: the missed-call notification id, distinct from [notificationId] for the same call. */
    fun missedNotificationId(callId: String): Int = notificationId(callId) xor MISSED_ID_MASK

    /** Pure: the id of the "Answering…" notice of [openAnswered], distinct from the other two. */
    fun answeringNotificationId(callId: String): Int = notificationId(callId) xor ANSWERING_ID_MASK

    /** The caller's title, with the generic one in the phone's language and a bare number formatted for reading. */
    internal fun displayTitle(
        context: Context,
        title: String,
    ): String =
        when {
            title == WaIncomingNotice.FALLBACK_TITLE -> context.getString(R.string.wa_call_title)
            PhoneNumbers.e164(title, PhoneNumbers.homeRegion(context)) == title -> pretty(context, title)
            else -> title
        }

    private fun pretty(
        context: Context,
        number: String,
    ): String =
        try {
            PhoneNumberUtils.formatNumber(number, PhoneNumbers.homeRegion(context)) ?: number
        } catch (_: RuntimeException) {
            number
        }

    fun show(
        context: Context,
        callId: String,
        title: String,
    ): Boolean = show(context, WaIncomingNotice(callId, title))

    /**
     * Posts the ringing notification of [notice] and starts the ring. [locked]: the app's WhatsApp vault is locked
     * (`plans/wa-vault.md` 13.3, round 7b): the notice names the caller only as [waLockedCallerTitle] allows (a device
     * contact or what the sealed push said, never a lookup in the vault), Decline only stops the ring here (no gateway
     * request while locked: the caller hears it ring until the gateway's 60 s timeout). With the app's locked-call
     * activity (`PlatformComponents.waLockedCall`, Phone) the full-screen intent, the tap and Answer open it over the
     * lock screen: it shows the caller and Answer and Decline only, and Answer asks for the screen unlock, then the
     * app opens its vault (no prompt from an unlocked sibling, else the biometric) and answers. Without it Answer opens
     * the app and there is no full-screen intent.
     */
    fun show(
        context: Context,
        notice: WaIncomingNotice,
        locked: Boolean = false,
    ): Boolean {
        if (isOver(notice.callId)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return try {
            if (!manager.canUseFullScreenIntent()) {
                // Android 14+: without "full screen notifications" the call is only a heads-up, never over the lock screen.
                RostyLog.w(
                    TAG,
                    "WA call: full-screen intents are not allowed for this app; the call screen cannot open itself",
                )
            }
            PlatformChannels.ensure(context, CHANNEL)
            val lockedScreen = if (locked) PlatformComponents.getOrNull()?.waLockedCall else null
            val open =
                if (lockedScreen != null) {
                    lockedPending(context, lockedScreen, notice, null, 0)
                } else {
                    pending(context, notice.callId, null, 0)
                }
            val answer =
                when {
                    lockedScreen != null -> lockedPending(context, lockedScreen, notice, ANSWER, 1)
                    locked -> pending(context, notice.callId, ANSWER, 1)
                    else -> actionPending(context, notice.callId, ANSWER)
                }
            val decline = actionPending(context, notice.callId, DECLINE)
            val caller =
                Person
                    .Builder()
                    .setName(displayTitle(context, notice.title))
                    .setImportant(true)
                    .build()
            val builder =
                Notification
                    .Builder(context, CHANNEL)
                    .let { NotificationIcon.apply(it, context, NotificationKind.WA_CALL) }
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    // A cold process never learns that the caller hung up: do not leave an ongoing notice behind.
                    .setTimeoutAfter(RING_TIMEOUT_MS)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(open)
                    .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            if (locked) {
                // At most the caller's name, as any phone call shows it: it may show in full on the lock screen.
                builder.setVisibility(Notification.VISIBILITY_PUBLIC)
                if (lockedScreen != null) builder.setFullScreenIntent(open, true)
            } else {
                builder.setFullScreenIntent(open, true)
            }
            // The FCM push and the live event both post it: the call screen opens for the first one only.
            val already = manager.activeNotifications.any { it.id == notificationId(notice.callId) }
            manager.notify(notificationId(notice.callId), builder.build())
            // The channel is silent: the ring is ours, so every way the ring ends can stop it. Once per call.
            WaRinger.start(context, notice.callId)
            if (!already && !locked) openWhileInUse(context, notice.callId)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Pure: whether the app should open the call screen itself. The system shows the full-screen intent only while the
     * screen is off or locked; on an unlocked phone in use it becomes a heads-up, so the app starts the screen itself.
     */
    fun shouldOpenDirectly(
        interactive: Boolean,
        locked: Boolean,
    ): Boolean = interactive && !locked

    /**
     * On an unlocked phone in use, starts the launch activity with the ringing call (see [shouldOpenDirectly]). Android
     * lets an app do that only with an exemption (on HyperOS: "Display pop-up windows while running in the
     * background"); without one the start is refused quietly and the heads-up stays the way in.
     */
    private fun openWhileInUse(
        context: Context,
        callId: String,
    ) {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        if (!shouldOpenDirectly(interactive, locked)) return
        try {
            context.startActivity(launchIntent(context, callId, null))
        } catch (e: RuntimeException) {
            RostyLog.w(TAG, "WA call: could not open the call screen", e)
        }
    }

    /** Posts the "Missed WhatsApp call" notification; tapping it opens the launch activity. */
    fun showMissed(
        context: Context,
        notice: WaMissedNotice,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return try {
            PlatformChannels.ensure(context, MISSED_CHANNEL)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open =
                PendingIntent.getActivity(
                    context,
                    missedNotificationId(notice.callId),
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                Notification
                    .Builder(context, MISSED_CHANNEL)
                    .let { NotificationIcon.apply(it, context, NotificationKind.CALL_MISSED) }
                    .setCategory(Notification.CATEGORY_MISSED_CALL)
                    .setContentTitle(displayTitle(context, notice.title))
                    .setContentText(context.getString(R.string.wa_call_missed))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            manager.notify(missedNotificationId(notice.callId), notification)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * The activity calls this with the launch intent (`onCreate`, `onNewIntent`). When it carries an Answer or Decline tap
     * it cancels the ringing notification, removes the action extra (a rotation must not answer twice) and returns the
     * `wa_call_action` payload; otherwise null.
     */
    fun takeAction(
        context: Context,
        intent: Intent?,
    ): String? {
        val id = intent?.getStringExtra(EXTRA_CALL_ID) ?: return null
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return null
        val json = actionJson(id, action) ?: return null
        intent.removeExtra(EXTRA_ACTION)
        cancel(context, id)
        return json
    }

    fun cancel(
        context: Context,
        callId: String,
    ): Boolean {
        markOver(callId)
        WaRinger.stop(callId)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val id = notificationId(callId)
        // Called from an FCM push and from Rust over JNI: never throws.
        return try {
            val shown = manager.activeNotifications.any { it.id == id }
            manager.cancel(id)
            shown
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * After Answer on the notification (the receiver took the action): opens the call screen. On an unlocked phone in
     * use the activity is started directly. Locked or with the screen off, Android 12+ refuses an activity start from a
     * notification's broadcast (the "trampoline" rule) unless the app has a background-start exemption (HyperOS: "Display
     * pop-up windows while running in the background"), so it is tried and a short "Answering…" notice with a full-screen
     * intent is posted as well: the system starts that over the keyguard, and `LockScreenGate` keeps it there.
     */
    fun openAnswered(
        context: Context,
        callId: String,
    ) {
        val launch = launchIntent(context, callId, null).putExtra(EXTRA_ANSWERED, true)
        try {
            context.startActivity(launch)
        } catch (e: RuntimeException) {
            RostyLog.w(TAG, "WA call: could not open the answered call", e)
        }
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        if (shouldOpenDirectly(interactive, locked)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            PlatformChannels.ensure(context, CHANNEL)
            val open =
                PendingIntent.getActivity(
                    context,
                    answeringNotificationId(callId),
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notice =
                Notification
                    .Builder(context, CHANNEL)
                    .let { NotificationIcon.apply(it, context, NotificationKind.WA_CALL) }
                    .setCategory(Notification.CATEGORY_CALL)
                    .setContentTitle(context.getString(R.string.wa_call_title))
                    .setContentText(context.getString(R.string.wa_call_answering))
                    .setTimeoutAfter(ANSWERING_TIMEOUT_MS)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .build()
            manager.notify(answeringNotificationId(callId), notice)
        } catch (e: RuntimeException) {
            RostyLog.w(TAG, "WA call: no answering notice", e)
        }
    }

    /** Removes the "Answering…" notice of [openAnswered] (the activity got the call). Never throws. */
    fun cancelAnswering(
        context: Context,
        callId: String,
    ) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(answeringNotificationId(callId))
        } catch (_: RuntimeException) {
        }
    }

    /**
     * The intent of the locked-call activity [screen] for [notice] (round 7b): the call id, the caller's title as the
     * notice shows it, and Answer when it was tapped on the notice.
     */
    fun lockedCallIntent(
        context: Context,
        screen: Class<*>,
        notice: WaIncomingNotice,
        action: String?,
    ): Intent {
        val intent =
            Intent(context, screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CALL_ID, notice.callId)
                .putExtra(EXTRA_TITLE, displayTitle(context, notice.title))
        action?.let { intent.putExtra(EXTRA_ACTION, it) }
        return intent
    }

    private fun lockedPending(
        context: Context,
        screen: Class<*>,
        notice: WaIncomingNotice,
        action: String?,
        slot: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(notice.callId, slot),
            lockedCallIntent(context, screen, notice, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The launch activity with [callId] and [action]: the locked-call activity hands Answer over with it. */
    fun answerIntent(
        context: Context,
        callId: String,
    ): Intent = launchIntent(context, callId, ANSWER).putExtra(EXTRA_ANSWERED, true)

    private fun launchIntent(
        context: Context,
        callId: String,
        action: String?,
    ): Intent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch.putExtra(EXTRA_CALL_ID, callId)
        action?.let { launch.putExtra(EXTRA_ACTION, it) }
        return launch
    }

    /**
     * The PendingIntent of Answer or Decline: a broadcast to the app's receiver when it installed one (works on the lock
     * screen), else the launch activity.
     */
    internal fun actionPending(
        context: Context,
        callId: String,
        action: String,
    ): PendingIntent {
        val slot = if (action == ANSWER) 1 else 2
        val receiver =
            PlatformComponents.getOrNull()?.waCallAction ?: return pending(context, callId, action, slot)
        val intent =
            Intent(context, receiver)
                .setAction(ACTION_CALL)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_ACTION, action)
        return PendingIntent.getBroadcast(
            context,
            requestCode(callId, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Distinct request codes per call and slot, or the system reuses one PendingIntent for all three. */
    private fun requestCode(
        callId: String,
        slot: Int,
    ): Int = notificationId(callId) * 3 + slot

    private fun pending(
        context: Context,
        callId: String,
        action: String?,
        slot: Int,
    ): PendingIntent {
        val launch = launchIntent(context, callId, action)
        val code = requestCode(callId, slot)
        return PendingIntent.getActivity(
            context,
            code,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
