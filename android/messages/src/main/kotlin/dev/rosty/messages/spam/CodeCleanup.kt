package dev.rosty.messages.spam

import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.messages.SmsKey
import dev.rosty.core.model.messages.SmsRow
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.platform.sms.SmsResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** One passcode SMS waiting to be deleted. */
@Serializable
data class PendingCode(
    @SerialName("sms_id") val smsId: Long,
    @SerialName("due_ms") val dueMs: Long,
)

/** `code-cleanup.json`: the M47 switch and the passcode messages it will delete. */
@Serializable
data class CodeCleanupFile(
    val enabled: Boolean = false,
    val pending: List<PendingCode> = emptyList(),
)

/** What a sweep should do now: delete [due], keep [later], wake again at [nextDueMs]. */
data class CleanupPlan(
    val due: List<PendingCode>,
    val later: List<PendingCode>,
) {
    val nextDueMs: Long? get() = later.minOfOrNull { it.dueMs }

    companion object {
        /** Splits [pending] at [nowMs]; the same message is kept once, at its earliest time. */
        fun of(
            pending: List<PendingCode>,
            nowMs: Long,
        ): CleanupPlan {
            val unique = pending.groupBy { it.smsId }.map { (_, same) -> same.minBy { it.dueMs } }
            val (due, later) = unique.partition { it.dueMs <= nowMs }
            return CleanupPlan(due, later)
        }

        /** The newest incoming SMS among [candidates] whose text holds [code]. */
        fun messageOf(
            candidates: List<SmsRow>,
            code: String,
        ): Long? =
            candidates
                .filter { it.box == INBOX_BOX && code.isNotBlank() && it.body.contains(code) }
                .maxByOrNull { it.dateMs }
                ?.id

        /** `Telephony.Sms.MESSAGE_TYPE_INBOX`. */
        const val INBOX_BOX = 1
    }
}

/**
 * M47, "Delete codes after copying" (util-messages-r6: an OTP SMS is removed 24 h after Copy code, local, confirmed in
 * Settings). Off by default. When on, [copied] finds the passcode message the code came from and books its deletion
 * [delayMs] later; [sweep] deletes what is due (only while the app holds the SMS role, which the provider requires) and
 * asks [wake] for the next one. The bookings live in their own small file because `messages-prefs.json` is written by
 * Rust, which has no field for them (a shared change is listed in the story notes).
 */
class CodeCleanup(
    private val file: File,
    private val sms: SmsRepository,
    private val roleHeld: () -> Boolean,
    private val wake: (delayMs: Long) -> Unit,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val delayMs: Long = DELAY_MS,
) {
    private val lock = Mutex()
    private var state: CodeCleanupFile? = null
    private val enabledState = MutableStateFlow(false)

    /** Whether M47 is on; false until [load] read the file. */
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    suspend fun load(): CodeCleanupFile = lock.withLock { current() }

    /** Turns M47 on or off; off forgets every booking, so nothing is deleted any more. */
    suspend fun setEnabled(on: Boolean) {
        lock.withLock {
            val next = if (on) current().copy(enabled = true) else CodeCleanupFile()
            write(next)
        }
    }

    /**
     * The code [code] of a message in SMS thread [threadId] was copied. Books that message's deletion when M47 is on;
     * true when one was booked.
     */
    suspend fun copied(
        threadId: Long,
        code: String,
    ): Boolean {
        if (!load().enabled) return false
        val page = sms.messages(threadId, null, LOOKBACK)
        val id = CleanupPlan.messageOf(page.sms, code) ?: return false
        val earliest =
            lock.withLock {
                val file = current()
                if (!file.enabled) return false
                val pending = file.pending + PendingCode(id, now() + delayMs)
                write(file.copy(pending = pending))
                pending.minOf { it.dueMs }
            }
        // The job runs at the earliest booking, which the sweep then follows with the next.
        wake((earliest - now()).coerceAtLeast(0))
        return true
    }

    /** Deletes the due bookings; returns how many messages went away. */
    suspend fun sweep(): Int {
        var deleted = 0
        lock.withLock {
            val file = current()
            val plan = CleanupPlan.of(file.pending, now())
            if (plan.due.isEmpty()) {
                plan.nextDueMs?.let { wake((it - now()).coerceAtLeast(0)) }
                return deleted
            }
            if (!roleHeld()) {
                // The provider refuses deletes without the role: keep the bookings for when it is back.
                RostyLog.w(TAG, "passcode cleanup waits for the SMS role")
                return deleted
            }
            val kept = plan.later.toMutableList()
            for (item in plan.due) {
                when (val result = sms.deleteMessage(SmsKey.Sms(item.smsId))) {
                    is SmsResult.Ok -> if (result.value) deleted++
                    is SmsResult.Failed -> if (result.reason == SmsResult.Reason.Permission) kept += item
                }
            }
            write(file.copy(pending = kept))
            // A delete the provider refused stays booked and is tried again at the next wake.
            plan.nextDueMs?.let { wake((it - now()).coerceAtLeast(0)) }
        }
        return deleted
    }

    /** The caller holds [lock]. */
    private suspend fun current(): CodeCleanupFile {
        state?.let { return it }
        val read =
            withContext(io) {
                try {
                    if (file.exists()) json.decodeFromString(CodeCleanupFile.serializer(), file.readText()) else null
                } catch (e: IOException) {
                    RostyLog.w(TAG, "the passcode cleanup file could not be read", e)
                    null
                } catch (e: SerializationException) {
                    RostyLog.w(TAG, "the passcode cleanup file is corrupt", e)
                    null
                } catch (e: IllegalArgumentException) {
                    RostyLog.w(TAG, "the passcode cleanup file is corrupt", e)
                    null
                }
            } ?: CodeCleanupFile()
        state = read
        enabledState.value = read.enabled
        return read
    }

    /** The caller holds [lock]. */
    private suspend fun write(next: CodeCleanupFile) {
        state = next
        enabledState.value = next.enabled
        withContext(io) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(CodeCleanupFile.serializer(), next))
                if (!tmp.renameTo(file)) file.writeText(tmp.readText())
            } catch (e: IOException) {
                RostyLog.w(TAG, "the passcode cleanup file could not be written", e)
            }
        }
    }

    companion object {
        const val FILE = "code-cleanup.json"

        /** 24 hours, the M47 delay. */
        const val DELAY_MS = 24 * 60 * 60 * 1000L

        /** How many recent messages of the thread are searched for the copied code. */
        private const val LOOKBACK = 20
        private const val TAG = "messages"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
