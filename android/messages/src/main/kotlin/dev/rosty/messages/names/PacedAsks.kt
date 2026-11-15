package dev.rosty.messages.names

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Paces the questions to WhatsApp. An inbox can hold hundreds of numbers nobody names and every one of them asks; sent
 * at once that would be a burst of lookups from one account. Here they wait in a line and go out one every [paceMs],
 * the number asked for last first (the thread just opened, the message that just arrived), and a number is not put in
 * the line again within [retryMs] (the lookup behind [send] keeps its own, longer memory of the answers).
 */
class PacedAsks(
    private val scope: CoroutineScope,
    private val send: (number: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val paceMs: Long = PACE_MS,
    private val retryMs: Long = RETRY_MS,
) {
    private val lock = Any()
    private val waiting = ArrayDeque<String>()
    private val askedAt = HashMap<String, Long>()
    private var draining = false

    /** Puts [number] at the head of the line unless it was asked a moment ago. */
    fun ask(number: String) {
        val start =
            synchronized(lock) {
                val last = askedAt[number]
                if (last != null && now() - last < retryMs) return
                waiting.remove(number)
                waiting.addFirst(number)
                if (draining) false else true.also { draining = true }
            }
        if (start) scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val next =
                synchronized(lock) {
                    waiting.removeFirstOrNull()?.also { askedAt[it] = now() } ?: run {
                        draining = false
                        null
                    }
                } ?: return
            send(next)
            delay(paceMs)
        }
    }

    companion object {
        /** One question every half second at most. */
        const val PACE_MS = 500L

        /** A number whose question may have failed (no connection) is asked again after this long. */
        const val RETRY_MS = 10 * 60_000L
    }
}
