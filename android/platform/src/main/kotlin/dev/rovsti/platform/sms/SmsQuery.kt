package dev.rovsti.platform.sms

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import dev.rovsti.core.model.messages.DeliveryState
import dev.rovsti.core.model.messages.MmsPart
import dev.rovsti.core.model.messages.MmsRow
import dev.rovsti.core.model.messages.SmsKey
import dev.rovsti.core.model.messages.SmsMessagesPayload
import dev.rovsti.core.model.messages.SmsRow
import dev.rovsti.core.model.messages.SmsThreadRow
import java.io.File

/** One active SIM as the compose screen's SIM chooser needs it (the old `sims` envelope row). */
data class SmsSim(
    val subId: Int,
    val slotIndex: Int?,
    val label: String,
    val colorArgb: Int,
)

/** The active SIMs and the one the user chose for SMS, when the system says. */
data class SmsSims(
    val sims: List<SmsSim>,
    val defaultSubId: Int?,
)

/** What [SmsQuery.insertRestored] did. [roleMissing] is true when the provider refused for lack of the SMS role. */
data class RestoreOutcome(
    val inserted: Int,
    val skipped: Int,
    val roleMissing: Boolean = false,
)

/**
 * Pulls from the Telephony provider (AD-MSG2). Rows are typed (`SmsThreadRow`, `SmsRow`, `MmsRow`: the shapes
 * `rovsti_core::sms` reads). A read the app may not make throws [SecurityException]; [AndroidSmsRepository] maps it.
 * Blocking: off the main thread. The pure rules are in [SmsRules].
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/SmsQuery.kt
 */
object SmsQuery {
    private val THREADS_URI: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
    private val ADDRESSES_URI: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    private val MMS_PART_URI: Uri = Uri.parse("content://mms/part")

    private val SMS_PROJECTION =
        arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id", "status")

    /** Conversations, newest first, at most [limit]. */
    fun threads(
        context: Context,
        limit: Int,
    ): List<SmsThreadRow> {
        val unread = unreadByThread(context)
        val canonical = canonicalAddresses(context)
        val rows = ArrayList<SmsThreadRow>()
        context.contentResolver
            .query(THREADS_URI, arrayOf("_id", "date", "recipient_ids", "snippet"), null, null, "date DESC")
            ?.use { c ->
                while (c.moveToNext() && rows.size < limit.coerceAtLeast(0)) {
                    val id = c.getLong(0)
                    val addresses = SmsRules.splitIds(c.getString(2)).mapNotNull { canonical[it] }
                    rows += SmsThreadRow(id, addresses, c.getString(3) ?: "", c.getLong(1), unread[id] ?: 0)
                }
            }
        return rows
    }

