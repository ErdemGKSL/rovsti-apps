package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import dev.rosty.core.model.wa.WaIds
import dev.rosty.messages.R
import dev.rosty.platform.notify.MessageNotifications
import dev.rosty.platform.notify.NotificationIcon
import dev.rosty.platform.notify.NotificationKind
import dev.rosty.platform.notify.NotificationSpec
import dev.rosty.platform.notify.PlatformChannels
import dev.rosty.platform.notify.ReplyActions
import dev.rosty.platform.sms.MessageNotifier
import dev.rosty.platform.sms.SmsClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one conversation's notification shows: Rust's digest ([spec], `rosty_core::notify::NotificationSpec`) and the
 * replies typed into it since ([sent], oldest first), which follow the incoming lines as the user's own messages.
 */
data class Digest(
    val spec: NotificationSpec,
    val sent: List<String> = emptyList(),
) {
    fun toJson(): String =
        JSONObject()
            .put("tag", spec.tag)
            .put("title", spec.title)
            .put("lines", JSONArray(spec.lines))
            .put("otp", spec.otp ?: JSONObject.NULL)
            .put("reply", spec.reply)
            .put("silent", spec.silent)
            .put("sent", JSONArray(sent))
            .toString()

    companion object {
        /** Pure: a spec JSON (no `sent`) or what [toJson] wrote; null for anything the spec parser refuses. */
        fun parse(json: String): Digest? {
            val spec = NotificationSpec.parse(json) ?: return null
            val sent =
                try {
                    JSONObject(json).optJSONArray("sent")?.let { a -> (0 until a.length()).map { a.optString(it, "") } }
                } catch (_: org.json.JSONException) {
                    null
                }
            return Digest(spec, sent.orEmpty())
        }
    }
}

/**
 * The message notifications of Rosty Messages (E6.9, AD-MSG5): one per conversation, rendered from Rust's digest.
 *
 * - `MessagingStyle` on the channel `messages` with the sender as a `Person` (name, key, round avatar); a group chat
 *   is a group conversation whose lines keep their senders.
 * - Every conversation is a child of one group with a summary, so several conversations fold into one entry.
 * - Actions in the phone's language: inline reply (`RemoteInput`), "Copy code" for a passcode, "Mark as read" and
 *   "Remind me in 1 hour" (M41). They go to [MessageActionReceiver]; a tap opens the conversation.
 * - The `uiAttached` rule: nothing is posted for the conversation the user is looking at ([OpenConversation]), nor
 *   when notifications are off or the conversation is muted ([NotifyPolicy]).
 *
 * The tag and id are those of `:platform`'s `MessageNotifications` (`<tag>`, 0), so its `cancel` still finds them.
 * Every caller may be a receiver in a process with no UI: nothing here needs the native library.
 */
object MessagesNotifier {
    /** The group every conversation notification belongs to. */
    const val GROUP = "rosty_messages"

    /** The tag of the group summary. */
    const val SUMMARY_TAG = "rosty:summary"

    /** The extra that carries the [Digest] of a posted notification, so a reply can be appended to it. */
    const val EXTRA_DIGEST = "rosty_digest"

    /** The seam `MmsInbox.notifier` takes; the app installs it from `Application.onCreate`. */
    val notifier: MessageNotifier = MessageNotifier { context, json -> show(context, json) }

    /** Told which conversation's notification was taken away, so its digest starts again. */
    @Volatile var onDismissed: ((String) -> Unit)? = null

    /** Where the person's picture comes from; tests swap it. */
    @Volatile var avatars: NotificationAvatars = ContactNotificationAvatars

    /** Posts or updates the notification of [specJson] (a `NotificationSpec`). False when nothing was posted. */
    fun show(
        context: Context,
        specJson: String,
    ): Boolean = Digest.parse(specJson)?.let { show(context, it) } ?: false

