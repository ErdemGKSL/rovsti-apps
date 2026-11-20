package dev.rosty.messages.wa

import androidx.lifecycle.viewModelScope
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.Location
import dev.rosty.core.model.wa.Poll
import dev.rosty.core.model.wa.PollOption
import dev.rosty.core.model.wa.RichContent
import dev.rosty.core.model.wa.SharedContact
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.RichCardUi
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.names.FakeLiveNames
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.wa.presence.WaPresenceWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** E6-WA R5: poll, place and contact bubbles, voting, rich sends and the header's presence line in a thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaRichThreadTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")
    private val book = MutableStateFlow(mapOf("+905550000001" to "Ada Lovelace", "+905550000002" to "Mert"))
    private val made = mutableListOf<WaThreadViewModel>()
    private val minute = 60_000L

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() {
        made.forEach { it.viewModelScope.cancel() }
        made.clear()
        Dispatchers.resetMain()
    }

    private fun model(
        kit: WaTestKit,
        jid: String = ADA,
        rules: WaActionRules? = FakeWaActionRules(),
    ): WaThreadViewModel {
        kit.offline.start()
        return WaThreadViewModel(
            WaThreadSources(
                offline = kit.offline,
                remote = kit.remote,
                emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                drafts = MemoryDrafts(),
                names = PersonNames({ book.value[it] }, book, FakeLiveNames()),
                now = { kit.now },
                rules = rules,
                ownIds = { listOf(OWN) },
            ),
            jid,
            EnglishWaWords,
            EnglishInboxWords,
            days,
        ).also(made::add)
    }

    private fun bubbles(state: ConversationUi): List<BubbleUi> =
        state.items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }

    private fun bubble(
        model: WaThreadViewModel,
        id: String,
    ): BubbleUi = bubbles(model.state.value).single { it.key == id }

    private fun queued(kit: WaTestKit): List<WaSyncAction> =
        kit.ops.writes
            .filterIsInstance<WaSyncEnqueueRequest>()
            .map { it.action }

    private fun row(
        chat: String,
        id: String,
        kind: WaSyncKind,
        sender: String? = chat,
    ): WaSyncMessage = WaTestKit.text(chat, id, NOW - 5 * minute, "", sender = sender).copy(kind = kind, text = null)

    private val lunch =
        Poll(
            question = "Lunch?",
            options =
                listOf(
                    PollOption("Pizza", listOf(ADA, MERT, OWN)),
                    PollOption("Salad", listOf(MERT)),
                    PollOption("Soup"),
                ),
            selectable = 0,
        )

    // ---- polls

    @Test fun aPollShowsItsCountsMyChoiceTheLeaderAndWhoVoted() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(GROUP, name = "Lunch club", group = true)
            kit.message(row(GROUP, "p1", WaSyncKind.Poll, sender = ADA).copy(poll = lunch))
            val model = model(kit, GROUP)
            settle()

            val poll = bubble(model, "p1").rich as RichCardUi.Poll
            assertEquals("Lunch?", poll.question)
            assertFalse("selectable 0 is any number of answers", poll.single)
            assertEquals("Select one or more", poll.hint)
            assertEquals("3 votes", poll.total)
            val (pizza, salad, soup) = poll.options
            assertEquals(3, pizza.count)
            assertTrue("my number among the voters is me", pizza.mine && pizza.leading)
            assertEquals(1f, pizza.share)
            assertEquals("Ada Lovelace, Mert, You", pizza.voters)
            assertFalse(salad.mine || salad.leading)
            assertEquals(0, soup.count)
            assertEquals("", soup.voters)
            assertTrue(poll.canVote)
        }

    @Test fun aVoteGoesOutAndShowsAtOnceAndATakenBackVoteToo() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            val single = lunch.copy(selectable = 1, options = lunch.options.map { it.copy(voters = emptyList()) })
            kit.message(row(ADA, "p2", WaSyncKind.Poll).copy(poll = single))
            val model = model(kit)
            settle()
            assertTrue((bubble(model, "p2").rich as RichCardUi.Poll).single)

            model.vote("p2", listOf("Salad"))
            settle()
            assertEquals(WaSyncAction.PollVote("p2", listOf("Salad")), queued(kit).last())
            val voted = bubble(model, "p2").rich as RichCardUi.Poll
            assertTrue("the vote shows before the gateway answers", voted.options[1].mine)
            assertEquals("1 votes", voted.total)

            model.vote("p2", emptyList())
            settle()
            assertEquals(WaSyncAction.PollVote("p2", emptyList()), queued(kit).last())
            assertTrue((bubble(model, "p2").rich as RichCardUi.Poll).options.none { it.mine })
        }

    @Test fun aPollIsNotDrawnWithoutTheRules() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(row(ADA, "p3", WaSyncKind.Poll).copy(poll = lunch))
            val model = model(kit, rules = null)
            settle()
            assertNull(bubble(model, "p3").rich)
        }

    // ---- places

    @Test fun aPlaceWithoutANameIsASharedLocationWithItsCoordinates() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(row(ADA, "l1", WaSyncKind.Location).copy(location = Location(41.0082, 28.9784)))
            kit.message(
                row(ADA, "l2", WaSyncKind.Location).copy(
                    id = "l2",
                    location = Location(41.0, 29.0, name = "Ferry pier", address = "Kadikoy", live = true),
                ),
            )
            val model = model(kit)
            settle()

            val bare = bubble(model, "l1").rich as RichCardUi.Place
            assertEquals("Shared location", bare.title)
            assertEquals("41.00820, 28.97840", bare.subtitle)
            assertNull(bare.live)
            assertEquals("geo:41.0082,28.9784", bare.geoUri)
            val named = bubble(model, "l2").rich as RichCardUi.Place
            assertEquals("Ferry pier", named.title)
            assertEquals("Kadikoy", named.subtitle)
            assertEquals("a live location shows its first point only", "Live location", named.live)
        }

    // ---- contact cards

    @Test fun aContactCardWithSeveralNumbersOrNone() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            val two =
                "BEGIN:VCARD\nVERSION:3.0\nFN:Deniz\nTEL;type=CELL;waid=100000000061:+1 000-000-0061\n" +
                    "TEL;type=HOME:+1 000-000-0062\nEND:VCARD"
            val none = "BEGIN:VCARD\nVERSION:3.0\nFN:Front desk\nEND:VCARD"
            kit.message(
                row(ADA, "c1", WaSyncKind.Contact).copy(
                    contacts = listOf(SharedContact("Deniz", two), SharedContact("Front desk", none)),
                ),
            )
            val model = model(kit)
            settle()

            val (deniz, desk) = (bubble(model, "c1").rich as RichCardUi.Contacts).cards
            assertEquals("Deniz", deniz.name)
            assertEquals(listOf("+1 000-000-0061", "+1 000-000-0062"), deniz.numbers)
            assertEquals("the number on WhatsApp opens its chat", "100000000061@s.whatsapp.net", deniz.messageJid)
            assertEquals("Front desk", desk.name)
            assertTrue(desk.numbers.isEmpty())
            assertNull("no number: no Message", desk.messageJid)
        }

    // ---- sends

    @Test fun aPlaceAndAPollGoOutAsRichSends() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            val model = model(kit)
            settle()

            assertTrue(model.sendPlace(41.0, 29.0))
            settle()
            assertEquals(WaSyncAction.SendRich(RichContent.Place(41.0, 29.0), null), queued(kit).last())
            assertFalse(
                "the fake rules check no draft: nothing goes",
                model.sendPoll(
                    dev.rosty.core.model.wa
                        .PollCheckRequest("Q", listOf("a", "b"), false),
                ),
            )
        }

    // ---- presence

    @Test fun theHeaderShowsOnlineAndTypingOnlyWhileTheChatIsOnScreen() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            val model = model(kit)
            settle()
            assertTrue("nothing is asked before the page shows", kit.remote.presenceCalls.isEmpty())

            model.setVisible(true)
            settle()
            assertEquals(listOf("available true", "subscribe $ADA"), kit.remote.presenceCalls)
            assertEquals("+905550000001", model.state.value.subtitle)

            kit.remote.emit(5, GatewayEvent.Presence(ADA, available = true))
            settle()
            assertEquals("online", model.state.value.subtitle)
            kit.remote.emit(6, GatewayEvent.Typing(ADA, ADA, TypingState.Typing))
            settle()
            assertEquals("typing…", model.state.value.subtitle)
            advanceTimeBy(WaPresenceWatch.TYPING_TTL_MS + 1)
            runCurrent()
            assertEquals("typing ends by itself", "online", model.state.value.subtitle)

            model.setVisible(false)
            settle()
            assertEquals(
                listOf("available true", "subscribe $ADA", "unsubscribe $ADA", "available false"),
                kit.remote.presenceCalls,
            )
            kit.remote.emit(7, GatewayEvent.Presence(ADA, available = true))
            settle()
            assertEquals("events after leaving are ignored", "+905550000001", model.state.value.subtitle)
        }

    @Test fun aHiddenLastSeenShowsNothingAndAGroupIsNeverSubscribed() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.chat(GROUP, name = "Lunch club", group = true)
            val one = model(kit)
            val group = model(kit, GROUP)
            settle()
            one.setVisible(true)
            group.setVisible(true)
            settle()
            assertFalse(kit.remote.presenceCalls.any { it == "subscribe $GROUP" })

            kit.remote.emit(5, GatewayEvent.Presence(ADA, available = false, lastSeenMs = null))
            settle()
            assertEquals("hidden last seen: the number stays", "+905550000001", one.state.value.subtitle)
            kit.remote.emit(6, GatewayEvent.Presence(MERT, available = true))
            settle()
            assertEquals("a member's presence is not the group's line", null, group.state.value.subtitle)
        }

    private companion object {
        /** My own number, as the gateway names it. */
        const val OWN = "100000000060@s.whatsapp.net"
    }
}
