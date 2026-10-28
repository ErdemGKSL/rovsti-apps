package dev.rovsti.bridge

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CallLog

/** Call-log pull (AD-9): newest first, never throws. */
object CallLogQuery {
    const val DEFAULT_LIMIT = 500

    private val PROJECTION = arrayOf(
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
    )

    private const val BLOCKED = 6
    private const val BLOCKED_LIMIT = 200

    fun json(context: Context, limit: Int): String = try {
        // Blocked calls (type 6) never reach Recents or the badge (story 17.2).
        ProviderJson.callLog(rows(context, limit.coerceIn(1, DEFAULT_LIMIT), "${CallLog.Calls.TYPE} != $BLOCKED"))
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: Exception) {
        ProviderJson.error("unknown")
    }

    /** The type-6 rows with their `block_reason` (story 17.2); at most 200. */
    fun blockedJson(context: Context, limit: Int): String = try {
        ProviderJson.callLog(rows(context, limit.coerceIn(1, BLOCKED_LIMIT), "${CallLog.Calls.TYPE} = $BLOCKED", withReason = true))
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: Exception) {
        ProviderJson.error("unknown")
    }

    private fun rows(context: Context, limit: Int, selection: String, withReason: Boolean = false): List<ProviderJson.LogRow> {
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
        }
        val out = ArrayList<ProviderJson.LogRow>(limit)
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, if (withReason) PROJECTION + CallLog.Calls.BLOCK_REASON else PROJECTION, args, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    ProviderJson.LogRow(
                        id = c.getLong(0),
                        numberRaw = c.getString(1) ?: "",
                        type = c.getInt(2),
                        dateMs = c.getLong(3),
                        durationS = c.getLong(4),
                        cachedName = c.getString(5)?.takeIf { it.isNotEmpty() },
                        label = c.getString(6)?.takeIf { it.isNotEmpty() },
                        contactId = c.getString(7)?.let { android.net.Uri.parse(it).lastPathSegment?.toLongOrNull() },
                        accountKey = SimAccounts.keyOf(c.getString(8), c.getString(9)),
                        blockReason = if (withReason) Blocklist.reasonName(c.getInt(10)) else null,
                    )
                )
            }
        }
        return out
    }
}
