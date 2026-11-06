package dev.rovsti.bridge

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import org.json.JSONObject

/** Call-log writes (delete, undo-restore, clear): never throws, `ProviderJson` envelopes. */
object CallLogWrite {
    private const val CHUNK = 400

    /** Deletes the rows whose `_id` is in [ids] (comma-separated); `{count}`. */
    fun delete(context: Context, ids: String): String = guarded {
        val parsed = ids.split(',').mapNotNull { it.trim().toLongOrNull() }
        var count = 0
        for (chunk in parsed.chunked(CHUNK)) {
            val marks = chunk.joinToString(",") { "?" }
            count += context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} IN ($marks)",
                chunk.map { it.toString() }.toTypedArray(),
            )
        }
        ProviderJson.ok(JSONObject().put("count", count))
    }

    /** Re-inserts the `{entries}` of [json] (as `callLog` wrote them); `{count}`. */
    fun restore(context: Context, json: String): String = guarded {
        val entries = JSONObject(json).optJSONArray("entries") ?: return@guarded ProviderJson.error("refused")
        val rows = ArrayList<ContentValues>(entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val v = ContentValues()
            v.put(CallLog.Calls.NUMBER, e.optString("number_raw"))
            v.put(CallLog.Calls.TYPE, ProviderJson.kindCode(e.optString("kind")))
            v.put(CallLog.Calls.DATE, e.optLong("date_ms"))
            v.put(CallLog.Calls.DURATION, e.optLong("duration_s"))
            val key = e.optString("account_key")
            val cut = key.lastIndexOf('#')
            if (cut > 0) {
                v.put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, key.substring(0, cut))
                v.put(CallLog.Calls.PHONE_ACCOUNT_ID, key.substring(cut + 1))
            }
            v.put(CallLog.Calls.IS_READ, 1)
            v.put(CallLog.Calls.NEW, 0)
            rows.add(v)
        }
        val inserted = if (rows.isEmpty()) 0 else context.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, rows.toTypedArray())
        if (inserted <= 0) ProviderJson.error("refused") else ProviderJson.ok(JSONObject().put("count", inserted))
    }

    /** Deletes every row; `{count}`. */
    fun deleteAll(context: Context): String = guarded {
        val n = context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        ProviderJson.ok(JSONObject().put("count", n))
    }

    private inline fun guarded(block: () -> String): String = try {
        block()
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: Exception) {
        ProviderJson.error("unknown")
    }
}
