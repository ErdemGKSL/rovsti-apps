package dev.rovsti.platform.calllog

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.platform.telephony.SimAccounts
import dev.rovsti.platform.wire.ProviderJson

/**
 * Call-log pull (AD-9): newest first. A read the app may not make throws [SecurityException]; the repository maps it.
 * Rows are typed [CallLogEntry]s, the same shape `rovsti_core` reads (fixture `call_log_ok.json`).
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/CallLogQuery.kt
 */
object CallLogQuery {
    const val DEFAULT_LIMIT = 500
    const val BLOCKED_LIMIT = 200

    /** `CallLog.Calls.TYPE` of a call the system blocked. */
    const val BLOCKED = 6

    /** Ids per `IN (...)` read, under SQLite's variable limit. */
    internal const val CHUNK = 400

    private val PROJECTION =
        arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_NUMBER_LABEL,
            CallLog.Calls.CACHED_LOOKUP_URI,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.BLOCK_REASON,
        )

    /** Recents rows: blocked calls (type 6) never reach Recents or the badge (story 17.2). */
    fun entries(
        context: Context,
        limit: Int,
    ): List<CallLogEntry> = read(context, limit.coerceIn(1, DEFAULT_LIMIT), "${CallLog.Calls.TYPE} != $BLOCKED", null)

    /** The type-6 rows with their `block_reason` (story 17.2); at most [BLOCKED_LIMIT]. */
    fun blocked(
        context: Context,
        limit: Int,
    ): List<CallLogEntry> = read(context, limit.coerceIn(1, BLOCKED_LIMIT), "${CallLog.Calls.TYPE} = $BLOCKED", null)

    /** The rows whose `_id` is in [ids], any type, newest first; what a delete keeps so Undo can restore it. */
    fun byIds(
        context: Context,
        ids: List<Long>,
    ): List<CallLogEntry> =
        ids.chunked(CHUNK).flatMap { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            read(context, chunk.size, "${CallLog.Calls._ID} IN ($marks)", chunk.map { it.toString() }.toTypedArray())
        }

    /** Unseen missed calls: type missed with the `NEW` flag set. */
    fun missedCount(context: Context): Int {
        val args =
            Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}",
                )
            }
        return context.contentResolver
            .query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), args, null)
            ?.use { it.count } ?: 0
    }

    /** `CallLog.Calls.TYPE` to the typed kind (`ProviderJson.kindName` is its wire word). */
    fun kindOf(type: Int): CallLogKind =
        when (type) {
            1, 7 -> CallLogKind.Incoming
            2 -> CallLogKind.Outgoing
            3 -> CallLogKind.Missed
            4 -> CallLogKind.Voicemail
            5 -> CallLogKind.Rejected
            BLOCKED -> CallLogKind.Blocked
            else -> CallLogKind.Unknown
        }

    private fun read(
        context: Context,
        limit: Int,
        selection: String,
        selectionArgs: Array<String>?,
    ): List<CallLogEntry> {
        val args =
            Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
            }
        val out = ArrayList<CallLogEntry>(limit)
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, PROJECTION, args, null)?.use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(1) ?: ""
                val type = c.getInt(2)
                out +=
                    CallLogEntry(
                        id = c.getLong(0),
                        numberRaw = raw,
                        normalized = ProviderJson.normalize(raw),
                        kind = kindOf(type),
                        dateMs = c.getLong(3),
                        durationS = c.getLong(4),
                        cachedName = c.getString(5)?.takeIf { it.isNotEmpty() },
                        numberLabel = c.getString(6)?.takeIf { it.isNotEmpty() },
                        contactId = c.getString(7)?.let { Uri.parse(it).lastPathSegment?.toLongOrNull() },
                        accountKey = SimAccounts.keyOf(c.getString(8), c.getString(9)),
                        blockReason = if (type == BLOCKED) Blocklist.reasonOf(c.getInt(10)) else null,
                    )
            }
        }
        return out
    }
}
