package dev.rosty.people

import dev.rosty.core.Outcome
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.ops.WaGateway
import dev.rosty.platform.identity.CallerIdentity
import dev.rosty.platform.identity.IdentitySource
import dev.rosty.wa.avatars.rateLimit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * WhatsApp names asked for on demand: a call from a number nobody owns (no contact, not in the people cache) shows the
 * number at once, [ask] queries WhatsApp in the background, and when the number is on WhatsApp its name (verified
 * business name, else the name the person gave themselves) lands here. [revision] then moves, so the resolver
 * ([dev.rosty.platform.identity.CallerIds]) answers again and the call screen and the call notification swap the number
 * for the name in place.
 *
 * Order stays: the device contact first, then the people cache, then this; a source only adds what the earlier ones lack.
 * One query per number at a time; an answer (also "not on WhatsApp") is kept for [ttlMs], so a redial asks nothing.
 * With a [store] the answers outlive the process: they are read once, before the first lookup or ask, and written
 * after each new answer, with the same lifetime.
 * Two gateway calls per new number (lookup, then profile), well inside the gateway's allowance. Never throws.
 */
class LiveWaNames(
    private val gateway: suspend () -> WaGateway?,
    private val e164: (String) -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    /** Where the answers are kept between runs; null keeps them in memory only. */
    private val store: LiveNameStore? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Hears every profile answer of the gateway (it may name a number's LID): an app with a chat store learns it. */
    private val onProfiles: suspend (List<WaProfile>) -> Unit = {},
) : IdentitySource {
    private val known = ConcurrentHashMap<String, LiveName>()
    private val restoring = Mutex()

    @Volatile private var restored = store == null
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val changes = MutableStateFlow(0L)

    /** No question goes out before this time (wall clock): the gateway answered 429 and named the wait. */
    @Volatile private var pausedUntilMs = 0L

    override val revision: StateFlow<Long> get() = changes

    /** Memory only: always answerable; an empty answer means "not asked yet" or "not on WhatsApp". */
    override val ready: Boolean = true

    override suspend fun lookup(numbers: List<String>): Map<String, CallerIdentity> {
        restore()
        val out = HashMap<String, CallerIdentity>()
        for (number in numbers) {
            val key = keyOf(number) ?: continue
            val name = known[key]?.name ?: continue
            out[number] = CallerIdentity(waPushName = name)
        }
        return out
    }

    /** Starts a background WhatsApp query for [number] unless one runs or a fresh answer is known. */
    fun ask(number: String) {
        val key = keyOf(number) ?: return
        if (fresh(key)) return
        // WhatsApp or the gateway said wait: nothing is asked (the number is asked again when next needed).
        if (paused()) return
        if (!asking.add(key)) return
        scope.launch {
            try {
                restore()
                // A stored answer may cover it now that the file is read.
                if (!fresh(key) && !paused()) query(key)
            } finally {
                asking.remove(key)
            }
        }
    }

    /**
     * Drops every answer, in memory and in the [store]: the WhatsApp account they were asked through is gone
     * (another one was linked, or the link was forgotten). A number is asked again when it is next needed.
     */
    suspend fun forget() {
        restoring.withLock {
            restored = true
            val named = known.values.any { it.name != null }
            known.clear()
            store?.let { keep -> withContext(io) { keep.save(emptyMap()) } }
            if (named) changes.value += 1
        }
    }

    private fun paused(): Boolean = clock() < pausedUntilMs

    /** A 429 pauses every question for the wait it names ([RATE_LIMIT_PAUSE_MS] when it names none). */
    private fun pauseOn(error: dev.rosty.core.RustError) {
        val limit = error.rateLimit() ?: return
        val until = clock() + (limit.retryAfterMs?.takeIf { it > 0 } ?: RATE_LIMIT_PAUSE_MS)
        if (until > pausedUntilMs) pausedUntilMs = until
        RostyLog.i(TAG, "live name: rate limited (${limit.source}), paused for ${until - clock()} ms")
    }

    private fun fresh(key: String): Boolean = known[key]?.let { clock() - it.atMs < ttlMs } == true

    /** Reads the stored answers once; the ones past their lifetime are left out. A name found moves [revision]. */
    private suspend fun restore() {
        if (restored) return
        restoring.withLock {
            if (restored) return
            val saved = withContext(io) { store?.load().orEmpty() }
            val now = clock()
            var named = false
            for ((key, answer) in saved) {
                if (now - answer.atMs >= ttlMs) continue
                // An answer of this run is newer than the file's.
                if (known.putIfAbsent(key, answer) == null && answer.name != null) named = true
            }
            restored = true
            if (named) changes.value += 1
        }
    }

    private suspend fun query(key: String) {
        val gw =
            try {
                gateway()
            } catch (e: RuntimeException) {
                RostyLog.w(TAG, "live name: no gateway", e)
                null
            } ?: return
        val jid = WaIds.pnJid(key) ?: return
        val user =
            when (val answer = gw.lookupUsers(listOf(jid))) {
                is Outcome.Ok -> {
                    answer.value.firstOrNull { it.jid == jid }
                }

                is Outcome.Err -> {
                    pauseOn(answer.error)
                    RostyLog.i(TAG, "live name: lookup failed (${answer.error})")
                    return
                }
            }
        if (user == null || !user.onWhatsapp) {
            remember(key, null)
            RostyLog.d(TAG, "live name: not on WhatsApp")
            return
        }
        val profileName =
            when (val answer = gw.profiles(listOf(jid))) {
                is Outcome.Ok -> {
                    onProfiles(answer.value)
                    answer.value.firstOrNull { it.jid == jid }?.let { p ->
                        nameOf(p.verifiedName, p.pushName)
                    }
                }

                is Outcome.Err -> {
                    pauseOn(answer.error)
                    RostyLog.i(TAG, "live name: profile failed (${answer.error})")
                    null
                }
            }
        val name = profileName ?: nameOf(user.verifiedName, null)
        remember(key, name)
        RostyLog.i(TAG, if (name != null) "live name: found on WhatsApp" else "live name: on WhatsApp, no name")
    }

    private suspend fun remember(
        key: String,
        name: String?,
    ) {
        known[key] = LiveName(name, clock())
        if (name != null) changes.value += 1
        val keep = store ?: return
        val now = clock()
        val live = known.filterValues { now - it.atMs < ttlMs }
        withContext(io) { keep.save(live) }
    }

    /** The number's digits in international form, the key and the JID's user part; null for a withheld or short number. */
    private fun keyOf(number: String): String? =
        e164(number.trim())
            ?.filter { it in '0'..'9' }
            ?.takeIf { it.length >= MIN_DIGITS }

    companion object {
        private const val TAG = "people"
        private const val MIN_DIGITS = 7

        /** How long an answer (also "not on WhatsApp") is trusted before a new call asks again. */
        const val TTL_MS: Long = 6 * 60 * 60 * 1000L

        /** The pause after a 429 that named no wait (an older gateway). */
        const val RATE_LIMIT_PAUSE_MS: Long = 60_000L

        /** Pure: the name to show, verified business name first, else the push name; null when both are blank. */
        fun nameOf(
            verified: String?,
            push: String?,
        ): String? = listOf(verified, push).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    }
}
