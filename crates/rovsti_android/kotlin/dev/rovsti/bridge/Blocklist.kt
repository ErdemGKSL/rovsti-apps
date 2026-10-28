package dev.rovsti.bridge

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * The system blocklist (`BlockedNumberContract`, AD-27, story 17.1). Only the default dialer (and a
 * few system apps) may use it, so every call can fail with a `SecurityException`; nothing here ever
 * throws. Blocking is shared with the system and the SMS app, which is the point.
 */
object Blocklist {
    /** What the facade saw, as the Rust side needs it (`false` from a write is "Android refused"). */
    enum class Result { OK, UNAVAILABLE, PERMISSION, REFUSED }

    data class Row(val id: Long, val numberRaw: String, val e164: String)

    /** The provider seen through a seam, so a JVM test can fake it. */
    interface Provider {
        fun canBlock(): Boolean
        fun rows(): List<Row>
        fun insert(raw: String): Boolean
        fun unblock(raw: String): Int
        fun isBlocked(raw: String): Boolean
    }

    /** The real provider. A `SecurityException` bubbles to [guard], which maps it. */
    class System(private val context: Context) : Provider {
        override fun canBlock() = BlockedNumberContract.canCurrentUserBlockNumbers(context)

        override fun rows(): List<Row> {
            val out = ArrayList<Row>()
            context.contentResolver.query(
                BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER, BlockedNumbers.COLUMN_E164_NUMBER),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) out.add(Row(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            }
            return out
        }

        override fun insert(raw: String): Boolean {
            val values = ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, raw) }
            // A duplicate returns the existing row's Uri: it replaces, never fails.
            return context.contentResolver.insert(BlockedNumbers.CONTENT_URI, values) != null
        }

        override fun unblock(raw: String): Int = BlockedNumberContract.unblock(context, raw)

        override fun isBlocked(raw: String): Boolean = BlockedNumberContract.isBlocked(context, raw)
    }

    /** Runs [block], mapping a refusal: no right to block at all is `unavailable`, otherwise `permission`. */
    fun <T> guard(provider: Provider, fallback: T, block: () -> T): Pair<Result, T> = try {
        Result.OK to block()
    } catch (_: SecurityException) {
        val can = try { provider.canBlock() } catch (_: RuntimeException) { false }
        (if (can) Result.PERMISSION else Result.UNAVAILABLE) to fallback
    } catch (_: RuntimeException) {
        Result.UNAVAILABLE to fallback
    }

    fun canBlock(provider: Provider): Boolean = guard(provider, false) { provider.canBlock() }.second

    /** `{v, ok, data:{numbers:[{id, number_raw, normalized}]}}` (docs/phone-wire.md). */
    fun pullJson(provider: Provider): String {
        val (result, rows) = guard(provider, emptyList()) { provider.rows() }
        return when (result) {
            Result.OK -> {
                val numbers = JSONArray()
                for (r in rows) {
                    numbers.put(
                        JSONObject().put("id", r.id).put("number_raw", r.numberRaw)
                            .put("normalized", r.e164.ifEmpty { ProviderJson.normalize(r.numberRaw) }),
                    )
                }
                ProviderJson.ok(JSONObject().put("numbers", numbers))
            }
            Result.PERMISSION -> ProviderJson.error("permission")
            else -> ProviderJson.error("unavailable")
        }
    }

    /** Blocks [raw]; emergency numbers and empty strings are `REFUSED` before the provider is asked. */
    fun block(provider: Provider, raw: String, isEmergency: (String) -> Boolean): Result {
        val number = raw.trim()
        if (number.isEmpty() || isEmergency(number)) return Result.REFUSED
        val (result, ok) = guard(provider, false) { provider.insert(number) }
        return if (result == Result.OK && !ok) Result.UNAVAILABLE else result
    }

    fun unblock(provider: Provider, raw: String): Result {
        val number = raw.trim()
        if (number.isEmpty()) return Result.REFUSED
        return guard(provider, 0) { provider.unblock(number) }.first
    }

    /** False on any failure: a number we cannot check rings as usual. */
    fun isBlocked(provider: Provider, raw: String): Boolean =
        raw.isNotBlank() && guard(provider, false) { provider.isBlocked(raw) }.second

    /**
     * `CallLog.Calls.BLOCK_REASON` to the wire word (story 17.2): the owner's own list is `user`,
     * every screening decision of the system is `system`, anything else `unknown`.
     */
    fun reasonName(code: Int): String = when (code) {
        CallLog.Calls.BLOCK_REASON_BLOCKED_NUMBER -> "user"
        CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE,
        CallLog.Calls.BLOCK_REASON_DIRECT_TO_VOICEMAIL,
        CallLog.Calls.BLOCK_REASON_NOT_IN_CONTACTS,
        CallLog.Calls.BLOCK_REASON_PAY_PHONE,
        CallLog.Calls.BLOCK_REASON_RESTRICTED_NUMBER,
        CallLog.Calls.BLOCK_REASON_UNKNOWN_NUMBER -> "system"
        else -> "unknown"
    }
}

/**
 * The safety net of story 17.1 (`Caps.BLOCK_GUARD`): when a call starts ringing, ask the blocklist on
 * [lookup] (never the main thread) and, on a hit, hand [onBlocked] to [main]. [onRinging] returns at
 * once, so the notification is never delayed by a slow provider.
 */
class BlockGuard(
    private val lookup: Executor,
    private val main: (Runnable) -> Unit,
    private val isBlocked: (String) -> Boolean,
) {
    fun onRinging(number: String, enabled: Boolean, onBlocked: () -> Unit) {
        if (!enabled || number.isBlank()) return
        lookup.execute {
            val hit = try { isBlocked(number) } catch (_: RuntimeException) { false }
            if (hit) main(Runnable { onBlocked() })
        }
    }
}
