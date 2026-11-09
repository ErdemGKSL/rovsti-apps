package dev.rovsti.wa.avatars

/**
 * What [WaAvatars] knows about every avatar it was asked for, and nothing else: no bitmaps (Coil holds those), no
 * I/O, no clock of its own. Every method takes `nowMs`, so tests run it in virtual time. Not thread safe: the owner
 * serialises access.
 *
 * It decides when a fetch starts (never a second one for a jid in flight, at most [maxParallel] at once, none while
 * the 429 back-off holds) and what an answer means for the next ask. Same rules as the pure `AvatarBook` in
 * `rovsti_app_core::wa_link::avatars` that the GPUI client used.
 */
internal class AvatarBook(
    private val capacity: Int = CAPACITY,
    private val maxParallel: Int = MAX_PARALLEL,
) {
    /** What the worker has to do for one avatar. */
    data class Job(
        val jid: String,
        /** The picture id the caller or a `profile` event says is current; null when nobody knows. */
        val wanted: String?,
        /** The id already shown, to revalidate with the gateway; null means look on disk first. */
        val held: String?,
    )

    /** What the worker found. */
    sealed interface Found {
        /** A picture file with [id]. [verified]: the gateway confirmed it is current. */
        data class Image(
            val id: String,
            val verified: Boolean,
        ) : Found

        /** The gateway still has the picture the job held. */
        data object Unchanged : Found

        /** No picture, or it is hidden. */
        data object None : Found

        /** The gateway answered 429. */
        data object RateLimited : Found

        /** Network or decode trouble. */
        data object Failed : Found
    }

    private sealed interface Slot {
        /** [recheckAtMs] null: verified this session. */
        data class Image(
            val id: String,
            var recheckAtMs: Long?,
        ) : Slot

        data class Missing(
            val forId: String?,
            val untilMs: Long,
        ) : Slot

        data class Failed(
            val retryAtMs: Long,
        ) : Slot
    }

    private val slots = LinkedHashMap<String, Slot>(16, 0.75f, true)
    private val pending = HashSet<String>()
    private val hints = HashMap<String, String?>()
    private var delayMs = 0L
    private var untilMs = 0L

    /** The picture id shown for [jid]. */
    fun heldId(jid: String): String? = (slots[jid] as? Slot.Image)?.id

    fun isPending(jid: String): Boolean = jid in pending

    /** Requests wait while this is true. */
    fun backoffActive(nowMs: Long): Boolean = nowMs < untilMs

    /** When the wait ends (meaningful while [backoffActive]). */
    val backoffUntilMs: Long get() = untilMs

    /**
     * [jid] is on screen. Returns the job to run, or null when nothing is due: the picture is current, a fetch is in
     * flight, a recent miss or failure still holds, the parallel limit is reached or the back-off is active.
     */
    fun request(
        jid: String,
        wantedByCaller: String?,
        nowMs: Long,
    ): Job? {
        val wanted = if (hints.containsKey(jid)) hints[jid] else wantedByCaller
        if (jid in pending) return null
        var held: String? = null
        when (val slot = slots[jid]) {
            is Slot.Image -> {
                if (wanted != null) {
                    if (wanted == slot.id) return null
                } else {
                    val at = slot.recheckAtMs
                    if (at == null || at > nowMs) return null
                    held = slot.id
                }
            }

            is Slot.Missing -> {
                if (slot.forId == wanted && nowMs < slot.untilMs) return null
            }

            is Slot.Failed -> {
                if (nowMs < slot.retryAtMs) return null
            }

            null -> {
                Unit
            }
        }
        if (backoffActive(nowMs) || pending.size >= maxParallel) return null
        pending += jid
        return Job(jid, wanted, held)
    }

    /** A job finished. Returns the jids the book dropped to stay within its capacity. */
    fun arrived(
        job: Job,
        found: Found,
        nowMs: Long,
    ) {
        pending -= job.jid
        when (found) {
            is Found.Image -> {
                if (found.verified) resetBackoff()
                store(job.jid, Slot.Image(found.id, if (found.verified) null else nowMs + RETRY_MS))
            }

            Found.Unchanged -> {
                resetBackoff()
                (slots[job.jid] as? Slot.Image)?.recheckAtMs = null
            }

            Found.None -> {
                resetBackoff()
                store(job.jid, Slot.Missing(job.wanted, nowMs + NONE_FOR_MS))
            }

            Found.RateLimited -> {
                rateLimited(nowMs)
            }

            Found.Failed -> {
                val held = slots[job.jid]
                if (held is Slot.Image) {
                    held.recheckAtMs = nowMs + RETRY_MS
                } else {
                    store(job.jid, Slot.Failed(nowMs + RETRY_MS))
                }
            }
        }
    }

    /** Another request of the same bucket (profiles) answered 429: wait twice as long as last time. */
    fun rateLimited(nowMs: Long) {
        delayMs = (delayMs * 2).coerceIn(BACKOFF_MIN_MS, BACKOFF_MAX_MS)
        untilMs = nowMs + delayMs
    }

    /** A `profile` or `chat_meta` event names the current picture of [jid] (null: it has none). */
    fun onPictureId(
        jid: String,
        pictureId: String?,
    ) {
        hints[jid] = pictureId
        when (val slot = slots[jid]) {
            is Slot.Image -> if (pictureId != slot.id) slot.recheckAtMs = Long.MIN_VALUE
            null -> Unit
            else -> slots.remove(jid)
        }
    }

    /** The gateway or the account changed: forget misses, failures, hints and the back-off. Pictures are rechecked. */
    fun forgetMisses() {
        slots.entries.removeAll { it.value !is Slot.Image }
        slots.values.forEach { (it as Slot.Image).recheckAtMs = Long.MIN_VALUE }
        hints.clear()
        resetBackoff()
    }

    /** Drops everything (the account was unlinked). */
    fun clear() {
        slots.clear()
        pending.clear()
        hints.clear()
        resetBackoff()
    }

    private fun resetBackoff() {
        delayMs = 0
        untilMs = 0
    }

    private fun store(
        jid: String,
        slot: Slot,
    ) {
        slots.remove(jid)
        while (slots.size >= capacity) {
            val oldest = slots.keys.iterator()
            oldest.next()
            oldest.remove()
        }
        slots[jid] = slot
    }

    companion object {
        /** Avatars remembered. */
        const val CAPACITY = 96

        /** Fetches at once. */
        const val MAX_PARALLEL = 3

        /** A failure, or an unverified picture, is tried again after this. */
        const val RETRY_MS = 60_000L

        /** "No picture" is believed this long. */
        const val NONE_FOR_MS = 6 * 3_600_000L
        const val BACKOFF_MIN_MS = 5_000L
        const val BACKOFF_MAX_MS = 300_000L
    }
}
