package dev.rovsti.messages

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import dev.rovsti.bridge.Headless
import dev.rovsti.bridge.MessageNotifications
import dev.rovsti.bridge.RovstiBridge
import org.json.JSONObject

/**
 * The default SMS app's `SMS_DELIVER` receiver (AD-MSG4). It may run with no activity and no UI process.
 *
 * Order matters: the message is written to `Telephony.Sms.Inbox` first (the default app must, or it is lost),
 * then Rust classifies it (`sms_classify`: spam rules and passcode), then the notification is posted unless the
 * message is spam, then the UI is told. If the library cannot be loaded the answer is empty and the message is
 * notified plainly: no passcode chip, no spam filter.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pieces: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (pieces.isEmpty()) return
        val sender = pieces[0].displayOriginatingAddress.orEmpty()
        val body = pieces.joinToString("") { it.displayMessageBody.orEmpty() }
        val sentAt = pieces[0].timestampMillis
        val subId = IncomingNotice.subscriptionOf(intent)
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                handle(app, sender, body, sentAt, subId)
            } finally {
                result.finish()
            }
        }.start()
    }

    private fun handle(context: Context, sender: String, body: String, sentAt: Long, subId: Int?) {
        val now = System.currentTimeMillis()
        val row = store(context, sender, body, now, sentAt, subId)
        val threadId = row?.let { threadOf(context, it) } ?: 0L
        val name = IncomingNotice.contactName(context, sender)
        val verdict = Verdict.parse(
            Headless.call(
                "sms_classify",
                IncomingNotice.classifyRequest(Headless.dataDir(context), sender, body, name != null, IncomingNotice.isBlocked(context, sender)),
            ),
        )
        if (!verdict.spam && threadId != 0L) {
            val lines = IncomingNotice.lastLines(unreadBodies(context, threadId), body)
            MessageNotifications.show(context, IncomingNotice.spec(threadId, name ?: sender, lines, verdict.otp))
        }
        RovstiBridge.emit(
            "sms_received",
            JSONObject().put("thread_id", threadId).put("address", sender).put("body", body).put("date_ms", now).toString(),
        )
        RovstiBridge.emit("sms_changed", "{}")
    }

    /** Writes the Inbox row; null when the provider refuses (the app lost the role meanwhile). */
    private fun store(context: Context, sender: String, body: String, now: Long, sentAt: Long, subId: Int?): Uri? = try {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            subId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    } catch (_: RuntimeException) {
        null
    }

    private fun threadOf(context: Context, row: Uri): Long = try {
        context.contentResolver.query(row, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    } catch (_: RuntimeException) {
        0L
    }

    /** Unread inbox bodies of the thread, oldest first, so the digest keeps earlier messages visible. */
    private fun unreadBodies(context: Context, threadId: Long): List<String> = try {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.BODY),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT 5",
        )?.use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) c.getString(0)?.let(out::add)
            out.reversed()
        } ?: emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }
}
