package dev.rosty.messages.notify

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.rosty.core.log.RostyLog
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.platform.Headless
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.MessageNotifications
import dev.rosty.platform.notify.MessageReminders
import dev.rosty.platform.notify.NotificationSpec
import dev.rosty.platform.notify.ReplyActions
import dev.rosty.platform.sms.SmsClock
import dev.rosty.platform.sms.SmsQuery
import dev.rosty.platform.sms.SmsRequest
import dev.rosty.platform.sms.SmsSend
import dev.rosty.shell.HasContainer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.TimeZone

/** Where the notification actions do their blocking work; tests run it inline. */
internal object NotifyWork {
    @Volatile var executor: (Runnable) -> Unit = { Thread(it, "notify-action").start() }

    fun run(block: () -> Unit) = executor(Runnable(block))
}

/**
 * The actions of a message notification built by [MessagesNotifier] (E6.9; AD-MSG4, AD-MSG5): inline reply, "Copy
 * code", "Mark as read" and "Remind me in 1 hour" (M41). Not exported. The action names, extras and the Rust requests
 * are `:platform`'s [ReplyActions]; this receiver adds the app's side of them: the reply path of an attached UI, the
 * notification in the phone's language after a reply, and the group summary.
 */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val tag = intent.getStringExtra(MessageNotifications.EXTRA_TAG) ?: return
        val app = context.applicationContext
        when (intent.action) {
            ReplyActions.REPLY -> {
                val text =
                    RemoteInput
                        .getResultsFromIntent(intent)
                        ?.getCharSequence(MessageNotifications.KEY_REPLY)
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                if (text.isEmpty()) return
                background { InlineReply.send(app, tag, text) }
            }

            ReplyActions.COPY -> {
                intent.getStringExtra(ReplyActions.EXTRA_CODE)?.let {
                    MessageNotifications.copyToClipboard(app, it, sensitive = true)
                }
                MessagesNotifier.dismiss(app, tag)
            }

            ReplyActions.MARK_READ -> {
                NotificationSpec.smsThreadId(tag)?.let { thread -> background { SmsQuery.markRead(app, thread) } }
                MessagesNotifier.dismiss(app, tag)
            }

            ReplyActions.REMIND -> {
                val title = intent.getStringExtra(ReplyActions.EXTRA_TITLE).orEmpty()
                val body = intent.getStringExtra(ReplyActions.EXTRA_BODY).orEmpty()
                background { remind(app, tag, title, body) }
            }
        }
    }

    private fun background(block: () -> Unit) {
        val result: PendingResult? = goAsync()
        NotifyWork.run {
            try {
                block()
            } catch (e: RuntimeException) {
                RostyLog.e(TAG, "A notification action failed", e)
            } finally {
                result?.finish()
            }
        }
    }

    /**
     * "Remind me in 1 hour" (M41): Rust builds the reminder (`msg_remind`), the alarm is armed and the notification
     * goes. Without an answer (no native library) the notification stays as it is.
     */
    private fun remind(
        context: Context,
        tag: String,
        title: String,
        body: String,
    ) {
        val now = SmsClock.nowMillis()
        val request = ReplyActions.remindRequest(tag, title, body, now, TimeZone.getDefault().getOffset(now) / 1000)
        val answer =
            Headless.call(ReplyActions.KIND_REMIND, request)?.let {
                try {
                    JSONObject(it)
                } catch (_: org.json.JSONException) {
                    null
                }
            }
        val arm = answer?.optJSONObject("arm")
        if (answer == null || !answer.optBoolean("ok") || arm == null) return
        if (!MessageReminders.arm(context, arm.toString())) return
        MessagesNotifier.dismiss(context, tag)
        toast(context, context.getString(R.string.notify_reminder_set))
        PlatformEvents.emit(MessageReminders.EVENT_CHANGED, "{}")
    }

    private companion object {
        const val TAG = "messages"
    }
}

/**
 * One reply typed into a notification. With the UI attached it goes through the app's [ReplyRouter] (the SMS
 * repository, the WhatsApp outbox) and the notification goes away, as when the conversation is answered on screen.
 * With no UI attached the process may be cold: an SMS goes straight to the radio through [SmsSend], a WhatsApp reply
 * through `headless("wa_reply")`, and the notification is posted again with the reply under the messages, silently,
 * so the system's reply spinner stops. A failed reply leaves the notification as it was and says so.
 */
internal object InlineReply {
    fun send(
        context: Context,
        tag: String,
        text: String,
    ) {
        val router = if (PlatformEvents.uiAttached) routerOf(context) else null
        val ok = if (router != null) runBlocking { router.send(tag, text) } else cold(context, tag, text)
        when {
            !ok -> {
                repostUnchanged(context, tag)
                toast(context, context.getString(R.string.notify_reply_failed))
            }

            router != null -> {
                MessagesNotifier.dismiss(context, tag)
            }

            else -> {
                val shown = MessagesNotifier.shown(context, tag)
                val next = shown?.copy(spec = shown.spec.copy(silent = true), sent = shown.sent + text)
                // Nothing to append to, or the notifier refused (muted meanwhile): the spinner still has to stop.
                if (next == null || !MessagesNotifier.show(context, next)) MessagesNotifier.dismiss(context, tag)
            }
        }
    }

    private fun routerOf(context: Context): ReplyRouter? =
        ((context.applicationContext as? HasContainer<*>)?.container as? MessagesContainer)?.replies

    /** The reply of a process with no UI: no repository, no store, at most one blocking call into Rust. */
    private fun cold(
        context: Context,
        tag: String,
        text: String,
    ): Boolean {
        val thread = NotificationSpec.smsThreadId(tag)
        val chat = NotificationSpec.waChat(tag)
        return when {
            thread != null -> {
                val to = SmsQuery.addressesFor(context, thread)
                to.isNotEmpty() && SmsSend.send(context, SmsRequest(SmsClock.nowMillis(), to, text, null))
            }

            chat != null -> {
                val request = ReplyActions.waReplyRequest(Headless.dataDir(context), chat, text)
                val answer = Headless.call(ReplyActions.KIND_WA_REPLY, request) ?: return false
                try {
                    JSONObject(answer).optBoolean("ok")
                } catch (_: org.json.JSONException) {
                    false
                }
            }

            else -> {
                false
            }
        }
    }

    private fun repostUnchanged(
        context: Context,
        tag: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val shown = manager.activeNotifications.firstOrNull { it.tag == tag } ?: return
        try {
            manager.notify(tag, 0, shown.notification)
        } catch (_: RuntimeException) {
        }
    }
}

private fun toast(
    context: Context,
    text: String,
) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}