    /** A page of one thread, newest [limit] messages older than [beforeMs] (null: from the newest). */
    fun messages(
        context: Context,
        threadId: Long,
        beforeMs: Long?,
        limit: Int,
    ): SmsMessagesPayload {
        val n = limit.coerceAtLeast(1)
        val smsSel = StringBuilder("thread_id = ?")
        val smsArgs = arrayListOf(threadId.toString())
        if (beforeMs != null) {
            smsSel.append(" AND date < ?")
            smsArgs.add(beforeMs.toString())
        }
        val sms = readSms(context, smsSel.toString(), smsArgs.toTypedArray(), n + 1)
        val mms = ArrayList<MmsRow>()
        // MMS dates are in seconds.
        val mmsSel = StringBuilder("thread_id = ?")
        val mmsArgs = arrayListOf(threadId.toString())
        if (beforeMs != null) {
            mmsSel.append(" AND date < ?")
            mmsArgs.add((beforeMs / 1000).toString())
        }
        context.contentResolver
            .query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("_id", "thread_id", "date", "msg_box", "read", "sub_id"),
                mmsSel.toString(),
                mmsArgs.toTypedArray(),
                "date DESC LIMIT ${n + 1}",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val (from, to) = mmsAddresses(context, id)
                    mms +=
                        MmsRow(
                            id = id,
                            threadId = c.getLong(1),
                            addresses = to,
                            from = from,
                            dateMs = c.getLong(2) * 1000,
                            outgoing = c.getInt(3) != Telephony.Mms.MESSAGE_BOX_INBOX,
                            read = c.getInt(4) != 0,
                            parts = mmsParts(context, id),
                            subId = c.getInt(5).takeIf { it >= 0 },
                        )
                }
            }
        return SmsRules.page(sms, mms, n)
    }

    @SuppressLint("MissingPermission")
    fun sims(context: Context): SmsSims {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val sims =
            manager?.activeSubscriptionInfoList.orEmpty().map { info ->
                val label = (info.displayName?.toString() ?: "").ifEmpty { "SIM ${info.simSlotIndex + 1}" }
                SmsSim(info.subscriptionId, info.simSlotIndex.takeIf { it >= 0 }, label, info.iconTint)
            }
        val default =
            SubscriptionManager.getDefaultSmsSubscriptionId().takeIf {
                it != SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        return SmsSims(sims, default)
    }

    /** Marks the thread's incoming SMS and MMS read. */
    fun markRead(
        context: Context,
        threadId: Long,
    ) {
        val values =
            ContentValues().apply {
                put("read", 1)
                put("seen", 1)
            }
        val args = arrayOf(threadId.toString())
        context.contentResolver.update(Telephony.Sms.CONTENT_URI, values, "thread_id = ? AND read = 0", args)
        context.contentResolver.update(Telephony.Mms.CONTENT_URI, values, "thread_id = ? AND read = 0", args)
    }

    /** Deletes the conversation (needs the SMS role). */
    fun deleteThread(
        context: Context,
        threadId: Long,
    ) {
        context.contentResolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null)
    }

    /** Deletes one message (needs the SMS role); false for a key that is not a provider row or nothing deleted. */
    fun deleteMessage(
        context: Context,
        key: SmsKey,
    ): Boolean {
        val (base, id) =
            when (key) {
                is SmsKey.Sms -> Telephony.Sms.CONTENT_URI to key.id
                is SmsKey.Mms -> Telephony.Mms.CONTENT_URI to key.id
                is SmsKey.Local -> return false
            }
        return context.contentResolver.delete(ContentUris.withAppendedId(base, id), null, null) > 0
    }

    /** SMS bodies matching the `LIKE` pattern [like] (`\` escapes), newest first. */
    fun search(
        context: Context,
        like: String,
        limit: Int,
    ): SmsMessagesPayload {
        val n = limit.coerceAtLeast(1)
        val sms = readSms(context, "body LIKE ? ESCAPE '\\'", arrayOf(like), n + 1)
        return SmsRules.page(sms, emptyList(), n)
    }

    /** The recipients of a thread, for a cold inline reply. */
    fun addressesFor(
        context: Context,
        threadId: Long,
    ): List<String> {
        val canonical = canonicalAddresses(context)
        context.contentResolver
            .query(THREADS_URI, arrayOf("recipient_ids"), "_id = ?", arrayOf(threadId.toString()), null)
            ?.use { c ->
                if (c.moveToFirst()) return SmsRules.splitIds(c.getString(0)).mapNotNull { canonical[it] }
            }
        return emptyList()
    }

    /**
     * Restores SMS rows from a backup, skipping rows already present (same address, date and body). Without the SMS
     * role the provider refuses: the outcome then has [RestoreOutcome.roleMissing] and what was done so far.
     */
    fun insertRestored(
        context: Context,
        rows: List<SmsRow>,
    ): RestoreOutcome {
        var inserted = 0
        var skipped = 0
        try {
            for (row in rows) {
                if (row.address.isEmpty() || exists(context, row.address, row.dateMs, row.body)) {
                    skipped++
                    continue
                }
                val values =
                    ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, row.address)
                        put(Telephony.Sms.BODY, row.body)
                        put(Telephony.Sms.DATE, row.dateMs)
                        put(Telephony.Sms.TYPE, row.box.coerceIn(1, 6))
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                        row.subId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
                    }
                if (context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) != null) inserted++ else skipped++
            }
        } catch (_: SecurityException) {
            return RestoreOutcome(inserted, skipped, roleMissing = true)
        }
        return RestoreOutcome(inserted, skipped)
    }

    private fun readSms(
        context: Context,
        selection: String,
        args: Array<String>,
        limit: Int,
    ): List<SmsRow> {
        val sms = ArrayList<SmsRow>()
        context.contentResolver
            .query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION, selection, args, "date DESC LIMIT $limit")
            ?.use { c ->
                while (c.moveToNext()) {
                    val box = c.getInt(5)
                    sms +=
                        SmsRow(
                            id = c.getLong(0),
                            threadId = c.getLong(1),
                            address = c.getString(2) ?: "",
                            body = c.getString(3) ?: "",
                            dateMs = c.getLong(4),
                            box = box,
                            read = c.getInt(6) != 0,
                            subId = c.getInt(7).takeIf { it >= 0 },
                            delivery = SmsRules.delivery(box, c.getInt(8)),
                        )
                }
            }
        return sms
    }

    private fun exists(
        context: Context,
        address: String,
        date: Long,
        body: String,
    ): Boolean =
        context.contentResolver
            .query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id"),
                "address = ? AND date = ? AND body = ?",
                arrayOf(address, date.toString(), body),
                null,
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
    private fun mmsAddresses(
        context: Context,
        id: Long,
    ): Pair<String?, List<String>> {
        var from: String? = null
        val to = ArrayList<String>()
        context.contentResolver
            .query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(0) ?: continue
                    if (address == "insert-address-token") continue
                    if (c.getInt(1) == 137) from = address else to.add(address)
                }
            }
        return from to to
    }

    /** Text parts inline, binary parts copied to the cache so only a path crosses the seam. */
    private fun mmsParts(
        context: Context,
        mmsId: Long,
    ): List<MmsPart> {
        val out = ArrayList<MmsPart>()
        context.contentResolver
            .query(MMS_PART_URI, arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(mmsId.toString()), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val partId = c.getLong(0)
                    val type = c.getString(1) ?: ""
                    when {
                        type == "application/smil" -> Unit
                        type.startsWith("text/") -> out += MmsPart(type, c.getString(2) ?: "", null)
                        else -> out += MmsPart(type, null, cachePart(context, partId, type))
                    }
                }
            }
        return out
    }

    private fun cachePart(
        context: Context,
        partId: Long,
        type: String,
    ): String? =
        try {
            val dir = File(context.cacheDir, "mms_parts").apply { mkdirs() }
            val file = File(dir, "$partId.${SmsRules.extension(type)}")
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
}

