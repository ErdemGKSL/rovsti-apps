package dev.rosty.messages.wa

import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.names.FakeLiveNames
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.ADA_LID
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * An open WhatsApp thread follows its person (E6-WA R1): the id it was opened with is resolved once, and when the
 * store learns that the chat is another one's the thread swaps its key and keeps what the user sees and typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaThreadRekeyTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")
    private val drafts = MemoryDrafts()
    private val book = MutableStateFlow(mapOf("+905550000001" to "Ada Lovelace"))
    private val minute = 60_000L

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    /** Drafts move with a merge, as the app's `MessagesStore` moves them (the draft of the surviving chat is kept). */
    private val moveDrafts =
        RekeyMover { step ->
            val (from, into) = WaRekeys.tagOf(step.from) to WaRekeys.tagOf(step.into)
            drafts.saved.remove(from)?.let { if (into !in drafts.saved) drafts.saved[into] = it }
        }

    private fun kit(scope: TestScope) = WaTestKit(scope.backgroundScope, rekeys = WaRekeys(movers = listOf(moveDrafts)))

    private fun model(
        kit: WaTestKit,
        jid: String,
    ): WaThreadViewModel {
        kit.offline.start()
        return WaThreadViewModel(
            WaThreadSources(
                offline = kit.offline,
                remote = kit.remote,
                emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                drafts = drafts,
                names =
                    PersonNames(
                        { book.value[it] },
                        book,
                        FakeLiveNames(),
                        numbers = { kit.offline.numbersOf(it) },
                    ),
                divider = { incoming, unread -> incoming.size - unread },
                now = { kit.now },
                rekeys = kit.offline.rekeys.rekeys,
            ),
            jid,
            EnglishWaWords,
            EnglishInboxWords,
            days,
        )
    }

    private fun bubbles(state: ConversationUi): List<BubbleUi> =
        state.items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }

    @Test fun aThreadOpenedByAnIdOfBeforeAMergeShowsTheMergedChat() =
        scope.runTest {
            val kit = kit(this)
            kit.chat(ADA, name = "Ada", pushName = "ada")
            kit.message(WaTestKit.text(ADA, "p1", NOW - 20 * minute, "under the number"))
            kit.message(WaTestKit.text(ADA_LID, "l1", NOW - 10 * minute, "under the LID"))
            // The store learned the pair before the page opened; a notification tag still names the LID.
            kit.ops.merge(ADA_LID, ADA)
            drafts.saved["wa:$ADA"] = "half a thought"

            val model = model(kit, "777000000000001:4@lid")
            settle()

            assertEquals(ADA, model.jid)
            assertEquals(ADA, model.chatJid.value)
            assertEquals("wa:$ADA", model.tag)
            val state = model.state.value
            assertEquals(listOf("l1", "p1"), bubbles(state).map { it.key })
            // Named and pictured by the number the store knows for the person.
            assertEquals("Ada Lovelace", state.title)
            assertEquals("+905550000001", state.subtitle)
            assertEquals(listOf("+905550000001"), state.avatar.numbers)
            assertEquals(ADA, state.avatar.waJid)
            assertEquals("half a thought", model.initialDraft(null))
        }

    @Test fun anOpenThreadSwapsItsKeyOnAMergeAndLosesNothing() =
        scope.runTest {
            val kit = kit(this)
            kit.chat(ADA_LID, pushName = "ada")
            kit.chat(ADA, name = "Ada", pushName = "ada")
            kit.message(WaTestKit.text(ADA_LID, "l1", NOW - 30 * minute, "hi"))
            kit.message(WaTestKit.text(ADA_LID, "l2", NOW - 20 * minute, "are you there?"))
            kit.message(WaTestKit.text(ADA, "p1", NOW - 40 * minute, "an older one, under the number"))
            val model = model(kit, ADA_LID)
            settle()
            kit.connect()
            settle()
            // Opened on the LID chat: no number is known, the person is named by what WhatsApp carries.
            assertEquals(ADA_LID, model.jid)
            assertEquals("ada", model.state.value.title)
            assertNull(model.state.value.subtitle)
            assertEquals(listOf("l2", "l1"), bubbles(model.state.value).map { it.key })

            // The user is in the middle of an answer to l2.
            model.onDraft("on my way")
            model.startReply("l2")
            settle()
            assertEquals("on my way", drafts.saved["wa:$ADA_LID"])
            assertEquals(
                "l2",
                model.state.value.reply
                    ?.key,
            )
            val states = ArrayList<ConversationUi>()
            backgroundScope.launch { model.state.collect { states += it } }
            runCurrent()

            // The gateway learns whose LID it is: the two chats become one.
            kit.remote.emit(9, GatewayEvent.IdentityMapped(ADA_LID, ADA, "peer_message", NOW))
            settle()

            assertEquals(ADA, model.jid)
            assertEquals("wa:$ADA", model.tag)
            val state = model.state.value
            // The same page, now with the rows of both chats, the reply bar still up.
            assertEquals(listOf("l2", "l1", "p1"), bubbles(state).map { it.key })
            // Bubble keys are message ids, not chat ids: the scroll anchor of the page survives the merge.
            assertEquals(listOf("l2", "l1"), bubbles(states.first()).map { it.key })
            assertEquals(listOf("l2", "l1"), bubbles(state).map { it.key }.filter { it.startsWith("l") })
            assertEquals("l2", state.reply?.key)
            assertEquals("Ada Lovelace", state.title)
            assertEquals("+905550000001", state.subtitle)
            assertEquals(ADA, state.avatar.waJid)
            // The page never went blank or back to "loading" on the way.
            assertTrue(states.all { it.loaded && bubbles(it).isNotEmpty() })
            // The draft moved with the chat, and what is typed next is kept under the new key.
            assertNull(drafts.saved["wa:$ADA_LID"])
            assertEquals("on my way", drafts.saved["wa:$ADA"])
            model.onDraft("on my way, five minutes")
            settle()
            assertEquals("on my way, five minutes", drafts.saved["wa:$ADA"])
            assertNull(drafts.saved["wa:$ADA_LID"])

            // Sending goes to the merged chat, as an answer to l2.
            model.send("on my way, five minutes")
            settle()
            val sent = kit.remote.performed.last()
            assertEquals(ADA, sent.first)
            assertEquals("l2", (sent.second as WaSyncAction.SendText).quotedId)
            assertNull(model.state.value.reply)

            // What arrives for the person now, under either id, shows on the page.
            kit.remote.emit(10, GatewayEvent.Typing(ADA_LID, ADA_LID, TypingState.Typing))
            runCurrent()
            assertEquals("typing…", model.state.value.subtitle)
            kit.remote.emit(11, WaTestKit.incoming(ADA_LID, "l3", NOW - minute, "ok"))
            settle()
            assertEquals("l3", bubbles(model.state.value).first { !it.outgoing }.key)
            assertEquals("+905550000001", model.state.value.subtitle)
        }

    @Test fun aMergeOfAnotherChatLeavesTheThreadAlone() =
        scope.runTest {
            val kit = kit(this)
            kit.chat(MERT, name = "Mert")
            kit.message(WaTestKit.text(MERT, "m1", NOW - 30 * minute, "hello"))
            drafts.saved["wa:$MERT"] = "to Mert"
            val model = model(kit, MERT)
            settle()
            kit.connect()
            settle()

            kit.remote.emit(9, GatewayEvent.IdentityMapped(ADA_LID, ADA, "usync", NOW))
            settle()

            assertEquals(MERT, model.jid)
            assertEquals(listOf("m1"), bubbles(model.state.value).map { it.key })
            assertEquals("to Mert", drafts.saved["wa:$MERT"])
        }

    @Test fun aLidNobodyNamesIsAWhatsAppUserWithNoNumberToShow() =
        scope.runTest {
            val kit = kit(this)
            kit.chat(ADA_LID)
            kit.message(WaTestKit.text(ADA_LID, "l1", NOW - 30 * minute, "hi"))
            val model = model(kit, ADA_LID)
            settle()

            val state = model.state.value
            assertEquals(PersonNames.UNKNOWN_USER, state.title)
            assertNull(state.subtitle)
            assertTrue(state.avatar.numbers.isEmpty())
            assertFalse(state.title.any(Char::isDigit))
        }

    @Test fun reactionsReceiptsAndDeletesNameTheAuthorByTheIdTheMessageArrivedUnder() =
        scope.runTest {
            val kit = kit(this)
            kit.chat(GROUP, name = "Weekend hike", group = true, unread = 2)
            // The group is LID-addressed: the store keeps Ada's messages under her number and remembers the LID.
            kit.message(
                WaTestKit
                    .text(GROUP, "g1", NOW - 30 * minute, "Who brings water?", sender = ADA)
                    .copy(wireSender = ADA_LID),
            )
            kit.message(WaTestKit.text(GROUP, "g2", NOW - 20 * minute, "I can", sender = MERT))
            val model = model(kit, GROUP)
            settle()
            kit.connect()
            settle()

            model.setVisible(true)
            settle()
            val reads =
                kit.remote.performed
                    .map { it.second }
                    .filterIsInstance<WaSyncAction.MarkRead>()
            assertEquals(
                setOf(ADA_LID to listOf("g1"), MERT to listOf("g2")),
                reads.map { it.sender to it.ids }.toSet(),
            )

            model.react("g1", "👍")
            model.react("g2", "👍")
            settle()
            val reactions =
                kit.remote.performed
                    .map { it.second }
                    .filterIsInstance<WaSyncAction.React>()
            assertEquals(listOf("g1" to ADA_LID, "g2" to MERT), reactions.map { it.messageId to it.sender })
            // The row itself still names her by the store's key (names, avatars, the sender card).
            val byKey = bubbles(model.state.value).associateBy { it.key }
            assertEquals(ADA, byKey.getValue("g1").senderId)
            assertEquals("Ada Lovelace", byKey.getValue("g1").sender)
            assertTrue(
                kit.ops.writes
                    .filterIsInstance<WaSyncEnqueueRequest>()
                    .all { it.chat == GROUP },
            )
        }

    @Test fun theSenderCardOfAMemberNamedByALidShowsTheNumberTheStoreKnows() =
        scope.runTest {
            val kit = kit(this)
            kit.chat(GROUP, name = "Weekend hike", group = true)
            kit.message(WaTestKit.text(GROUP, "g1", NOW - 30 * minute, "hello", sender = ADA_LID, senderName = "ada"))
            val model = model(kit, GROUP)
            settle()
            kit.connect()
            settle()

            // A hidden member: no number, so no call and no contact rows; never the digits of the LID.
            val hidden = model.senderCard(ADA_LID)
            assertEquals("ada", hidden.name)
            assertNull(hidden.number)
            assertFalse(hidden.saved)
            assertTrue(hidden.avatar.numbers.isEmpty())

            kit.remote.emit(9, GatewayEvent.IdentityMapped(ADA_LID, ADA, "group_metadata", NOW))
            settle()
            // The row is hers under the number now, and the card of the id the page still holds says so too.
            assertEquals(ADA, bubbles(model.state.value).single().senderId)
            assertEquals("Ada Lovelace", bubbles(model.state.value).single().sender)
            val known = model.senderCard(ADA_LID)
            assertEquals("+905550000001", known.number)
            assertEquals("Ada Lovelace", known.name)
            assertTrue(known.saved)
        }
}
