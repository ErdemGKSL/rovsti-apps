package dev.rovsti.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Base64
import dev.rovsti.bridge.Headless
import dev.rovsti.bridge.MmsCodec
import org.json.JSONObject

/** What `nativeHeadless("mms_notification")` decoded from an `m-notification-ind`. */
data class MmsAnnouncement(val transactionId: String, val location: String) {
    companion object {
        /** Pure: null when the answer is missing, `ok:false`, or has no download location. */
        fun parse(answer: String?): MmsAnnouncement? {
            if (answer.isNullOrBlank()) return null
            return try {
                val o = JSONObject(answer)
                val location = o.optString("content_location", "")
                if (!o.optBoolean("ok", false) || location.isEmpty()) null else MmsAnnouncement(o.optString("transaction_id", ""), location)
            } catch (_: org.json.JSONException) {
                null
            }
        }
    }
}

/**
 * The default SMS app's `WAP_PUSH_DELIVER` receiver (AD-MSG4). The carrier's `m-notification-ind` is decoded in
 * Rust (`mms_notification`), downloaded with `SmsManager.downloadMultimediaMessage` through [MmsCodec], and the
 * result reaches `MmsResultReceiver`, which hands it to [MmsInbox] (`mms_retrieved`, `Telephony.Mms`, notify).
 * Without the native library nothing can be decoded and the push is ignored.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data") ?: return
        val subId = IncomingNotice.subscriptionOf(intent)
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                val request = JSONObject().put("pdu_b64", Base64.encodeToString(pdu, Base64.NO_WRAP)).toString()
                MmsAnnouncement.parse(Headless.call("mms_notification", request))?.let {
                    MmsCodec.download(app, System.currentTimeMillis(), it.location, subId)
                }
            } finally {
                result.finish()
            }
        }.start()
    }
}