/** Pure rules of [SmsQuery] (no Android types beyond the models, so the JVM tests run them). */
object SmsRules {
    /** `recipient_ids` is a space-separated list of canonical-address ids. */
    fun splitIds(raw: String?): List<String> =
        raw
            .orEmpty()
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }

    /** `Telephony.Sms.STATUS`: 0 complete (delivered), 32 pending, 64 failed. */
    fun delivery(
        box: Int,
        status: Int,
    ): DeliveryState =
        when {
            box == 5 || status == 64 -> DeliveryState.Failed
            status == 0 && box == 2 -> DeliveryState.Delivered
            box == 4 || box == 6 || status == 32 -> DeliveryState.Pending
            box == 2 -> DeliveryState.Sent
            else -> DeliveryState.None
        }

    /** A page from up to `n + 1` rows of each kind (newest first): the newest [n] overall, and `has_older`. */
    fun page(
        sms: List<SmsRow>,
        mms: List<MmsRow>,
        n: Int,
    ): SmsMessagesPayload {
        val all = (sms.map { it.dateMs to it } + mms.map { it.dateMs to it }).sortedByDescending { it.first }
        val kept = all.take(n).map { it.second }
        return SmsMessagesPayload(
            sms = kept.filterIsInstance<SmsRow>(),
            mms = kept.filterIsInstance<MmsRow>(),
            hasOlder = all.size > n,
        )
    }

    fun extension(contentType: String): String =
        when (contentType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "audio/mpeg" -> "mp3"
            else -> "bin"
        }
}
