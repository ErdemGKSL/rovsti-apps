package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncOutboxItem
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.wa.avatars.rateLimit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How a failed gateway call settles an outbox row (`wa_sync.outbox_settle`):
 *
 * - no answer (timeout, connection lost, an answer that could not be read): `network`, the gateway may have sent it;
 * - the gateway said it did not send (`not_connected`, `rate_limited` with its wait, `internal`), or it could not be
 *   asked at all (no open gateway, the key refused): `gateway`, tried again later;
 * - the gateway refused the request itself (any other 4xx: bad request, not found, too large): `rejected`, shown as
 *   failed with Retry.
 */
fun waFailure(error: RustError): WaSyncOutcome.Error {
    val message = error.message.orEmpty()
    val code = ((error.detail as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
    return when {
        error is RustError.Network || error is RustError.Parse -> {
            WaSyncOutcome.Error(WaSyncErrorKind.Network, message)
        }

        error is RustError.Invalid && code in NOT_SENT_CODES -> {
            WaSyncOutcome.Error(WaSyncErrorKind.Gateway, message, error.rateLimit()?.retryAfterMs)
        }

        error is RustError.Auth || error is RustError.NotFound -> {
            WaSyncOutcome.Error(WaSyncErrorKind.Gateway, message)
        }

        else -> {
            WaSyncOutcome.Error(WaSyncErrorKind.Rejected, message)
        }
    }
}

private val NOT_SENT_CODES = setOf("not_connected", "rate_limited", "internal")

/** What one [WaOutbox.drain] left behind. */
data class DrainResult(
    /** Rows that went out. */
    val sent: Int,
    /** When to drain again without a new trigger (the backoff of a row); null: nothing is scheduled. */
    val nextWakeMs: Long?,
    /** Sends kept back until the sync is done. */
    val held: Int,
)

/**
 * The outbox of one open store (E6.4s): everything the user does offline is queued in the database ([enqueue]) and
 * shown at once; [drain] sends what is due, one row per chat in the order written, and reports each answer back
 * (`settle`). Nothing here polls: [drain] runs when a row is queued while connected, when the connection returns, and
 * from the WorkManager job at the time the store names.
 */
class WaOutbox(
    private val store: WaSyncStore,
    private val remote: WaRemote,
    private val now: () -> Long,
    private val onChange: (Set<String>?) -> Unit,
) {
    private val draining = Mutex()

    /** Queues [action] under a fresh [clientId]; the thread shows it at once. Null when the store refused it. */
    suspend fun enqueue(
        clientId: String,
        chat: String,
        action: WaSyncAction,
    ): WaSyncOutboxItem? {
        val item = store.enqueue(clientId, chat, action, now()).getOrNull() ?: return null
        onChange(setOf(chat))
        return item
    }

    suspend fun retry(
        chat: String,
        clientId: String,
    ): Boolean = (store.retry(clientId).getOrNull() == true).also { onChange(setOf(chat)) }

    suspend fun cancel(
        chat: String,
        clientId: String,
    ): Boolean = (store.cancel(clientId).getOrNull() == true).also { onChange(setOf(chat)) }

    /**
     * Sends every due row until none is left or the gateway stops answering. [networkBack] (the connection just came
     * back) ends the backoffs.
     */
    suspend fun drain(networkBack: Boolean = false): DrainResult =
        draining.withLock {
            var sent = 0
            var back = networkBack
            val tried = HashSet<String>()
            while (true) {
                val due = store.due(now(), back).getOrNull() ?: return@withLock DrainResult(sent, null, 0)
                back = false
                if (due.items.isEmpty()) return@withLock DrainResult(sent, due.nextWakeMs, due.held)
                // A row handed out twice in one drain (its retry is due at once) would spin: give it back and wait.
                val again = due.items.filterNot { tried.add(it.clientId) }
                if (again.isNotEmpty()) {
                    val wake = due.items.mapNotNull { giveBack(it) }.minOrNull()
                    return@withLock DrainResult(sent, wake, due.held)
                }
                var retryAt: Long? = null
                for (item in due.items) {
                    val outcome =
                        when (val answer = remote.perform(item.chat, item.action)) {
                            is Outcome.Ok -> answer.value
                            is Outcome.Err -> waFailure(answer.error)
                        }
                    val settled = store.settle(item.clientId, outcome, now()).getOrNull()
                    if (outcome is WaSyncOutcome.Sent) sent++
                    if (outcome is WaSyncOutcome.Error && outcome.error != WaSyncErrorKind.Rejected) {
                        val at = settled?.nextAttemptMs ?: (now() + FALLBACK_RETRY_MS)
                        retryAt = minOf(retryAt ?: at, at)
                    }
                    onChange(setOf(item.chat))
                }
                // The gateway is not answering: stop here and come back at the time of the first retry.
                if (retryAt != null) return@withLock DrainResult(sent, retryAt, due.held)
            }
            @Suppress("UNREACHABLE_CODE")
            DrainResult(sent, null, 0)
        }

    /** A row `due` handed out that is not sent in this drain: it waits again. Answers when it is tried next. */
    private suspend fun giveBack(item: WaSyncOutboxItem): Long? =
        store
            .settle(item.clientId, WaSyncOutcome.Error(WaSyncErrorKind.Gateway, "not attempted"), now())
            .getOrNull()
            ?.nextAttemptMs

    private companion object {
        const val FALLBACK_RETRY_MS = 60_000L
    }
}
