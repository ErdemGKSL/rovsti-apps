package dev.rovsti.platform.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PersistableBundle
import dev.rovsti.bridge.ReplyReceiver
import dev.rovsti.platform.sms.MessageNotifier
import dev.rovsti.platform.sms.SmsClock
import org.json.JSONObject

/**
 * `rovsti_core::notify::NotificationSpec`: what to render for one conversation.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MessageNotifications.kt
 */
data class NotificationSpec(
    val tag: String,
    val title: String,
    val lines: List<String>,
    val otp: String?,
    val reply: Boolean,
    val silent: Boolean,
) {
    companion object {
        /** Pure: null for malformed JSON, an empty tag, or a spec with nothing to show. */
        fun parse(json: String): NotificationSpec? =
            try {
                val o = JSONObject(json)
                val tag = o.optString("tag", "")
                val arr = o.optJSONArray("lines")
                val lines = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it, "") }
                if (tag.isEmpty() || lines.isEmpty()) {
                    null
                } else {
                    NotificationSpec(
                        tag,
                        o.optString("title", ""),
                        lines,
                        if (o.isNull("otp")) null else o.optString("otp", "").takeIf { it.isNotEmpty() },
                        o.optBoolean("reply", false),
                        o.optBoolean("silent", false),
                    )
                }
            } catch (_: org.json.JSONException) {
                null
            }

        /** Pure: `sms:<thread id>` gives the id; null for any other tag. */
        fun smsThreadId(tag: String): Long? =
            if (tag.startsWith("sms:")) tag.removePrefix("sms:").toLongOrNull() else null

        /** Pure: `wa:<jid>` gives the jid; null for any other tag or an empty jid. */
        fun waChat(tag: String): String? = tag.removePrefix("wa:").takeIf { tag.startsWith("wa:") && it.isNotEmpty() }
    }
}

/**
 * Message notifications (AD-MSG5): `MessagingStyle` on channel `messages`, an inline-reply action (`RemoteInput`,
 * `FLAG_MUTABLE`), "Copy code" for a passcode and "Mark read". Taps and actions go to [ReplyReceiver]; the content
 * intent opens the launch activity with `open_thread_tag`.
 */
object MessageNotifications {
    const val KEY_REPLY = "reply_text"
    const val EXTRA_TAG = "open_thread_tag"
    private const val SILENT_GROUP = "rovsti_silent"

    /** The seam `MmsInbox.notifier` takes; the app installs it from `Application.onCreate`. */
    val notifier: MessageNotifier = MessageNotifier { context, json -> show(context, json) }

    /** Posts or updates the notification. False when notifications are off or the spec is unusable. */
    fun show(
        context: Context,
        specJson: String,
    ): Boolean {
        val spec = NotificationSpec.parse(specJson) ?: return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return try {
            PlatformChannels.ensure(context, PlatformChannels.MESSAGES)
            manager.notify(spec.tag, 0, build(context, spec))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancel(
        context: Context,
        tag: String,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val shown = manager.activeNotifications.any { it.tag == tag }
        manager.cancel(tag, 0)
        return shown
    }

    private fun build(
        context: Context,
        spec: NotificationSpec,
    ): Notification {
        val me = Person.Builder().setName("You").build()
        val them = Person.Builder().setName(spec.title).build()
        val style = Notification.MessagingStyle(me).setConversationTitle(spec.title)
        val now = SmsClock.nowMillis()
        // The spec has no per-line times: keep their order with one second between lines.
        spec.lines.forEachIndexed { i, line ->
            style.addMessage(line, now - (spec.lines.size - 1 - i) * 1000L, them)
        }
        val builder =
            Notification
                .Builder(context, PlatformChannels.MESSAGES)
                .setSmallIcon(NotificationIcon.small(context))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(style)
                .setAutoCancel(true)
                .setOnlyAlertOnce(spec.silent)
                .setContentIntent(openIntent(context, spec.tag))
        // `Notification.Builder` has no setSilent (only NotificationCompat does). This is what it does: a child of a
        // group whose alerts belong to the summary makes no sound and does not pop up.
        if (spec.silent) builder.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        if (spec.reply) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
            builder.addAction(
                Notification.Action
                    .Builder(
                        null as Icon?,
                        "Reply",
                        receiverIntent(context, spec.tag, ReplyActions.REPLY, null, SLOT_REPLY, mutable = true),
                    ).addRemoteInput(input)
                    .setAllowGeneratedReplies(true)
                    .build(),
            )
        }
        spec.otp?.let {
            builder.addAction(
                Notification.Action
                    .Builder(
                        null as Icon?,
                        "Copy code",
                        receiverIntent(context, spec.tag, ReplyActions.COPY, it, SLOT_COPY, mutable = false),
                    ).build(),
            )
        }
        builder.addAction(
            Notification.Action
                .Builder(
                    null as Icon?,
                    "Mark read",
                    receiverIntent(context, spec.tag, ReplyActions.MARK_READ, null, SLOT_MARK_READ, mutable = false),
                ).build(),
        )
        if (spec.otp == null) {
            builder.addAction(
                Notification.Action
                    .Builder(
                        null as Icon?,
                        "Remind me in 1 hour",
                        receiverIntent(context, spec.tag, ReplyActions.REMIND, null, SLOT_REMIND, mutable = false) {
                            it
                                .putExtra(
                                    ReplyActions.EXTRA_TITLE,
                                    spec.title,
                                ).putExtra(ReplyActions.EXTRA_BODY, spec.lines.last())
                        },
                    ).build(),
            )
        }
        return builder.build()
    }

    private fun openIntent(
        context: Context,
        tag: String,
    ): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_TAG, tag)
        return PendingIntent.getActivity(
            context,
            requestCode(tag, SLOT_OPEN),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Request codes are `tag.hashCode() * 8 + slot`: Reply 0, Copy 1, Mark read 2, open 3, Remind 4.
    const val SLOT_REPLY = 0
    const val SLOT_COPY = 1
    const val SLOT_MARK_READ = 2
    const val SLOT_OPEN = 3
    const val SLOT_REMIND = 4

    fun requestCode(
        tag: String,
        slot: Int,
    ): Int = tag.hashCode() * 8 + slot

    /** Reply needs `FLAG_MUTABLE` so the system can add the typed text; the others are immutable. */
    private fun receiverIntent(
        context: Context,
        tag: String,
        action: String,
        code: String?,
        slot: Int,
        mutable: Boolean,
        extras: (Intent) -> Unit = {},
    ): PendingIntent {
        val intent = Intent(action).setClass(context, ReplyReceiver::class.java).putExtra(EXTRA_TAG, tag)
        code?.let { intent.putExtra(ReplyActions.EXTRA_CODE, it) }
        extras(intent)
        val flag = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode(tag, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or flag,
        )
    }

    /** Copies [text]; with [sensitive] the clip is hidden from the paste preview (API 33+, `EXTRA_IS_SENSITIVE`). */
    fun copyToClipboard(
        context: Context,
        text: String,
        sensitive: Boolean,
    ): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            val clip = ClipData.newPlainText("Rovsti", text)
            if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras =
                    PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            clipboard.setPrimaryClip(clip)
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
