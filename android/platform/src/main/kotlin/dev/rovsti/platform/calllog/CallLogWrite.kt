package dev.rovsti.platform.calllog

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind

/**
 * Call-log writes (delete, undo-restore, clear). They throw [SecurityException] without `WRITE_CALL_LOG`; the
 * repository maps it to a [CallLogResult].
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/CallLogWrite.kt
 */
object CallLogWrite {
    /** Deletes the rows whose `_id` is in [ids]; the number of rows gone. */
    fun delete(
        context: Context,
        ids: List<Long>,
    ): Int {
        var count = 0
        for (chunk in ids.chunked(CallLogQuery.CHUNK)) {
            val marks = chunk.joinToString(",") { "?" }
            count +=
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} IN ($marks)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
        }
        return count
    }

    /** Re-inserts [rows] (as [CallLogQuery.byIds] read them) as read, not new; the number of rows written. */
    fun restore(
        context: Context,
        rows: List<CallLogEntry>,
    ): Int {
        if (rows.isEmpty()) return 0
        val values = rows.map(::valuesOf).toTypedArray()
        return context.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, values)
    }

    /** Deletes every row; the number of rows gone. */
    fun deleteAll(context: Context): Int = context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)

    /** The columns [CallLogQuery] reads, so a restored row decodes equal to the original except its `_id`. */
    internal fun valuesOf(e: CallLogEntry): ContentValues =
        ContentValues().apply {
            put(CallLog.Calls.NUMBER, e.numberRaw)
            put(CallLog.Calls.TYPE, typeOf(e.kind))
            put(CallLog.Calls.DATE, e.dateMs)
            put(CallLog.Calls.DURATION, e.durationS)
            e.cachedName?.let { put(CallLog.Calls.CACHED_NAME, it) }
            e.numberLabel?.let { put(CallLog.Calls.CACHED_NUMBER_LABEL, it) }
            e.contactId?.let {
                put(
                    CallLog.Calls.CACHED_LOOKUP_URI,
                    ContactsContract.Contacts.CONTENT_URI
                        .buildUpon()
                        .appendPath("$it")
                        .build()
                        .toString(),
                )
            }
            val key = e.accountKey.orEmpty()
            val cut = key.lastIndexOf('#')
            if (cut > 0) {
                put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, key.substring(0, cut))
                put(CallLog.Calls.PHONE_ACCOUNT_ID, key.substring(cut + 1))
            }
            e.blockReason?.let { put(CallLog.Calls.BLOCK_REASON, Blocklist.reasonCode(it)) }
            put(CallLog.Calls.IS_READ, 1)
            put(CallLog.Calls.NEW, 0)
        }

    /** The typed kind back to `CallLog.Calls.TYPE`, the inverse of [CallLogQuery.kindOf]; unknown is type 0, which the provider keeps. */
    internal fun typeOf(kind: CallLogKind): Int =
        when (kind) {
            CallLogKind.Outgoing -> 2
            CallLogKind.Missed -> 3
            CallLogKind.Voicemail -> 4
            CallLogKind.Rejected -> 5
            CallLogKind.Blocked -> CallLogQuery.BLOCKED
            CallLogKind.Incoming -> 1
            CallLogKind.Unknown -> 0
        }
}