    fun show(
        context: Context,
        digest: Digest,
    ): Boolean {
        val policy = NotifyPolicy.current
        val now = SmsClock.nowMillis()
        val tag = digest.spec.tag
        if (!policy.enabled || policy.isMuted(tag, now) || OpenConversation.isShowing(tag)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        // The passcode chip is a setting; a passcode is still not answered, so reply stays off (GPUI `plan`).
        val shown = if (policy.otpCopy) digest else digest.copy(spec = digest.spec.copy(otp = null))
        return try {
            PlatformChannels.ensure(context, PlatformChannels.MESSAGES)
            manager.notify(tag, 0, build(context, shown, now))
            manager.notify(SUMMARY_TAG, 0, summary(context, manager, shown))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The digest of the notification shown for [tag], or null. */
    fun shown(
        context: Context,
        tag: String,
    ): Digest? =
        context
            .getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.tag == tag }
            ?.notification
            ?.extras
            ?.getString(EXTRA_DIGEST)
            ?.let(Digest::parse)

    /** Takes the notification of [tag] away (it was opened, answered or read), and the summary with the last one. */
    fun dismiss(
        context: Context,
        tag: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.cancel(tag, 0)
            sweepSummary(manager, gone = tag)
        } catch (_: RuntimeException) {
        }
        onDismissed?.invoke(tag)
    }

    /**
     * The chat of the tag [from] merged into the chat of [into]: the notification of [from] is taken away and, when it
     * was showing and [into] shows none of its own, posted again under [into], silently (the same lines, no second
     * sound). A notification [into] already shows stays as it is. True when one was posted under [into].
     */
    fun retag(
        context: Context,
        from: String,
        into: String,
    ): Boolean {
        if (from == into) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val old = shown(context, from)
        val kept = shown(context, into)
        try {
            manager.cancel(from, 0)
            sweepSummary(manager, gone = from)
        } catch (_: RuntimeException) {
        }
        if (old == null || kept != null) return false
        return show(context, old.copy(spec = old.spec.copy(tag = into, silent = true)))
    }

    /** Takes away the notification of every conversation whose tag starts with [prefix], silently. */
    fun dismissAll(
        context: Context,
        prefix: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            for (shown in manager.activeNotifications) {
                val tag = shown.tag ?: continue
                if (tag.startsWith(prefix)) manager.cancel(tag, 0)
            }
            sweepSummary(manager, gone = null)
        } catch (_: RuntimeException) {
        }
    }

