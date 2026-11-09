package dev.rovsti.platform.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.rovsti.bridge.ReceiverWork
import dev.rovsti.platform.Headless
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.sms.SmsClock
import dev.rovsti.platform.sms.SmsQuery
import dev.rovsti.platform.sms.SmsRequest
import dev.rovsti.platform.sms.SmsSend
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * What `dev.rovsti.bridge.ReplyReceiver` does (AD-MSG4, AD-MSG5). With the UI attached an inline reply is forwarded as
 * `msg_reply` `{tag, text}` on [PlatformEvents]. Cold, an SMS reply goes straight through [SmsSend] and a WhatsApp reply
 * through `Headless.call("wa_reply")`; the notification is then re-posted with the sent line so the spinner stops.
 * Every `Headless` answer is advisory: with none, the notification stays as it was (the plain notification).
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/ReplyReceiver.kt
 */
object ReplyActions {
    const val REPLY = "dev.rovsti.bridge.MSG_REPLY"
    const val COPY = "dev.rovsti.bridge.MSG_COPY_CODE"
    const val MARK_READ = "dev.rovsti.bridge.MSG_MARK_READ"
    const val REMIND = "dev.rovsti.bridge.MSG_REMIND"
    const val EXTRA_CODE = "otp_code"
    const val EXTRA_TITLE = "remind_title"
    const val EXTRA_BODY = "remind_body"

    /** The `PlatformEvents` kind of a reply typed while the UI is attached. */
    const val EVENT_REPLY = "msg_reply"

    /** `Headless` kinds. */
    const val KIND_REMIND = "msg_remind"
    const val KIND_WA_REPLY = "wa_reply"

    fun handle(
        receiver: BroadcastReceiver,
        context: Context,
        intent: Intent,
    ) {
        val tag = intent.getStringExtra(MessageNotifications.EXTRA_TAG) ?: return
        val app = context.applicationContext
        when (intent.action) {
            REPLY -> {
                val text =
                    RemoteInput
                        .getResultsFromIntent(intent)
                        ?.getCharSequence(MessageNotifications.KEY_REPLY)
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                if (text.isEmpty()) return
                if (PlatformEvents.uiAttached) {
                    PlatformEvents.emit(EVENT_REPLY, JSONObject().put("tag", tag).put("text", text).toString())
                    return
                }
                background(receiver) { replyCold(app, tag, text) }
            }

            COPY -> {
                intent.getStringExtra(EXTRA_CODE)?.let { MessageNotifications.copyToClipboard(app, it, true) }
                MessageNotifications.cancel(app, tag)
            }

            MARK_READ -> {
                val thread = NotificationSpec.smsThreadId(tag)
                if (thread != null) background(receiver) { SmsQuery.markRead(app, thread) }
                MessageNotifications.cancel(app, tag)
            }

            REMIND -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
                background(receiver) { remind(app, tag, title, body) }
            }
        }
    }

    private fun background(
        receiver: BroadcastReceiver,
        block: () -> Unit,
    ) {
        val result = receiver.goAsync()
        ReceiverWork.run {
            try {
                block()
            } finally {
                result?.finish()
            }
        }
    }

    /** The `msg_remind` request Rust gets (a `MsgRemindRequest`). */
    fun remindRequest(
        tag: String,
        title: String,
        body: String,
        nowMs: Long,
        tzOffsetS: Int,
    ): String =
        JSONObject()
            .put("tag", tag)
            .put("title", title)
            .put("body", body)
            .put("minutes", 60)
            .put("now_ms", nowMs)
            .put("tz_offset_s", tzOffsetS)
            .toString()

    /** The `wa_reply` request Rust gets (a `WaReplyRequest`). */
    fun waReplyRequest(
        dir: String,
        chat: String,
        text: String,
    ): String =
        JSONObject()
            .put("dir", dir)
            .put("chat", chat)
            .put("text", text)
            .toString()

    /** "Remind me in 1 hour": Rust builds the reminder, Kotlin arms it and confirms with a toast. */
    internal fun remind(
        context: Context,
        tag: String,
        title: String,
        body: String,
    ) {
        val now = SmsClock.nowMillis()
        val request = remindRequest(tag, title, body, now, TimeZone.getDefault().getOffset(now) / 1000)
        val answer = Headless.call(KIND_REMIND, request)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val arm = answer?.optJSONObject("arm")
        // No decision: the notification stays as it is.
        if (answer == null || !answer.optBoolean("ok") || arm == null) return
        if (!MessageReminders.arm(context, arm.toString())) return
        MessageNotifications.cancel(context, tag)
        val toast = answer.optString("toast", "Reminder set")
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, toast, Toast.LENGTH_SHORT).show() }
        PlatformEvents.emit(MessageReminders.EVENT_CHANGED, "{}")
    }

    internal fun replyCold(
        context: Context,
        tag: String,
        text: String,
    ) {
        val sms = NotificationSpec.smsThreadId(tag)
        val chat = NotificationSpec.waChat(tag)
        val ok =
            when {
                sms != null -> {
                    val to = SmsQuery.addressesFor(context, sms)
                    to.isNotEmpty() && SmsSend.send(context, SmsRequest(SmsClock.nowMillis(), to, text, null))
                }

                chat != null -> {
                    val request = waReplyRequest(Headless.dataDir(context), chat, text)
                    Headless
                        .call(
                            KIND_WA_REPLY,
                            request,
                        )?.let { runCatching { JSONObject(it).optBoolean("ok") }.getOrDefault(false) } ==
                        true
                }

                else -> {
                    false
                }
            }
        if (ok) {
            val spec =
                JSONObject()
                    .put("tag", tag)
                    .put("title", "")
                    .put("lines", JSONArray().put("You: $text"))
                    .put("reply", true)
                    .put("silent", true)
            repostWithSent(context, tag, spec)
        } else {
            repostPlain(context, tag)
        }
    }

    private fun repostWithSent(
        context: Context,
        tag: String,
        spec: JSONObject,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Keep the existing title: the digest has none to hand, so only update when one is shown.
        val title =
            manager
                ?.activeNotifications
                ?.firstOrNull { it.tag == tag }
                ?.notification
                ?.extras
                ?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()
                .orEmpty()
        MessageNotifications.show(context, spec.put("title", title).toString())
    }

    /** The reply failed or Rust gave no answer: post the notification again unchanged so the reply spinner stops. */
    private fun repostPlain(
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
