package dev.rovsti.wa.calls

import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.platform.wa.WaIncoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Answer and Decline tapped on the incoming WhatsApp call notification (AD-WA5, AD-WA6). The activity hands the payload
 * of `WaIncoming.takeAction` to [offer]; this class makes it take effect exactly once per call:
 *
 * - nothing runs before [ready] says the Rust core is initialised (a cold start from the notification reaches this
 *   code before `Rust.init` is done; every op would wait anyway, and the gateway must not be opened early);
 * - Decline rejects by id at once, the gateway needs no call on screen;
 * - Answer waits, at most [TTL_MS], until the call is ringing in [WaCalls.calls] (a cold process learns of it from the
 *   gateway's snapshot) and then accepts; a call that ended meanwhile is dropped;
 * - the first action for a call wins: a late Decline cannot undo an Answer, and redelivered intents do nothing.
 */
class WaCallActions(
    private val calls: WaCalls,
    private val ready: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    private val handled = LinkedHashSet<String>()

    /** True when the payload is a new action for a call and is now running; false for junk and repeats. */
    @Synchronized
    fun offer(payload: String): Boolean {
        val (id, action) = parse(payload) ?: return false
        if (!handled.add(id)) return false
        if (handled.size > MEMORY) handled.remove(handled.first())
        scope.launch {
            ready()
            if (action == WaIncoming.DECLINE) calls.reject(id) else answer(id)
        }
        return true
    }

    /** True when an action for [callId] already ran or is running (a snapshot must not show that call again). */
    @Synchronized
    fun handled(callId: String): Boolean = callId in handled

    private suspend fun answer(id: String) {
        val due =
            withTimeoutOrNull(TTL_MS) {
                calls.calls.first { list ->
                    val call = list.firstOrNull { it.callId == id }
                    call != null && (call.isRinging() || call.phase is CallPhase.Ended)
                }
            } ?: return
        val call = due.firstOrNull { it.callId == id } ?: return
        if (call.isRinging()) calls.accept(id)
    }

    private fun dev.rovsti.core.model.wa.CallSummary.isRinging() =
        direction == CallDirection.In && phase == CallPhase.Ringing

    private fun parse(payload: String): Pair<String, String>? =
        runCatching {
            val obj = Json.parseToJsonElement(payload).jsonObject
            val id = obj["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            val action = obj["action"]?.jsonPrimitive?.contentOrNull
            if (id != null && (action == WaIncoming.ANSWER || action == WaIncoming.DECLINE)) id to action else null
        }.getOrNull()

    companion object {
        /** A notification action waits this long for its call to reach the app (`wa_call::ACTION_TTL_MS`). */
        const val TTL_MS = 60_000L
        private const val MEMORY = 16
    }
}
