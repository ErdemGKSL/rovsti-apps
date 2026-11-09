package dev.rovsti.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rovsti.platform.notify.BirthdayReminders
import dev.rovsti.platform.notify.CallReminders
import dev.rovsti.platform.notify.MessageReminders
import dev.rovsti.platform.notify.ReplyActions

// The notification-side manifest components. They live in the `dev.rovsti.bridge` package, not `platform.notify`,
// because the manifests of the update-in-place apps and the PendingIntents already armed in the system name
// `dev.rovsti.bridge.*` (ARCH 7.2). FqcnTest pins the names; the logic is in `platform.notify`.

/** Alarm target for [MessageReminders]; declare it in the host manifest (not exported, no boot action). */
class MessageReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != MessageReminders.ACTION_FIRE) return
        val id = intent.getLongExtra(MessageReminders.EXTRA_ID, 0)
        if (id <= 0) return
        val app = context.applicationContext
        fireAsync { MessageReminders.fire(app, id) }
    }
}

/** Alarm target for [CallReminders]; declare it in the host manifest (not exported, no boot action). */
class CallReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != CallReminders.ACTION_FIRE) return
        val id = intent.getLongExtra(CallReminders.EXTRA_ID, 0)
        if (id <= 0) return
        val app = context.applicationContext
        fireAsync { CallReminders.fire(app, id) }
    }
}

/** Alarm target for [BirthdayReminders]; declare it in the host manifest (not exported, no boot action). */
class BirthdayReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != BirthdayReminders.ACTION_FIRE) return
        val id = intent.getLongExtra(BirthdayReminders.EXTRA_ID, 0)
        if (id <= 0) return
        val app = context.applicationContext
        fireAsync { BirthdayReminders.fire(app, id) }
    }
}

private fun BroadcastReceiver.fireAsync(block: () -> Unit) {
    val result: BroadcastReceiver.PendingResult? = goAsync()
    ReceiverWork.run {
        try {
            block()
        } finally {
            result?.finish()
        }
    }
}

/**
 * Notification actions (AD-MSG4, AD-MSG5): Reply, Copy code, Mark read, Remind me. Declare it (not exported) in the host
 * manifest. The logic is [ReplyActions].
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        ReplyActions.handle(this, context, intent)
    }
}
