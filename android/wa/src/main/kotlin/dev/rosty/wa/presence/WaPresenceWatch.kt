package dev.rosty.wa.presence

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.HeaderPresence
import dev.rosty.core.model.wa.PresenceSeen
import dev.rosty.core.model.wa.PresenceViewRequest
import dev.rosty.core.model.wa.TypingKind
import dev.rosty.core.model.wa.TypingSeen
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a [WaPresenceWatch] asks the gateway and Rust; the real one is [WaPresencePorts.of], tests give fakes. */
interface WaPresencePorts {
    /** `wa.subscribe_presence`. */
    suspend fun subscribe(jid: String): Outcome<Unit>

    /** `wa.unsubscribe_presence` (an older gateway answers not found; that is fine). */
    suspend fun unsubscribe(jid: String): Outcome<Unit>

    /** `wa.set_presence`: WhatsApp sends presence only to an account that is available itself. */
    suspend fun available(available: Boolean): Outcome<Unit>

    /** `wa.presence_view`: the line from what was seen (`rosty_core::wa_rich::header_presence`). */
    suspend fun view(req: PresenceViewRequest): Outcome<HeaderPresence>
}

/**
 * The presence line of the one chat on screen (E6-WA R5), shared by Messages' thread header and Phone's WhatsApp call
 * screen through `:wa`.
 *
 * - [watch] subscribes to the person of a one-to-one chat (a group is never subscribed: the line shows only who
 *   types or records) and marks the account available; [leave] unsubscribes, marks it unavailable and drops what
 *   was seen. Events after [leave], or for anyone but the watched chat, change nothing.
 * - [onEvent] takes the gateway's `presence` and `typing` events as they come (the caller's stream, with the ids it
 *   keys chats by; [WaIds.same] matches a number to its LID form).
 * - The rules are Rust's: typing beats recording beats online beats last seen; a typing state ends by itself after
 *   25 s (the watch looks again then); a hidden last seen shows nothing.
 */
class WaPresenceWatch(
    private val ports: WaPresencePorts,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val line = MutableStateFlow(HeaderPresence.NONE)

    /** The line for the watched chat; [HeaderPresence.NONE] while nothing is watched or known. */
    val state: StateFlow<HeaderPresence> = line.asStateFlow()

    private val mutex = Mutex()
    private var chat: String? = null
    private var group = false
    private var presence: PresenceSeen? = null
    private val typing = ArrayList<TypingSeen>()
    private var expiry: Job? = null

    /** The chat [jid] is on screen. A second watch of another chat leaves the first. */
    suspend fun watch(
        jid: String,
        isGroup: Boolean,
    ) {
        val previous =
            mutex.withLock {
                val was = chat
                chat = jid
                group = isGroup
                presence = null
                typing.clear()
                was
            }
        if (previous != null && previous != jid && !WaIds.isGroup(previous)) ports.unsubscribe(previous)
        ports.available(true)
        if (!isGroup) ports.subscribe(jid)
        recompute()
    }

    /** The chat left the screen: unsubscribed, unavailable, nothing shown. */
    suspend fun leave() {
        val was =
            mutex.withLock {
                val was = chat
                chat = null
                presence = null
                typing.clear()
                expiry?.cancel()
                line.value = HeaderPresence.NONE
                was
            } ?: return
        if (!WaIds.isGroup(was)) ports.unsubscribe(was)
        ports.available(false)
    }

    /** One gateway event; only `presence` and `typing` of the watched chat count. */
    suspend fun onEvent(event: GatewayEvent) {
        val changed =
            mutex.withLock {
                val watched = chat ?: return
                when (event) {
                    is GatewayEvent.Presence -> {
                        if (group || !(WaIds.same(event.jid, watched) || WaIds.same(event.jidAlt, watched))) {
                            return
                        }
                        presence = PresenceSeen(event.available, event.lastSeenMs)
                        true
                    }

                    is GatewayEvent.Typing -> {
                        if (!WaIds.same(event.chat, watched)) return
                        typing.removeAll { WaIds.same(it.jid, event.jid) }
                        when (event.state) {
                            TypingState.Typing -> typing += TypingSeen(event.jid, TypingKind.Typing, now())
                            TypingState.Recording -> typing += TypingSeen(event.jid, TypingKind.Recording, now())
                            TypingState.Paused -> Unit
                        }
                        true
                    }

                    // Their message arrived: they are not typing any more.
                    is GatewayEvent.Message -> {
                        if (!WaIds.same(event.chat, watched) || event.fromMe) return
                        val who = event.sender ?: event.chat
                        typing.removeAll { WaIds.same(it.jid, who) }
                    }

                    else -> {
                        false
                    }
                }
            }
        if (changed) recompute()
    }

    private suspend fun recompute() {
        val req =
            mutex.withLock {
                chat ?: return
                PresenceViewRequest(group, presence, typing.toList(), now())
            }
        val shown = (ports.view(req) as? Outcome.Ok)?.value ?: HeaderPresence.NONE
        mutex.withLock {
            // A leave while Rust answered wins.
            if (chat == null) return
            line.value = shown
            expiry?.cancel()
            val oldest = typing.minOfOrNull { it.atMs } ?: return
            val wait = (oldest + TYPING_TTL_MS - now()).coerceAtLeast(0) + 1
            expiry =
                scope.launch {
                    delay(wait)
                    // The states waited for are over even when the clock did not move with the wait (it was set
                    // back): without that the watch would wait again and again.
                    mutex.withLock { typing.removeAll { it.atMs <= oldest || now() - it.atMs >= TYPING_TTL_MS } }
                    recompute()
                }
        }
    }

    companion object {
        /** `rosty_core::wa_rich::TYPING_TTL_MS`. */
        const val TYPING_TTL_MS = 25_000L
    }
}
