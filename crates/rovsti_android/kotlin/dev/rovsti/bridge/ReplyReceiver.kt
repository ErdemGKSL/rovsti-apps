package dev.rovsti.bridge

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.util.TimeZone

/**
 * Notification actions (AD-MSG4, AD-MSG5). With the UI attached an inline reply is forwarded to Rust as
 * `msg_reply` `{tag, text}`. Cold, an SMS reply goes straight through [SmsSend] and a WhatsApp reply
 * through `nativeHeadless("wa_reply")`; the notification is then re-posted with the sent line so the
 * spinner stops. Declare the receiver (not exported) in the host manifest.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(MessageNotifications.EXTRA_TAG) ?: return
        val app = context.applicationContext
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifications.KEY_REPLY)?.toString()
                    ?.trim().orEmpty()
                if (text.isEmpty()) return
                if (RovstiBridge.uiReady) {
                    RovstiBridge.emit("msg_reply", JSONObject().put("tag", tag).put("text", text).toString())
                    return
                }
                val result = goAsync()
                Thread {
                    try {
                        replyCold(app, tag, text)
                    } finally {
                        result.finish()
                    }
                }.start()
            }
            ACTION_COPY -> {
                intent.getStringExtra(EXTRA_CODE)?.let { MessageNotifications.copyToClipboard(app, it, true) }
                MessageNotifications.cancel(app, tag)
            }
            ACTION_MARK_READ -> {
                val thread = NotificationSpec.smsThreadId(tag)
                if (thread != null) {
                    val result = goAsync()
                    Thread {
                        try {
                            SmsQuery.markRead(app, thread)
                        } finally {
                            result.finish()
                        }
                    }.start()
                }
                MessageNotifications.cancel(app, tag)
            }
            ACTION_REMIND -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
                val result = goAsync()
                Thread {
                    try {
                        remind(app, tag, title, body)
                    } finally {
                        result.finish()
                    }
                }.start()
            }
        }
    }

    /** "Remind me in 1 hour": Rust builds the reminder, Kotlin arms it and confirms with a toast. */
    private fun remind(context: Context, tag: String, title: String, body: String) {
        val now = System.currentTimeMillis()
        val request = JSONObject().put("tag", tag).put("title", title).put("body", body).put("minutes", 60)
            .put("now_ms", now).put("tz_offset_s", TimeZone.getDefault().getOffset(now) / 1000).toString()
        val answer = Headless.call("msg_remind", request)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val arm = answer?.optJSONObject("arm")
        if (answer == null || !answer.optBoolean("ok") || arm == null) return
        if (!MessageReminders.arm(context, arm.toString())) return
        MessageNotifications.cancel(context, tag)
        val toast = answer.optString("toast", "Reminder set")
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, toast, Toast.LENGTH_SHORT).show() }
        RovstiBridge.emit("message_reminders_changed", "{}")
    }

    private fun replyCold(context: Context, tag: String, text: String) {
        val sms = NotificationSpec.smsThreadId(tag)
        val chat = NotificationSpec.waChat(tag)
        val ok = when {
            sms != null -> {
                val to = SmsQuery.addressesFor(context, sms)
                to.isNotEmpty() && SmsSend.send(context, SmsRequest(System.currentTimeMillis(), to, text, null))
            }
            chat != null -> {
                val request = JSONObject().put("dir", Headless.dataDir(context)).put("chat", chat).put("text", text).toString()
                Headless.call("wa_reply", request)?.let { runCatching { JSONObject(it).optBoolean("ok") }.getOrDefault(false) } == true
            }
            else -> false
        }
        if (ok) {
            val spec = JSONObject().put("tag", tag).put("title", "").put("lines", org.json.JSONArray().put("You: $text"))
                .put("reply", true).put("silent", true)
            // Keep the existing title: the digest has none to hand, so only update when one is shown.
            repostWithSent(context, tag, spec)
        } else {
            MessageNotifications.cancel(context, tag)
        }
    }

    private fun repostWithSent(context: Context, tag: String, spec: JSONObject) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val title = manager?.activeNotifications?.firstOrNull { it.tag == tag }
            ?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        MessageNotifications.show(context, spec.put("title", title).toString())
    }

    companion object {
        const val ACTION_REPLY = "dev.rovsti.bridge.MSG_REPLY"
        const val ACTION_COPY = "dev.rovsti.bridge.MSG_COPY_CODE"
        const val ACTION_MARK_READ = "dev.rovsti.bridge.MSG_MARK_READ"
        const val ACTION_REMIND = "dev.rovsti.bridge.MSG_REMIND"
        const val EXTRA_CODE = "otp_code"
        const val EXTRA_TITLE = "remind_title"
        const val EXTRA_BODY = "remind_body"
    }
}
