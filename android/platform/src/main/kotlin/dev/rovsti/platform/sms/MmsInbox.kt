package dev.rovsti.platform.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dev.rovsti.platform.Headless
import dev.rovsti.platform.PlatformEvents
import org.json.JSONObject

/** One part of a retrieved MMS as `Headless.call("mms_retrieved")` answers it. */
data class RetrievedPart(
    val contentType: String,
    val name: String?,
    val text: String?,
    val data: ByteArray?,
)

/** The decoded `m-retrieve-conf` (`ok == true` answer of `mms_retrieved`). */
data class Retrieved(
    val from: String?,
    val to: List<String>,
    val subject: String?,
    val dateSeconds: Long?,
    val transactionId: String?,
    val text: String,
    val parts: List<RetrievedPart>,
    val verdict: Verdict,
) {
    companion object {
        /** Pure: null when the answer is missing, malformed or `ok:false`. */
        fun parse(answer: String?): Retrieved? {
            if (answer.isNullOrBlank()) return null
            return try {
                val o = JSONObject(answer)
                if (!o.optBoolean("ok", false)) return null
                val to = o.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it, "") } }.orEmpty()
                val arr = o.optJSONArray("parts")
                val parts =
                    if (arr == null) {
                        emptyList()
                    } else {
                        (0 until arr.length()).map { i ->
                            val p = arr.getJSONObject(i)
                            RetrievedPart(
                                p.optString("content_type", ""),
                                if (p.isNull("name")) null else p.optString("name", "").takeIf { it.isNotEmpty() },
                                if (p.has("text") && !p.isNull("text")) p.optString("text") else null,
                                if (p.has("data_b64") && !p.isNull("data_b64")) {
                                    try {
                                        java.util.Base64
                                            .getMimeDecoder()
                                            .decode(p.optString("data_b64"))
                                    } catch (
                                        _: IllegalArgumentException,
                                    ) {
                                        null
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                Retrieved(
                    if (o.isNull("from")) null else o.optString("from", "").takeIf { it.isNotEmpty() },
                    to,
                    if (o.isNull("subject")) null else o.optString("subject", "").takeIf { it.isNotEmpty() },
                    if (o.isNull("date_s")) null else o.optLong("date_s"),
                    if (o.isNull(
                            "transaction_id",
                        )
                    ) {
                        null
                    } else {
                        o.optString("transaction_id", "").takeIf { it.isNotEmpty() }
                    },
                    o.optString("text", ""),
                    parts,
                    Verdict(
                        o.optBoolean("spam", false),
                        if (o.isNull("otp")) null else o.optString("otp", "").takeIf { it.isNotEmpty() },
                    ),
                )
            } catch (_: org.json.JSONException) {
                null
            }
        }
    }
}

/** Posts the notification for [MmsInbox]; `:platform` `notify.MessageNotifications` implements it (E3.6). */
fun interface MessageNotifier {
    fun show(
        context: Context,
        specJson: String,
    )
}

/**
 * Ported from apps/messages/platforms/android/app/src/main/kotlin/dev/rovsti/messages/MmsInbox.kt
 *
 * Persists a downloaded MMS to `Telephony.Mms` and notifies (AD-MSG4). The PDU is decoded in Rust
 * (`mms_retrieved`); this object only writes rows. Without a library there is no decode, so the message cannot be
 * shown and is dropped (the carrier keeps it until it expires).
 */
object MmsInbox {
    /** The notification poster; the app installs it from `Application.onCreate`. With none, no notification shows. */
    @Volatile var notifier: MessageNotifier? = null

    private const val ADDR_FROM = 137
    private const val ADDR_TO = 151
    private const val CHARSET_UTF8 = 106
    private const val MMS_VERSION_1_2 = 0x12
    private const val MESSAGE_TYPE_RETRIEVE_CONF = 132

    /** Matches `MmsCodec.DownloadedHandler` (install with `MmsCodec.downloadedHandler = MmsCodec.DownloadedHandler(MmsInbox::onDownloaded)`). Runs on a receiver worker thread. */
    fun onDownloaded(
        context: Context,
        localId: Long,
        pduBase64: String,
    ) {
        val request = JSONObject().put("pdu_b64", pduBase64).put("dir", Headless.dataDir(context)).toString()
        val first = Retrieved.parse(Headless.call("mms_retrieved", request)) ?: return
        val sender = first.from.orEmpty()
        val name = IncomingNotice.contactName(context, sender)
        val blocked = IncomingNotice.isBlocked(context, sender)
        // The sender is only known after decoding, so contacts and the blocklist are applied here:
        // a contact is never spam, a blocked number always is (same order as `spam::classify`).
        val spam = blocked || (first.verdict.spam && name == null)
        val stored = persist(context, first) ?: return
        if (!spam && stored.threadId != 0L) {
            val line = first.text.ifBlank { IncomingNotice.attachmentLine(first.parts.map { it.contentType }) }
            notifier?.show(
                context,
                IncomingNotice.spec(stored.threadId, name ?: sender.ifEmpty { "MMS" }, listOf(line), first.verdict.otp),
            )
        }
        PlatformEvents.emit(
            "sms_received",
            JSONObject()
                .put("thread_id", stored.threadId)
                .put("address", sender)
                .put("body", first.text)
                .put("date_ms", stored.dateMs)
                .toString(),
        )
    }

    data class Stored(
        val id: Long,
        val threadId: Long,
        val dateMs: Long,
    )

    private fun persist(
        context: Context,
        mms: Retrieved,
    ): Stored? =
        try {
            val now = SmsClock.nowMillis()
            val dateSeconds = mms.dateSeconds ?: (now / 1000)
            val addresses = (listOfNotNull(mms.from) + mms.to).distinct().filter { it.isNotBlank() }
            val threadId =
                if (addresses.isEmpty()) {
                    0L
                } else {
                    Telephony.Threads.getOrCreateThreadId(
                        context,
                        addresses.toSet(),
                    )
                }
            val values =
                ContentValues().apply {
                    put(Telephony.Mms.THREAD_ID, threadId)
                    put(Telephony.Mms.DATE, dateSeconds)
                    put(Telephony.Mms.DATE_SENT, dateSeconds)
                    put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                    put(Telephony.Mms.READ, 0)
                    put(Telephony.Mms.SEEN, 0)
                    put(Telephony.Mms.MESSAGE_TYPE, MESSAGE_TYPE_RETRIEVE_CONF)
                    put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
                    put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                    mms.subject?.let {
                        put(Telephony.Mms.SUBJECT, it)
                        put(Telephony.Mms.SUBJECT_CHARSET, CHARSET_UTF8)
                    }
                    mms.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
                }
            val base = Telephony.Mms.Inbox.CONTENT_URI
            val row = context.contentResolver.insert(base, values)
            val id = row?.lastPathSegment?.toLongOrNull()
            if (id == null) {
                null
            } else {
                addAddress(context, id, mms.from, ADDR_FROM)
                mms.to.forEach { addAddress(context, id, it, ADDR_TO) }
                mms.parts.forEachIndexed { index, part -> addPart(context, id, index, part) }
                Stored(id, threadId, dateSeconds * 1000)
            }
        } catch (_: RuntimeException) {
            // Not the default SMS app any more, or the provider refused.
            null
        }

    private fun mmsUri(
        id: Long,
        leaf: String,
    ): Uri =
        Telephony.Mms.CONTENT_URI
            .buildUpon()
            .appendPath(id.toString())
            .appendPath(leaf)
            .build()

    private fun addAddress(
        context: Context,
        id: Long,
        address: String?,
        type: Int,
    ) {
        if (address.isNullOrBlank()) return
        val values =
            ContentValues().apply {
                put(Telephony.Mms.Addr.MSG_ID, id)
                put(Telephony.Mms.Addr.ADDRESS, address)
                put(Telephony.Mms.Addr.TYPE, type)
                put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8)
            }
        context.contentResolver.insert(mmsUri(id, "addr"), values)
    }

    private fun addPart(
        context: Context,
        id: Long,
        index: Int,
        part: RetrievedPart,
    ) {
        val values =
            ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, id)
                put(Telephony.Mms.Part.SEQ, index)
                put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
                put(Telephony.Mms.Part.CONTENT_ID, "<part_$index>")
                put(Telephony.Mms.Part.CONTENT_LOCATION, part.name ?: "part_$index")
                part.name?.let { put(Telephony.Mms.Part.NAME, it) }
                if (part.text != null) {
                    put(Telephony.Mms.Part.CHARSET, CHARSET_UTF8)
                    put(Telephony.Mms.Part.TEXT, part.text)
                }
            }
        val uri = context.contentResolver.insert(mmsUri(id, "part"), values) ?: return
        if (part.data != null) {
            // Binary content goes through the part's own stream, the provider picks the file.
            context.contentResolver.openOutputStream(uri)?.use { it.write(part.data) }
        }
    }
}
