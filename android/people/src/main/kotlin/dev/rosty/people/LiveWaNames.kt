package dev.rosty.people

import dev.rosty.core.Outcome
import dev.rosty.core.log.RostyLog
import dev.rosty.core.ops.WaGateway
import dev.rosty.platform.identity.CallerIdentity
import dev.rosty.platform.identity.IdentitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * Two gateway calls per new number (lookup, then profile), well inside the gateway's allowance. Never throws.
 */
class LiveWaNames(
    private val gateway: suspend () -> WaGateway?,
    private val e164: (String) -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
) : IdentitySource {
    private class Known(
        val name: String?,
        val atMs: Long,
    )

    private val known = ConcurrentHashMap<String, Known>()
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val changes = MutableStateFlow(0L)

    override val revision: StateFlow<Long> get() = changes

    /** Memory only: always answerable; an empty answer means "not asked yet" or "not on WhatsApp". */
    override val ready: Boolean = true

    override suspend fun lookup(numbers: List<String>): Map<String, CallerIdentity> {
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
        val prior = known[key]
        if (prior != null && clock() - prior.atMs < ttlMs) return
        if (!asking.add(key)) return
        scope.launch {
            try {
                query(key)
            } finally {
                asking.remove(key)
            }
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
        val jid = key + JID_SUFFIX
        val user =
            when (val answer = gw.lookupUsers(listOf(jid))) {
                is Outcome.Ok -> {
                    answer.value.firstOrNull { it.jid == jid }
                }

                is Outcome.Err -> {
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
                    answer.value.firstOrNull { it.jid == jid }?.let { p ->
                        nameOf(p.verifiedName, p.pushName)
                    }
                }

                is Outcome.Err -> {
                    RostyLog.i(TAG, "live name: profile failed (${answer.error})")
                    null
                }
            }
        val name = profileName ?: nameOf(user.verifiedName, null)
        remember(key, name)
        RostyLog.i(TAG, if (name != null) "live name: found on WhatsApp" else "live name: on WhatsApp, no name")
    }

    private fun remember(
        key: String,
        name: String?,
    ) {
        known[key] = Known(name, clock())
        if (name != null) changes.value += 1
    }

    /** The number's digits in international form, the key and the JID's user part; null for a withheld or short number. */
    private fun keyOf(number: String): String? =
        e164(number.trim())
            ?.filter { it in '0'..'9' }
            ?.takeIf { it.length >= MIN_DIGITS }

    companion object {
        private const val TAG = "people"
        private const val JID_SUFFIX = "@s.whatsapp.net"
        private const val MIN_DIGITS = 7

        /** How long an answer (also "not on WhatsApp") is trusted before a new call asks again. */
        const val TTL_MS: Long = 6 * 60 * 60 * 1000L

        /** Pure: the name to show, verified business name first, else the push name; null when both are blank. */
        fun nameOf(
            verified: String?,
            push: String?,
        ): String? = listOf(verified, push).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    }
}
