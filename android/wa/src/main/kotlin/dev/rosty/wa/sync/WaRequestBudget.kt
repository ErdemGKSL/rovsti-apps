package dev.rosty.wa.sync

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.wa.avatars.rateLimit
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.min
import kotlin.random.Random

/**
 * The request budget of the WhatsApp gateway (`plans/wa-vault.md` 14): the app must cost the gateway less than the
 * eager sync did, never more. Pure: the clock is passed in.
 *
 * - **Locked**: no request at all ([lock]); [unlocked] opens the budget again.
 * - **Burst**: at most [burst] requests in the first [burstWindowMs] after an unlock (status, chat list, identity, the
 *   event socket, the chat on screen and one spare).
 * - **Steady**: at most [perMinute] requests in any sliding minute (the burst counts too).
 * - **Cold syncs**: at most one per [coldGapMs] ([tryColdSync]): a reconnect storm does not refetch the chat list.
 */
class WaRequestBudget(
    private val now: () -> Long,
    private val burst: Int = BURST,
    private val burstWindowMs: Long = BURST_WINDOW_MS,
    private val perMinute: Int = PER_MINUTE,
    private val coldGapMs: Long = COLD_GAP_MS,
) {
    private val lock = Any()
    private var unlockedAt: Long? = null
    private val taken = ArrayDeque<Long>()
    private var lastCold: Long? = null

    /** True while no request may go out. */
    val locked: Boolean get() = synchronized(lock) { unlockedAt == null }

    /** The vault was unlocked: a fresh burst window, and the next cold sync may run at once. */
    fun unlocked() {
        synchronized(lock) {
            unlockedAt = now()
            taken.clear()
            lastCold = null
        }
    }

    /** The vault was locked: every later request is refused until [unlocked]. */
    fun lock() {
        synchronized(lock) {
            unlockedAt = null
            taken.clear()
        }
    }

    /** Takes one request if the budget has it now. */
    fun tryTake(): Boolean =
        synchronized(lock) {
            if (waitLocked(now()) != 0L) return false
            taken.addLast(now())
            true
        }

    /** How long until [tryTake] can succeed; [Long.MAX_VALUE] while locked. */
    fun waitMs(): Long = synchronized(lock) { waitLocked(now()) }

    private fun waitLocked(at: Long): Long {
        val start = unlockedAt ?: return Long.MAX_VALUE
        while (taken.isNotEmpty() && taken.first() <= at - MINUTE_MS) taken.removeFirst()
        if (at < start + burstWindowMs) {
            val inBurst = taken.count { it >= start }
            if (inBurst >= burst) return start + burstWindowMs - at
        }
        if (taken.size >= perMinute) return taken.first() + MINUTE_MS - at
        return 0
    }

    /** True (and remembered) when a cold sync (status, chat list, identity) may run now. */
    fun tryColdSync(): Boolean =
        synchronized(lock) {
            if (unlockedAt == null) return false
            val at = now()
            val last = lastCold
            if (last != null && at - last < coldGapMs) return false
            lastCold = at
            true
        }

    companion object {
        const val BURST = 6
        const val BURST_WINDOW_MS = 10_000L
        const val PER_MINUTE = 30
        const val COLD_GAP_MS = 30_000L
        const val MINUTE_MS = 60_000L
    }
}

/**
 * Exponential back-off with jitter after a failed gateway request (`plans/wa-vault.md` 14): 2 s, 4 s, ... up to 5 min,
 * each wait drawn from its upper half, and never shorter than what the gateway asked for (`Retry-After`, carried as
 * `retry_after_ms` in the error detail). A success resets it. Pure: the clock and the random source are passed in.
 */
class WaBackoff(
    private val now: () -> Long,
    private val random: () -> Double = { Random.nextDouble() },
    private val baseMs: Long = BASE_MS,
    private val maxMs: Long = MAX_MS,
) {
    private val lock = Any()
    private var failures = 0
    private var until = 0L

    /** Records a failure and answers how long to wait before the next request. */
    fun failed(retryAfterMs: Long? = null): Long =
        synchronized(lock) {
            failures++
            val step = min(maxMs, baseMs shl min(failures - 1, MAX_SHIFT))
            val jittered = step / 2 + (step / 2 * random().coerceIn(0.0, 1.0)).toLong()
            val wait = maxOf(jittered, retryAfterMs ?: 0L)
            until = now() + wait
            wait
        }

    fun succeeded() {
        synchronized(lock) {
            failures = 0
            until = 0
        }
    }

    /** How long until a request may go out again; 0 when none is held back. */
    fun waitMs(): Long = synchronized(lock) { (until - now()).coerceAtLeast(0) }

    companion object {
        const val BASE_MS = 2_000L
        const val MAX_MS = 300_000L
        private const val MAX_SHIFT = 20
    }
}

/**
 * Every gateway request of the WhatsApp sync goes through [call]: it waits for the [budget] and the [backoff], refuses
 * at once while locked, and feeds the outcome back to the back-off. History and media each run one at a time
 * ([history], [media]): concurrency 1.
 */
class WaRequestGate(
    val budget: WaRequestBudget,
    val backoff: WaBackoff,
    /** Waits; tests pass a virtual-time delay. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** The longest a call waits for the budget before it gives up (offline-like). */
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {
    /** One history page at a time. */
    val history = Mutex()

    /** One media download at a time. */
    val media = Mutex()

    /** Requests that went out since the gate was made (tests, logs). */
    @Volatile var sent: Int = 0
        private set

    suspend fun <T> call(block: suspend () -> Outcome<T>): Outcome<T> {
        var waited = 0L
        while (true) {
            if (budget.locked) return Outcome.Err(LOCKED)
            // The back-off first, so no token is spent on a request that would only wait.
            val held = backoff.waitMs()
            val wait =
                if (held > 0) {
                    held
                } else if (budget.tryTake()) {
                    0L
                } else {
                    budget.waitMs()
                }
            if (wait == 0L) break
            if (waited + wait > maxWaitMs) return Outcome.Err(RustError.Network("the gateway budget is spent"))
            sleep(wait)
            waited += wait
        }
        sent++
        val out = block()
        when (out) {
            is Outcome.Ok -> {
                backoff.succeeded()
            }

            is Outcome.Err -> {
                val error = out.error
                val limit = error.rateLimit()
                if (limit != null || error is RustError.Network) backoff.failed(limit?.retryAfterMs)
            }
        }
        return out
    }

    /** A history page: concurrency 1. */
    suspend fun <T> page(block: suspend () -> Outcome<T>): Outcome<T> = history.withLock { call(block) }

    /** A media download: concurrency 1. */
    suspend fun <T> download(block: suspend () -> Outcome<T>): Outcome<T> = media.withLock { call(block) }

    companion object {
        const val MAX_WAIT_MS = 120_000L

        /** What a request answers while the vault is locked: nothing went out. */
        val LOCKED: RustError =
            RustError.Unsupported("WhatsApp is locked", buildJsonObject { put("code", JsonPrimitive("locked")) })

        /** True for [LOCKED]. */
        fun isLocked(error: RustError): Boolean = error === LOCKED
    }
}
