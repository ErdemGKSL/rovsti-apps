package dev.rosty.messages.wa

import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.TypingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the other side of a chat does right now: who (in a group) and whether they type or record. */
data class WaChatState(
    /** The person, by the key the store keeps them under. */
    val who: String,
    val recording: Boolean,
)

/**
 * The chats whose other side types or records a voice note right now (E6-WA R3), for the inbox rows: the `typing`
 * events of [live] by chat key. A line goes when `paused` arrives, when their message arrives, and on its own after
 * [shownMs] (WhatsApp sends no `paused` when someone just stops), as on the thread's header.
 */
class WaChatStates(
    private val scope: CoroutineScope,
    private val live: Flow<GatewayEvent>,
    private val shownMs: Long = WaThreadViewModel.TYPING_SHOWN_MS,
) {
    private val states = MutableStateFlow<Map<String, WaChatState>>(emptyMap())
    private val clears = HashMap<String, Job>()
    private var job: Job? = null

    /** Chat key to what its other side does now. */
    val now: StateFlow<Map<String, WaChatState>> = states.asStateFlow()

    /** Starts following [live]; once. */
    fun start(): WaChatStates {
        if (job == null) job = scope.launch { live.collect(::onEvent) }
        return this
    }

    private fun onEvent(event: GatewayEvent): Unit =
        synchronized(clears) {
            onEventLocked(event)
        }

    private fun onEventLocked(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Typing -> {
                if (event.state == TypingState.Paused) {
                    clear(event.chat)
                } else {
                    val state = WaChatState(event.jid, event.state == TypingState.Recording)
                    states.update { it + (event.chat to state) }
                    clears.remove(event.chat)?.cancel()
                    clears[event.chat] =
                        scope.launch {
                            delay(shownMs)
                            synchronized(clears) {
                                clears.remove(event.chat)
                                states.update { it - event.chat }
                            }
                        }
                }
            }

            is GatewayEvent.Message -> {
                if (!event.fromMe) clear(event.chat)
            }

            else -> {
                Unit
            }
        }
    }

    private fun clear(chat: String) {
        clears.remove(chat)?.cancel()
        if (chat in states.value) states.update { it - chat }
    }
}
