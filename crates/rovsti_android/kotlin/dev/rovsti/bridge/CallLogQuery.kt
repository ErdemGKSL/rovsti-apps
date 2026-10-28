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
    )

    fun json(context: Context, limit: Int): String = try {
        ProviderJson.callLog(rows(context, limit.coerceIn(1, DEFAULT_LIMIT)))
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: Exception) {
        ProviderJson.error("unknown")
    }

    private fun rows(context: Context, limit: Int): List<ProviderJson.LogRow> {
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
        }
        val out = ArrayList<ProviderJson.LogRow>(limit)
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, PROJECTION, args, null)?.use { c ->
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
                    )
                )
            }
        }
        return out
    }
}
