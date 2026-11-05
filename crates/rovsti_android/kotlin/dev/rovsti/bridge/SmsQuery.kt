package dev.rovsti.bridge

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pulls from the Telephony provider (AD-MSG2). Every function returns an envelope `{v, ok, data | error}`
 * (`rovsti_core::wire::Envelope`) and never throws. Rust re-pulls on `sms_changed`. Blocking: off the
 * main thread. The pure JSON builders are in [SmsJson].
 */
object SmsQuery {
    private val THREADS_URI: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
    private val ADDRESSES_URI: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    private val MMS_PART_URI: Uri = Uri.parse("content://mms/part")

    fun threadsJson(context: Context, limit: Int): String = guarded {
        val unread = unreadByThread(context)
        val canonical = canonicalAddresses(context)
        val rows = ArrayList<JSONObject>()
        context.contentResolver.query(
            THREADS_URI, arrayOf("_id", "date", "recipient_ids", "snippet"), null, null,
            "date DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < limit.coerceAtLeast(0)) {
                val id = c.getLong(0)
                val addresses = SmsJson.splitIds(c.getString(2)).mapNotNull { canonical[it] }
                rows.add(SmsJson.thread(id, addresses, c.getString(3) ?: "", c.getLong(1), unread[id] ?: 0))
            }
        }
        JSONObject().put("threads", JSONArray(rows))
    }

    fun messagesJson(context: Context, threadId: Long, beforeMs: Long?, limit: Int): String = guarded {
        val n = limit.coerceAtLeast(1)
        val sms = ArrayList<JSONObject>()
        val smsSel = StringBuilder("thread_id = ?")
        val smsArgs = arrayListOf(threadId.toString())
        if (beforeMs != null) {
            smsSel.append(" AND date < ?")
            smsArgs.add(beforeMs.toString())
        }
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id", "status"),
            smsSel.toString(), smsArgs.toTypedArray(), "date DESC LIMIT ${n + 1}",
        )?.use { c ->
            while (c.moveToNext()) {
                sms.add(
                    SmsJson.sms(
                        c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4),
                        c.getInt(5), c.getInt(6) != 0, c.getInt(7).takeIf { it >= 0 }, c.getInt(8),
                    ),
                )
            }
        }
        val mms = ArrayList<JSONObject>()
        // MMS dates are in seconds.
        val mmsSel = StringBuilder("thread_id = ?")
        val mmsArgs = arrayListOf(threadId.toString())
        if (beforeMs != null) {
            mmsSel.append(" AND date < ?")
            mmsArgs.add((beforeMs / 1000).toString())
        }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf("_id", "thread_id", "date", "msg_box", "read", "sub_id"),
            mmsSel.toString(), mmsArgs.toTypedArray(), "date DESC LIMIT ${n + 1}",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val (from, to) = mmsAddresses(context, id)
                mms.add(
                    SmsJson.mms(
                        id, c.getLong(1), to, from, c.getLong(2) * 1000, c.getInt(3) != Telephony.Mms.MESSAGE_BOX_INBOX,
                        c.getInt(4) != 0, mmsParts(context, id), c.getInt(5).takeIf { it >= 0 },
                    ),
                )
            }
        }
        SmsJson.page(sms, mms, n)
    }

    @SuppressLint("MissingPermission")
    fun simsJson(context: Context): String = guarded {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val sims = JSONArray()
        manager?.activeSubscriptionInfoList?.forEach { info ->
            val label = (info.displayName?.toString() ?: "").ifEmpty { "SIM ${info.simSlotIndex + 1}" }
            sims.put(SmsJson.sim(info.subscriptionId, info.simSlotIndex.takeIf { it >= 0 }, label, info.iconTint))
        }
        val data = JSONObject().put("sims", sims)
        SubscriptionManager.getDefaultSmsSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?.let { data.put("default_sub_id", it) }
        data
    }

    /** Marks the thread's incoming SMS and MMS read. */
    fun markRead(context: Context, threadId: Long): Boolean = try {
        val values = ContentValues().apply { put("read", 1); put("seen", 1) }
        val args = arrayOf(threadId.toString())
        context.contentResolver.update(Telephony.Sms.CONTENT_URI, values, "thread_id = ? AND read = 0", args)
        context.contentResolver.update(Telephony.Mms.CONTENT_URI, values, "thread_id = ? AND read = 0", args)
        true
    } catch (_: RuntimeException) {
        false
    }

    /** Deletes the conversation (needs the SMS role). */
    fun deleteThread(context: Context, threadId: Long): Boolean = try {
        context.contentResolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null)
        true
    } catch (_: RuntimeException) {
        false
    }

    /** The recipients of a thread, for a cold inline reply. */
    fun addressesFor(context: Context, threadId: Long): List<String> {
        val canonical = canonicalAddresses(context)
        context.contentResolver.query(
            THREADS_URI, arrayOf("recipient_ids"), "_id = ?", arrayOf(threadId.toString()), null,
        )?.use { c ->
            if (c.moveToFirst()) return SmsJson.splitIds(c.getString(0)).mapNotNull { canonical[it] }
        }
        return emptyList()
    }

    /**
     * Restores `{"sms":[SmsRow..]}` from a backup, skipping rows already present (same address, date and
     * body). Answers `{"inserted":n,"skipped":m}`; `{"inserted":0,"skipped":0,"error":"role"}` without the SMS role.
     */
    fun insertRestored(context: Context, json: String): String {
        var inserted = 0
        var skipped = 0
        val rows = try {
            JSONObject(json).optJSONArray("sms") ?: JSONArray()
        } catch (_: org.json.JSONException) {
            JSONArray()
        }
        try {
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val address = o.optString("address", "")
                val body = o.optString("body", "")
                val date = o.optLong("date_ms", 0)
                if (address.isEmpty() || exists(context, address, date, body)) {
                    skipped++
                    continue
                }
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, date)
                    put(Telephony.Sms.TYPE, SmsJson.boxCode(o.opt("box")))
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                    if (o.has("sub_id") && !o.isNull("sub_id")) put(Telephony.Sms.SUBSCRIPTION_ID, o.optInt("sub_id"))
                }
                if (context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) != null) inserted++ else skipped++
            }
        } catch (_: SecurityException) {
            return JSONObject().put("inserted", inserted).put("skipped", skipped).put("error", "role").toString()
        }
        return JSONObject().put("inserted", inserted).put("skipped", skipped).toString()
    }

    private fun exists(context: Context, address: String, date: Long, body: String): Boolean =
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id"), "address = ? AND date = ? AND body = ?",
            arrayOf(address, date.toString(), body), null,
        )?.use { it.moveToFirst() } == true

    private fun unreadByThread(context: Context): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        for ((uri, sel) in listOf(
            Telephony.Sms.CONTENT_URI to "read = 0 AND type = 1",
            Telephony.Mms.CONTENT_URI to "read = 0 AND msg_box = 1",
        )) {
            context.contentResolver.query(uri, arrayOf("thread_id"), sel, null, null)?.use { c ->
                while (c.moveToNext()) counts.merge(c.getLong(0), 1, Int::plus)
            }
        }
        return counts
    }

    private fun canonicalAddresses(context: Context): Map<String, String> {
        val map = HashMap<String, String>()
        context.contentResolver.query(ADDRESSES_URI, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getString(1) ?: ""
        }
        return map
    }

    /** `(from, recipients)` of an MMS: address types 137 (FROM) and 151 (TO). */
    private fun mmsAddresses(context: Context, id: Long): Pair<String?, List<String>> {
        var from: String? = null
        val to = ArrayList<String>()
        context.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0) ?: continue
                if (address == "insert-address-token") continue
                if (c.getInt(1) == 137) from = address else to.add(address)
            }
        }
        return from to to
    }

    /** Text parts inline, binary parts copied to the cache so only a path crosses the seam. */
    private fun mmsParts(context: Context, mmsId: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        context.contentResolver.query(
            MMS_PART_URI, arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(mmsId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLong(0)
                val type = c.getString(1) ?: ""
                when {
                    type == "application/smil" -> Unit
                    type.startsWith("text/") -> out.add(SmsJson.part(type, text = c.getString(2) ?: ""))
                    else -> out.add(SmsJson.part(type, path = cachePart(context, partId, type)))
                }
            }
        }
        return out
    }

    private fun cachePart(context: Context, partId: Long, type: String): String? = try {
        val dir = File(context.cacheDir, "mms_parts").apply { mkdirs() }
        val file = File(dir, "$partId.${SmsJson.extension(type)}")
        if (!file.exists()) {
            context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        if (file.exists()) file.absolutePath else null
    } catch (_: java.io.IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun guarded(block: () -> JSONObject): String = try {
        SmsJson.ok(block())
    } catch (_: SecurityException) {
        SmsJson.error("permission")
    } catch (_: RuntimeException) {
        SmsJson.error("unknown")
    }
}

/** Pure JSON builders for [SmsQuery] (org.json only, so the JVM tests run them). */
object SmsJson {
    fun ok(data: JSONObject): String = JSONObject().put("v", WIRE_VERSION).put("ok", true).put("data", data).toString()

    fun error(code: String): String = JSONObject().put("v", WIRE_VERSION).put("ok", false).put("error", code).toString()

    /** `recipient_ids` is a space-separated list of canonical-address ids. */
    fun splitIds(raw: String?): List<String> = raw.orEmpty().trim().split(' ').filter { it.isNotEmpty() }

    fun thread(id: Long, addresses: List<String>, snippet: String, dateMs: Long, unread: Int): JSONObject =
        JSONObject().put("thread_id", id).put("addresses", JSONArray(addresses)).put("snippet", snippet)
            .put("date_ms", dateMs).put("unread", unread)

    /** `Telephony.Sms.STATUS`: 0 complete (delivered), 32 pending, 64 failed. */
    fun delivery(box: Int, status: Int): String = when {
        box == 5 || status == 64 -> "failed"
        status == 0 && box == 2 -> "delivered"
        box == 4 || box == 6 || status == 32 -> "pending"
        box == 2 -> "sent"
        else -> "none"
    }

    fun sms(
        id: Long, threadId: Long, address: String, body: String, dateMs: Long, box: Int, read: Boolean, subId: Int?, status: Int,
    ): JSONObject {
        val o = JSONObject().put("id", id).put("thread_id", threadId).put("address", address).put("body", body)
            .put("date_ms", dateMs).put("box", box).put("read", read).put("delivery", delivery(box, status))
        subId?.let { o.put("sub_id", it) }
        return o
    }

    fun part(type: String, text: String? = null, path: String? = null): JSONObject {
        val o = JSONObject().put("content_type", type)
        text?.let { o.put("text", it) }
        path?.let { o.put("path", it) }
        return o
    }

    fun mms(
        id: Long, threadId: Long, addresses: List<String>, from: String?, dateMs: Long, outgoing: Boolean,
        read: Boolean, parts: List<JSONObject>, subId: Int?,
    ): JSONObject {
        val o = JSONObject().put("id", id).put("thread_id", threadId).put("addresses", JSONArray(addresses))
            .put("date_ms", dateMs).put("outgoing", outgoing).put("read", read).put("parts", JSONArray(parts))
        from?.let { o.put("from", it) }
        subId?.let { o.put("sub_id", it) }
        return o
    }

    fun sim(subId: Int, slot: Int?, label: String, color: Int): JSONObject {
        val o = JSONObject().put("sub_id", subId).put("label", label).put("color_argb", color)
        slot?.let { o.put("slot_index", it) }
        return o
    }

    /** A page from up to `n + 1` rows of each kind (newest first): the newest [n] overall, and `has_older`. */
    fun page(sms: List<JSONObject>, mms: List<JSONObject>, n: Int): JSONObject {
        val all = (sms + mms).sortedByDescending { it.optLong("date_ms") }
        val kept = all.take(n)
        val keptSms = kept.filter { it.has("address") }
        val keptMms = kept.filter { !it.has("address") }
        return JSONObject().put("sms", JSONArray(keptSms)).put("mms", JSONArray(keptMms)).put("has_older", all.size > n)
    }

    /** The Telephony `TYPE` of a backup row's `box` (a code or a name). */
    fun boxCode(box: Any?): Int = when (box) {
        is Number -> box.toInt().coerceIn(1, 6)
        "sent" -> 2
        "draft" -> 3
        "outbox" -> 4
        "failed" -> 5
        "queued" -> 6
        else -> 1
    }

    fun extension(contentType: String): String = when (contentType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "audio/mpeg" -> "mp3"
        else -> "bin"
    }
}
