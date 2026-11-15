package dev.rosty.messages

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import dev.rosty.core.log.RostyLog
import dev.rosty.platform.Headless
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.MessageNotifications
import dev.rosty.platform.sms.IncomingNotice
import dev.rosty.platform.sms.SmsClock
import dev.rosty.platform.sms.Verdict
import org.json.JSONObject

/**
 * The default SMS app's `SMS_DELIVER` receiver (AD-MSG4). It may run with no activity and no UI process.
 *
 * Order matters: the message is written to `Telephony.Sms.Inbox` first (the default app must, or it is lost), then Rust
 * classifies it (`sms_classify`: spam rules and passcode), then the notification is posted unless the message is spam,
 * then the UI is told. If the library cannot be loaded the answer is empty and the message is notified plainly: no
 * passcode chip, no spam filter.
 *
 * Ported from apps/messages/platforms/android/app/src/main/kotlin/dev/rosty/messages/SmsDeliverReceiver.kt
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
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
                handle(app, Incoming(sender, body, sentAt, subId))
            } catch (e: RuntimeException) {
                RostyLog.e(TAG, "An incoming SMS could not be handled", e)
            } finally {
                result.finish()
            }
        }.start()
    }

    private data class Incoming(
        val sender: String,
        val body: String,
        val sentAt: Long,
        val subId: Int?,
    )

    private fun handle(
        context: Context,
        sms: Incoming,
    ) {
        val now = SmsClock.nowMillis()
        val row = store(context, sms, now)
        val threadId = row?.let { threadOf(context, it) } ?: 0L
        val name = IncomingNotice.contactName(context, sms.sender)
        val verdict =
            Verdict.parse(
                Headless.call(
                    "sms_classify",
                    IncomingNotice.classifyRequest(
                        Headless.dataDir(context),
                        sms.sender,
                        sms.body,
                        name != null,
                        IncomingNotice.isBlocked(context, sms.sender),
                    ),
                ),
            )
        if (!verdict.spam && threadId != 0L) {
            val lines = IncomingNotice.lastLines(unreadBodies(context, threadId), sms.body)
            MessageNotifications.show(context, IncomingNotice.spec(threadId, name ?: sms.sender, lines, verdict.otp))
        }
        PlatformEvents.emit(
            "sms_received",
            JSONObject()
                .put("thread_id", threadId)
                .put("address", sms.sender)
                .put("body", sms.body)
                .put("date_ms", now)
                .toString(),
        )
        PlatformEvents.emit("sms_changed", "{}")
    }

    /** Writes the Inbox row; null when the provider refuses (the app lost the role meanwhile). */
    private fun store(
        context: Context,
        sms: Incoming,
        now: Long,
    ): Uri? =
        try {
            val values =
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sms.sender)
                    put(Telephony.Sms.BODY, sms.body)
                    put(Telephony.Sms.DATE, now)
                    put(Telephony.Sms.DATE_SENT, sms.sentAt)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                    sms.subId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
                }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (_: RuntimeException) {
            null
        }

    private fun threadOf(
        context: Context,
        row: Uri,
    ): Long =
        try {
            context.contentResolver.query(row, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
        } catch (_: RuntimeException) {
            0L
        }

    /** Unread inbox bodies of the thread, oldest first, so the digest keeps earlier messages visible. */
    private fun unreadBodies(
        context: Context,
        threadId: Long,
    ): List<String> =
        try {
            context.contentResolver
                .query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(Telephony.Sms.BODY),
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                    arrayOf(threadId.toString()),
                    "${Telephony.Sms.DATE} DESC LIMIT $DIGEST_LINES",
                )?.use { c ->
                    val out = ArrayList<String>()
                    while (c.moveToNext()) c.getString(0)?.let(out::add)
                    out.reversed()
                } ?: emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }

    private companion object {
        const val TAG = "messages"
        const val DIGEST_LINES = 5
    }
}
