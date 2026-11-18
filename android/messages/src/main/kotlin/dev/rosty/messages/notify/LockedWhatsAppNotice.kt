package dev.rosty.messages.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.rosty.messages.R
import dev.rosty.platform.notify.NotificationIcon
import dev.rosty.platform.notify.NotificationKind
import dev.rosty.platform.notify.PlatformChannels

/**
 * The one WhatsApp notification while the vault is locked (`plans/wa-vault.md` 6, 13.6): "WhatsApp, 3 new messages".
 * No sender, no text, no inline reply, no action at all, and no gateway request: a push carries only `kind`, `chat` and
 * `count`, and the chat is not shown. A tap opens the app, where the unlock page waits. The count adds up across pushes
 * (in the app's `rosty_wa_locked` preferences, so a cold process keeps counting) and starts again at the unlock.
 */
object LockedWhatsAppNotice {
    const val TAG = "rosty:wa-locked"
    private const val PREFS = "rosty_wa_locked"
    private const val KEY_COUNT = "count"
    private const val MAX_COUNT = 999

    /** The messages counted since the lock. */
    fun count(context: Context): Int = prefs(context).getInt(KEY_COUNT, 0)

    /** Adds [messages] (a push's `count`, at least 1) and posts the notice. False when nothing was posted. */
    fun add(
        context: Context,
        messages: Int,
    ): Boolean {
        val total = (count(context) + messages.coerceAtLeast(1)).coerceAtMost(MAX_COUNT)
        prefs(context).edit().putInt(KEY_COUNT, total).apply()
        val policy = NotifyPolicy.current
        if (!policy.enabled) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return try {
            PlatformChannels.ensure(context, PlatformChannels.MESSAGES)
            manager.notify(TAG, 0, build(context, total))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The vault was unlocked (the app now shows the messages themselves), or the phone restarted. */
    fun clear(context: Context) {
        prefs(context).edit().putInt(KEY_COUNT, 0).apply()
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(TAG, 0)
        } catch (_: RuntimeException) {
        }
    }

    /** The words of the notice: the app's "WhatsApp" and the count, nothing else. */
    fun text(
        context: Context,
        count: Int,
    ): String = context.resources.getQuantityString(R.plurals.wa_locked_notice, count, count)

    internal fun build(
        context: Context,
        count: Int,
    ): Notification {
        val title = context.getString(R.string.wa_locked_notice_title)
        val text = text(context, count)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open =
            PendingIntent.getActivity(
                context,
                TAG.hashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // The lock screen shows the same: there is nothing to hide in it.
        val public =
            Notification
                .Builder(context, PlatformChannels.MESSAGES)
                .let { NotificationIcon.apply(it, context, NotificationKind.WA_MESSAGE) }
                .setContentTitle(title)
                .setContentText(text)
                .build()
        return Notification
            .Builder(context, PlatformChannels.MESSAGES)
            .let { NotificationIcon.apply(it, context, NotificationKind.WA_MESSAGE) }
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle(title)
            .setContentText(text)
            .setNumber(count)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(public)
            .build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
