package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.rosty.messages.R
import dev.rosty.platform.notify.NotificationIcon
import dev.rosty.platform.notify.NotificationKind
import dev.rosty.platform.notify.PlatformChannels

/**
 * One chat's WhatsApp notification while the vault is locked, from what a sealed push said (round 7b) or, for a push
 * that is not sealed, the device contact that owns the chat's number. [title] is who wrote (a group's subject), [line]
 * the text ("Sender: text" in a group) or null when only the name may show. Only ever in memory and in the
 * notification.
 */
data class LockedChat(
    /** The chat JID; it names the notification and is never shown. */
    val chat: String,
    val title: String,
    val line: String?,
    val count: Int,
    val whenMs: Long,
) {
    override fun toString(): String = "LockedChat(count=$count)"
}

/**
 * The WhatsApp notifications while the vault is locked (`plans/wa-vault.md` 6, 13.6, round 7b).
 *
 * - [add]: the one content-free notice, "WhatsApp, 3 new messages", for a push that says nothing readable (no push
 *   key on the gateway, a push that does not open, or the "No content" preview setting). Public on the lock screen.
 * - [addChat]: one notice per chat from a sealed push, as the preview setting allows ([WaPreviewMode]): the sender and
 *   the text, or the sender and the count. `VISIBILITY_PRIVATE` with the public version "WhatsApp, N new messages", so
 *   the lock screen shows no name or text until the phone itself is unlocked.
 *
 * No inline reply, no action at all, and no gateway request. A tap opens the app, where the unlock page waits. The
 * count of the public version adds up across pushes (in the app's `rosty_wa_locked` preferences: a number only, so a
 * cold process keeps counting); a chat's lines and count live only in its notification's extras. Everything starts
 * again at the unlock ([clear]).
 */
object LockedWhatsAppNotice {
    const val TAG = "rosty:wa-locked"
    private const val PREFS = "rosty_wa_locked"
    private const val KEY_COUNT = "count"
    private const val MAX_COUNT = 999
    private const val MAX_LINES = 6
    private const val EXTRA_LINES = "rosty_wa_locked_lines"
    private const val EXTRA_CHAT_COUNT = "rosty_wa_locked_count"

    /** The messages counted since the lock. */
    fun count(context: Context): Int = prefs(context).getInt(KEY_COUNT, 0)

    private fun addCount(
        context: Context,
        messages: Int,
    ): Int {
        val total = (count(context) + messages.coerceAtLeast(1)).coerceAtMost(MAX_COUNT)
        prefs(context).edit().putInt(KEY_COUNT, total).apply()
        return total
    }

    /** Adds [messages] (a push's `count`, at least 1) and posts the notice. False when nothing was posted. */
    fun add(
        context: Context,
        messages: Int,
    ): Boolean {
        val total = addCount(context, messages)
        val manager = manager(context) ?: return false
        return try {
            PlatformChannels.ensure(context, PlatformChannels.MESSAGES)
            manager.notify(TAG, 0, build(context, total))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Posts or updates [chat]'s notice as [mode] allows ([WaPreviewMode.NoContent] posts the content-free notice
     * instead). False when nothing was posted.
     */
    fun addChat(
        context: Context,
        chat: LockedChat,
        mode: WaPreviewMode,
    ): Boolean {
        if (mode == WaPreviewMode.NoContent) return add(context, chat.count)
        val total = addCount(context, chat.count)
        val manager = manager(context) ?: return false
        val id = chatId(chat.chat)
        val shown =
            try {
                manager.activeNotifications
                    .firstOrNull { it.tag == TAG && it.id == id }
                    ?.notification
                    ?.extras
            } catch (_: RuntimeException) {
                null
            }
        val chatCount = ((shown?.getInt(EXTRA_CHAT_COUNT) ?: 0) + chat.count.coerceAtLeast(1)).coerceAtMost(MAX_COUNT)
        val lines =
            if (mode == WaPreviewMode.NameAndPreview) {
                (shown?.getStringArrayList(EXTRA_LINES).orEmpty() + listOfNotNull(chat.line)).takeLast(MAX_LINES)
            } else {
                emptyList()
            }
        return try {
            PlatformChannels.ensure(context, PlatformChannels.MESSAGES)
            manager.notify(TAG, id, buildChat(context, chat, lines, chatCount, total))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The vault was unlocked (the app now shows the messages themselves), or the phone restarted. */
    fun clear(context: Context) {
        prefs(context).edit().putInt(KEY_COUNT, 0).apply()
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) }
            manager.cancel(TAG, 0)
        } catch (_: RuntimeException) {
        }
    }

    /** The words of the notice: the app's "WhatsApp" and the count, nothing else. */
    fun text(
        context: Context,
        count: Int,
    ): String = context.resources.getQuantityString(R.plurals.wa_locked_notice, count, count)

    /** Pure: the notification id of [chat]'s notice (never 0, the content-free notice's). */
    fun chatId(chat: String): Int = chat.hashCode().let { if (it == 0) 1 else it }

    private fun manager(context: Context): NotificationManager? {
        if (!NotifyPolicy.current.enabled) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return manager.takeIf { it.areNotificationsEnabled() }
    }

    private fun openIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            TAG.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** "WhatsApp", "N new messages": the content-free notice and every public version. */
    private fun public(
        context: Context,
        count: Int,
    ): Notification =
        Notification
            .Builder(context, PlatformChannels.MESSAGES)
            .let { NotificationIcon.apply(it, context, NotificationKind.WA_MESSAGE) }
            .setContentTitle(context.getString(R.string.wa_locked_notice_title))
            .setContentText(text(context, count))
            .build()

    internal fun build(
        context: Context,
        count: Int,
    ): Notification {
        val title = context.getString(R.string.wa_locked_notice_title)
        val text = text(context, count)
        // The lock screen shows the same: there is nothing to hide in it.
        return Notification
            .Builder(context, PlatformChannels.MESSAGES)
            .let { NotificationIcon.apply(it, context, NotificationKind.WA_MESSAGE) }
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle(title)
            .setContentText(text)
            .setNumber(count)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(public(context, count))
            .build()
    }

    internal fun buildChat(
        context: Context,
        chat: LockedChat,
        lines: List<String>,
        chatCount: Int,
        total: Int,
    ): Notification {
        val countText = text(context, chatCount)
        val builder =
            Notification
                .Builder(context, PlatformChannels.MESSAGES)
                .let { NotificationIcon.apply(it, context, NotificationKind.WA_MESSAGE) }
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentTitle(chat.title)
                .setContentText(lines.lastOrNull() ?: countText)
                .setNumber(chatCount)
                .setWhen(chat.whenMs)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context))
                // The name and the text stay off the lock screen until the phone itself is unlocked.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public(context, total))
        when {
            lines.size > 1 -> {
                val style = Notification.InboxStyle().setSummaryText(countText)
                lines.forEach(style::addLine)
                builder.setStyle(style)
            }

            lines.size == 1 -> {
                builder.setStyle(Notification.BigTextStyle().bigText(lines.single()))
            }
        }
        builder.addExtras(
            Bundle().apply {
                putStringArrayList(EXTRA_LINES, ArrayList(lines))
                putInt(EXTRA_CHAT_COUNT, chatCount)
            },
        )
        return builder.build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
