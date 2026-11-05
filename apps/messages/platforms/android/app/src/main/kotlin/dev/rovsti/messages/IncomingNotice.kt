package dev.rovsti.messages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject

/** What Rust answered for `sms_classify` / `mms_retrieved`; no answer is ham with no code (plain notification). */
data class Verdict(val spam: Boolean, val otp: String?) {
    companion object {
        val HAM = Verdict(false, null)

        /** Pure: the verdict in [answer], or [HAM] for null, blank or malformed JSON. */
        fun parse(answer: String?): Verdict {
            if (answer.isNullOrBlank()) return HAM
            return try {
                val o = JSONObject(answer)
                Verdict(o.optBoolean("spam", false), if (o.isNull("otp")) null else o.optString("otp", "").takeIf { it.isNotEmpty() })
            } catch (_: org.json.JSONException) {
                HAM
            }
        }
    }
}

/** Shared pieces of the cold receive paths: requests to Rust, notification specs and contact lookups. */
object IncomingNotice {
    /** The SIM a broadcast arrived on, or null when the intent does not say. */
    fun subscriptionOf(intent: Intent): Int? {
        val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val id = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", invalid))
        return id.takeIf { it != invalid }
    }

    /** The `sms_classify` request. */
    fun classifyRequest(dir: String, sender: String, body: String, known: Boolean, blocked: Boolean): String =
        JSONObject().put("dir", dir).put("sender", sender).put("body", body)
            .put("known", known).put("blocked", blocked).toString()

    /**
     * The `rovsti_core::notify::NotificationSpec` JSON that `MessageNotifications.show` renders: tag
     * `sms:<thread>`, inline reply unless the message carries a passcode, "Copy code" when it does.
     */
    fun spec(threadId: Long, title: String, lines: List<String>, otp: String?): String {
        val o = JSONObject().put("tag", "sms:$threadId").put("title", title)
            .put("lines", JSONArray(lines)).put("reply", otp == null).put("silent", false)
        otp?.let { o.put("otp", it) }
        return o.toString()
    }

    /** The newest [max] lines, oldest first; [fallback] when the thread query found nothing. */
    fun lastLines(unread: List<String>, fallback: String, max: Int = 5): List<String> =
        unread.takeLast(max).ifEmpty { listOf(fallback) }

    /** The line shown for an MMS with no text. */
    fun attachmentLine(contentTypes: List<String>): String = when {
        contentTypes.any { it.startsWith("image/") } -> "Photo"
        contentTypes.any { it.startsWith("video/") } -> "Video"
        contentTypes.any { it.startsWith("audio/") } -> "Audio"
        else -> "Attachment"
    }

    /** Contact display name for [number] (`PhoneLookup`), or null when it is not a contact or access is denied. */
    fun contactName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.takeIf { name -> name.isNotBlank() } else null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Whether the system blocklist holds [number]. False when this app may not read it. */
    fun isBlocked(context: Context, number: String): Boolean = try {
        number.isNotBlank() && BlockedNumberContract.isBlocked(context, number)
    } catch (_: RuntimeException) {
        false
    }
}
