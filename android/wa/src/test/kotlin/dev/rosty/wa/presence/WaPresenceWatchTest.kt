package dev.rosty.wa.presence

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.HeaderPresence
import dev.rosty.core.model.wa.HeaderPresenceKind
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.PresenceViewRequest
import dev.rosty.core.model.wa.TypingKind
import dev.rosty.core.model.wa.TypingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** E6-WA R5: the presence line of the chat on screen, its subscription rules and the typing expiry. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaPresenceWatchTest {
    private val peer = "100000000041@s.whatsapp.net"
    private val peerLid = "777000000000041@lid"
    private val group = "120363000000000041@g.us"

    /** The ports, recording what was asked; the view is a Kotlin copy of Rust's rule for the cases here. */
    private class Ports(
        private val clock: () -> Long,
    ) : WaPresencePorts {
        val asked = ArrayList<String>()

        override suspend fun subscribe(jid: String): Outcome<Unit> {
            asked += "subscribe $jid"
            return Outcome.Ok(Unit)
        }

        override suspend fun unsubscribe(jid: String): Outcome<Unit> {
            asked += "unsubscribe $jid"
            return Outcome.Ok(Unit)
        }

        override suspend fun available(available: Boolean): Outcome<Unit> {
            asked += "available $available"
            return Outcome.Ok(Unit)
        }

        override suspend fun view(req: PresenceViewRequest): Outcome<HeaderPresence> {
            val live = req.typing.filter { clock() - it.atMs < WaPresenceWatch.TYPING_TTL_MS }
            val typing = live.filter { it.kind == TypingKind.Typing }.map { it.jid }
            val recording = live.filter { it.kind == TypingKind.Recording }.map { it.jid }
            val seen = req.presence
            return Outcome.Ok(
                when {
                    typing.isNotEmpty() -> HeaderPresence(HeaderPresenceKind.Typing, who = typing)
                    recording.isNotEmpty() -> HeaderPresence(HeaderPresenceKind.Recording, who = recording)
                    req.group -> HeaderPresence.NONE
                    seen?.available == true -> HeaderPresence(HeaderPresenceKind.Online)
                    seen?.lastSeenMs != null -> HeaderPresence(HeaderPresenceKind.LastSeen, atMs = seen.lastSeenMs)
                    else -> HeaderPresence.NONE
                },
            )
        }
    }

    private fun TestScope.watch(): Pair<WaPresenceWatch, Ports> {
        val ports = Ports { testScheduler.currentTime }
        return WaPresenceWatch(ports, this) { testScheduler.currentTime } to ports
    }

    @Test fun aOneToOneChatIsSubscribedAndShowsOnlineThenLastSeen() =
        runTest {
            val (watch, ports) = watch()
            watch.watch(peer, isGroup = false)
            assertEquals(listOf("available true", "subscribe $peer"), ports.asked)

            // The event names the person by LID, the chat is keyed by number: the same person.
            watch.onEvent(GatewayEvent.Presence(peerLid, available = true, jidAlt = peer))
            assertEquals(HeaderPresenceKind.Online, watch.state.value.kind)
            watch.onEvent(GatewayEvent.Presence(peer, available = false, lastSeenMs = 1_000L))
            assertEquals(HeaderPresence(HeaderPresenceKind.LastSeen, atMs = 1_000L), watch.state.value)
            // Last seen hidden: nothing.
            watch.onEvent(GatewayEvent.Presence(peer, available = false))
            assertEquals(HeaderPresence.NONE, watch.state.value)
        }

    @Test fun typingEndsOnItsOwnOrWithTheirMessage() =
        runTest {
            val (watch, _) = watch()
            watch.watch(peer, isGroup = false)
            watch.onEvent(GatewayEvent.Typing(peer, peer, TypingState.Typing))
            assertEquals(HeaderPresenceKind.Typing, watch.state.value.kind)
            advanceTimeBy(WaPresenceWatch.TYPING_TTL_MS + 5)
            runCurrent()
            assertEquals(HeaderPresence.NONE, watch.state.value)

            watch.onEvent(GatewayEvent.Typing(peer, peer, TypingState.Recording))
            assertEquals(HeaderPresenceKind.Recording, watch.state.value.kind)
            watch.onEvent(GatewayEvent.Message("m1", peer, null, false, 1, MessageBody.Text("hi"), emptyList()))
            assertEquals(HeaderPresence.NONE, watch.state.value)
        }

    @Test fun typingEndsEvenWhenTheClockDoesNotMoveWithTheWait() =
        runTest {
            // A clock that stands still (set back on the device): the wait alone ends the typing state, once.
            val ports = Ports { 0L }
            val watch = WaPresenceWatch(ports, backgroundScope) { 0L }
            watch.watch(peer, isGroup = false)
            watch.onEvent(GatewayEvent.Typing(peer, peer, TypingState.Typing))
            assertEquals(HeaderPresenceKind.Typing, watch.state.value.kind)
            advanceTimeBy(WaPresenceWatch.TYPING_TTL_MS + 5)
            runCurrent()
            assertEquals(HeaderPresence.NONE, watch.state.value)
        }

    @Test fun aGroupIsNeverSubscribedAndShowsOnlyWhoTypes() =
        runTest {
            val (watch, ports) = watch()
            watch.watch(group, isGroup = true)
            assertEquals(listOf("available true"), ports.asked)
            watch.onEvent(GatewayEvent.Presence(peer, available = true))
            assertEquals(HeaderPresence.NONE, watch.state.value)
            watch.onEvent(GatewayEvent.Typing(group, peer, TypingState.Typing))
            assertEquals(listOf(peer), watch.state.value.who)
        }

    @Test fun afterLeavingNothingChangesAndTheChatIsUnsubscribed() =
        runTest {
            val (watch, ports) = watch()
            watch.watch(peer, isGroup = false)
            watch.leave()
            assertEquals(
                listOf("available true", "subscribe $peer", "unsubscribe $peer", "available false"),
                ports.asked,
            )
            watch.onEvent(GatewayEvent.Presence(peer, available = true))
            watch.onEvent(GatewayEvent.Typing(peer, peer, TypingState.Typing))
            assertEquals(HeaderPresence.NONE, watch.state.value)
            // Another chat's events never count.
            watch.watch(peer, isGroup = false)
            watch.onEvent(GatewayEvent.Presence("100000000042@s.whatsapp.net", available = true))
            assertEquals(HeaderPresence.NONE, watch.state.value)
        }
}