    /** Cancels the summary once no conversation is left under it. */
    fun sweepSummary(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            sweepSummary(manager, gone = null)
        } catch (_: RuntimeException) {
        }
    }

    private fun sweepSummary(
        manager: NotificationManager,
        gone: String?,
    ) {
        val left = manager.activeNotifications.any { it.isConversation() && it.tag != gone }
        if (!left) manager.cancel(SUMMARY_TAG, 0)
    }

    private fun android.service.notification.StatusBarNotification.isConversation(): Boolean =
        tag != SUMMARY_TAG && notification.group == GROUP

    private fun kindOf(tag: String): NotificationKind =
        if (NotificationSpec.waChat(tag) != null) NotificationKind.WA_MESSAGE else NotificationKind.MESSAGE

    /** A WhatsApp group chat: its lines read "Sender: text" (`rosty_core::notify::build`). */
    internal fun isGroup(tag: String): Boolean = WaIds.isGroup(NotificationSpec.waChat(tag))

    /** The title to show: the spec's, else the number of the chat, else the app's name. */
    private fun titleOf(
        context: Context,
        spec: NotificationSpec,
    ): String =
        spec.title.takeIf { it.isNotBlank() }
            ?: WaIds.number(NotificationSpec.waChat(spec.tag))
            ?: context.getString(R.string.launcher_name)

    internal fun build(
        context: Context,
        digest: Digest,
        now: Long,
    ): Notification {
        val spec = digest.spec
        val title = titleOf(context, spec)
        val group = isGroup(spec.tag)
        val photo = avatars.load(context, spec.tag)?.let(Icon::createWithBitmap)
        val me = Person.Builder().setName(context.getString(R.string.notify_you)).build()
        val them =
            Person
                .Builder()
                .setName(title)
                .setKey(spec.tag)
                .setIcon(photo)
                .build()
        val style = Notification.MessagingStyle(me)
        if (group) style.setConversationTitle(title).setGroupConversation(true)
        // The spec has no per-line times: keep the order with one second between lines.
        val count = spec.lines.size + digest.sent.size
        spec.lines.forEachIndexed { i, line ->
            val (sender, text) = if (group) senderOf(line, spec.tag) ?: (them to line) else them to line
            style.addMessage(text, now - (count - 1 - i) * LINE_GAP_MS, sender)
        }
        digest.sent.forEachIndexed { i, line ->
            style.addMessage(line, now - (digest.sent.size - 1 - i) * LINE_GAP_MS, null as Person?)
        }
        val builder =
            Notification
                .Builder(context, PlatformChannels.MESSAGES)
                .let { NotificationIcon.apply(it, context, kindOf(spec.tag), photo) }
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(style)
                .addPerson(them)
                .setWhen(now)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(spec.silent)
                .setContentIntent(openIntent(context, spec.tag))
                .setGroup(GROUP)
                // A silent update leaves the alert to the summary, which never alerts: no sound, no pop-up.
                .setGroupAlertBehavior(
                    if (spec.silent) Notification.GROUP_ALERT_SUMMARY else Notification.GROUP_ALERT_CHILDREN,
                )
        builder.addExtras(Bundle().apply { putString(EXTRA_DIGEST, digest.toJson()) })
        if (spec.reply) builder.addAction(replyAction(context, spec.tag))
        spec.otp?.let { code ->
            builder.addAction(
                action(
                    context,
                    R.string.notify_copy_code,
                    spec.tag,
                    ReplyActions.COPY,
                    MessageNotifications.SLOT_COPY,
                ) {
                    it.putExtra(ReplyActions.EXTRA_CODE, code)
                },
            )
        }
        builder.addAction(
            action(
                context,
                R.string.notify_mark_read,
                spec.tag,
                ReplyActions.MARK_READ,
                MessageNotifications.SLOT_MARK_READ,
            ),
        )
        if (spec.otp == null) {
            builder.addAction(
                action(
                    context,
                    R.string.notify_remind_hour,
                    spec.tag,
                    ReplyActions.REMIND,
                    MessageNotifications.SLOT_REMIND,
                ) {
                    it.putExtra(ReplyActions.EXTRA_TITLE, title).putExtra(ReplyActions.EXTRA_BODY, spec.lines.last())
                },
            )
        }
        return builder.build()
    }

    /** "Sender: text" of a group line as a person and the text; null when the line names no sender. */
    private fun senderOf(
        line: String,
        tag: String,
    ): Pair<Person, String>? {
        val at = line.indexOf(SENDER_SEPARATOR)
        if (at <= 0) return null
        val name = line.substring(0, at)
        val person =
            Person
                .Builder()
                .setName(name)
                .setKey("$tag/$name")
                .build()
        return person to line.substring(at + SENDER_SEPARATOR.length)
    }

    /** The group summary: the conversations under it, newest first, one line each. It never alerts. */
    private fun summary(
        context: Context,
        manager: NotificationManager,
        newest: Digest,
    ): Notification {
        val others =
            manager.activeNotifications
                .filter { it.isConversation() && it.tag != newest.spec.tag }
                .sortedByDescending { it.postTime }
                .mapNotNull {
                    it.notification.extras
                        .getString(EXTRA_DIGEST)
                        ?.let(Digest::parse)
                }
        val all = listOf(newest) + others
        val style = Notification.InboxStyle()
        for (digest in all.take(SUMMARY_LINES)) {
            val last = digest.sent.lastOrNull() ?: digest.spec.lines.last()
            style.addLine(context.getString(R.string.notify_summary_line, titleOf(context, digest.spec), last))
        }
        val text = context.resources.getQuantityString(R.plurals.notify_summary_chats, all.size, all.size)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification
            .Builder(context, PlatformChannels.MESSAGES)
            .setSmallIcon(NotificationIcon.small(context, NotificationKind.MESSAGE))
            .setColor(NotificationKind.MESSAGE.accent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle(context.getString(R.string.launcher_name))
            .setContentText(text)
            .setStyle(style.setSummaryText(text))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    MessageNotifications.requestCode(SUMMARY_TAG, MessageNotifications.SLOT_OPEN),
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
    }

    private fun replyAction(
        context: Context,
        tag: String,
    ): Notification.Action {
        val label = context.getString(R.string.notify_reply)
        val input = RemoteInput.Builder(MessageNotifications.KEY_REPLY).setLabel(label).build()
        // Reply needs FLAG_MUTABLE so the system can add the typed text; every other intent is immutable.
        val intent = receiverIntent(context, tag, ReplyActions.REPLY, MessageNotifications.SLOT_REPLY, mutable = true)
        return Notification.Action
            .Builder(null as Icon?, label, intent)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun action(
        context: Context,
        label: Int,
        tag: String,
        action: String,
        slot: Int,
        extras: (Intent) -> Unit = {},
    ): Notification.Action =
        Notification.Action
            .Builder(
                null as Icon?,
                context.getString(label),
                receiverIntent(context, tag, action, slot, mutable = false, extras),
            ).build()

    private fun openIntent(
        context: Context,
        tag: String,
    ): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MessageNotifications.EXTRA_TAG, tag)
        return PendingIntent.getActivity(
            context,
            MessageNotifications.requestCode(tag, MessageNotifications.SLOT_OPEN),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun receiverIntent(
        context: Context,
        tag: String,
        action: String,
        slot: Int,
        mutable: Boolean,
        extras: (Intent) -> Unit = {},
    ): PendingIntent {
        val intent =
            Intent(action)
                .setClass(context, MessageActionReceiver::class.java)
                .putExtra(MessageNotifications.EXTRA_TAG, tag)
        extras(intent)
        val flag = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            MessageNotifications.requestCode(tag, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or flag,
        )
    }

    private const val LINE_GAP_MS = 1000L
    private const val SENDER_SEPARATOR = ": "
    private const val SUMMARY_LINES = 6
}
