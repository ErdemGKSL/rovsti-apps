package dev.rovsti.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** A send request (`rovsti_core::sms::SmsSend`): `{local_id, to[], body, sub_id?}`. */
data class SmsRequest(val localId: Long, val to: List<String>, val body: String, val subId: Int?) {
    companion object {
        /** Pure: null for malformed JSON, no recipient or an empty body. */
        fun parse(json: String): SmsRequest? = try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("to") ?: JSONArray()
            val to = (0 until arr.length()).map { arr.optString(it, "").trim() }.filter { it.isNotEmpty() }
            val body = o.optString("body", "")
            val sub = if (o.has("sub_id") && !o.isNull("sub_id")) o.optInt("sub_id") else null
            if (to.isEmpty() || body.isEmpty()) null else SmsRequest(o.optLong("local_id", 0), to, body, sub)
        } catch (_: org.json.JSONException) {
            null
        }
    }
}

/**
 * SMS sending (AD-MSG2). The send is queued and returns at once; the sent and delivery
 * `PendingIntent`s reach [SmsStatusReceiver], which emits `sms_status` and updates the provider.
 * With the SMS role the message is first written to Outbox, then moved to Sent or Failed.
 */
object SmsSend {
    const val ACTION_STATUS = "dev.rovsti.bridge.SMS_STATUS"
    const val EXTRA_LOCAL_ID = "local_id"
    const val EXTRA_URI = "uri"
    const val EXTRA_KIND = "kind"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"
    const val KIND_SENT = "sent"
    const val KIND_DELIVERY = "delivery"

    private val requestCodes = AtomicInteger(1)

    /** Pure: the `sms_status` payload. */
    fun statusJson(localId: Long, uri: String?, state: String, errorCode: Int? = null): String {
        val o = JSONObject().put("local_id", localId).put("state", state)
        uri?.let { o.put("uri", it) }
        errorCode?.let { o.put("error_code", it) }
        return o.toString()
    }

    /**
     * Pure: the state a status broadcast means, or null while more parts are outstanding. A failed
     * part fails the message at once; success counts when the last part reports in.
     */
    fun stateFor(kind: String, ok: Boolean, part: Int, parts: Int): String? = when {
        !ok -> "failed"
        part < parts - 1 -> null
        kind == KIND_DELIVERY -> "delivered"
        else -> "sent"
    }

    /** `Telephony.Sms.STATUS` for [state]. */
    fun providerStatus(state: String): Int = when (state) {
        "delivered" -> Telephony.Sms.STATUS_COMPLETE
        "failed" -> Telephony.Sms.STATUS_FAILED
        else -> Telephony.Sms.STATUS_PENDING
    }

    /** `Telephony.Sms.TYPE` for [state]; null leaves it unchanged. */
    fun providerType(state: String): Int? = when (state) {
        "sent", "delivered" -> Telephony.Sms.MESSAGE_TYPE_SENT
        "failed" -> Telephony.Sms.MESSAGE_TYPE_FAILED
        else -> null
    }

    private fun manager(context: Context, subId: Int?): SmsManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java) ?: return null
            if (subId == null) base else base.createForSubscriptionId(subId)
        } else {
            @Suppress("DEPRECATION")
            if (subId == null) SmsManager.getDefault() else SmsManager.getSmsManagerForSubscriptionId(subId)
        }
    }

    /** Queues the send of [json] (an [SmsRequest]); false when it could not be queued. */
    fun send(context: Context, json: String): Boolean = SmsRequest.parse(json)?.let { send(context, it) } ?: false

    fun send(context: Context, request: SmsRequest): Boolean {
        val sms = manager(context, request.subId) ?: return false
        var queued = false
        for (to in request.to) {
            val uri = insertOutbox(context, to, request.body, request.subId)
            try {
                val parts = sms.divideMessage(request.body)
                val sent = ArrayList<PendingIntent>(parts.size)
                val delivery = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    sent.add(statusIntent(context, request.localId, uri, KIND_SENT, i, parts.size))
                    delivery.add(statusIntent(context, request.localId, uri, KIND_DELIVERY, i, parts.size))
                }
                if (parts.size == 1) {
                    sms.sendTextMessage(to, null, parts[0], sent[0], delivery[0])
                } else {
                    sms.sendMultipartTextMessage(to, null, parts, sent, delivery)
                }
                queued = true
            } catch (_: RuntimeException) {
                // IllegalArgumentException (bad number) or SecurityException (no SEND_SMS).
                updateProvider(context, uri, "failed")
                RovstiBridge.emit("sms_status", statusJson(request.localId, uri?.toString(), "failed", -1))
            }
        }
        return queued
    }

    /** A scheduled item fired by its alarm; true when every recipient was queued. */
    fun sendScheduled(context: Context, item: ScheduledItem): Boolean =
        send(context, SmsRequest(item.id, listOf(item.to), item.body, item.subId))

    private fun statusIntent(context: Context, localId: Long, uri: Uri?, kind: String, part: Int, parts: Int): PendingIntent {
        val intent = Intent(ACTION_STATUS).setClass(context, SmsStatusReceiver::class.java)
            .putExtra(EXTRA_LOCAL_ID, localId).putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_PART, part).putExtra(EXTRA_PARTS, parts)
        uri?.let { intent.putExtra(EXTRA_URI, it.toString()) }
        // Unique per send, or the system folds every status into one PendingIntent and loses extras.
        return PendingIntent.getBroadcast(
            context, requestCodes.getAndIncrement(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Writes an Outbox row; null when Rovsti does not hold the SMS role (the send still goes out). */
    private fun insertOutbox(context: Context, to: String, body: String, subId: Int?): Uri? = try {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, to)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
            subId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
    } catch (_: RuntimeException) {
        null
    }

    fun updateProvider(context: Context, uri: Uri?, state: String) {
        uri ?: return
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.STATUS, providerStatus(state))
                providerType(state)?.let { put(Telephony.Sms.TYPE, it) }
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (_: RuntimeException) {
            // Not the default SMS app any more: the provider row stays as it was.
        }
    }
}

/** Receives the sent and delivery broadcasts of [SmsSend]; declare it (not exported) in the host manifest. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(SmsSend.EXTRA_KIND) ?: return
        val ok = resultCode == android.app.Activity.RESULT_OK
        val part = intent.getIntExtra(SmsSend.EXTRA_PART, 0)
        val parts = intent.getIntExtra(SmsSend.EXTRA_PARTS, 1)
        val state = SmsSend.stateFor(kind, ok, part, parts) ?: return
        val localId = intent.getLongExtra(SmsSend.EXTRA_LOCAL_ID, 0)
        val uri = intent.getStringExtra(SmsSend.EXTRA_URI)
        SmsSend.updateProvider(context, uri?.let(Uri::parse), state)
        RovstiBridge.emit("sms_status", SmsSend.statusJson(localId, uri, state, if (ok) null else resultCode))
    }
}
