package dev.rosty.messages.wa

import androidx.lifecycle.viewModelScope
import dev.rosty.core.model.chat.SearchJumpRequest
import dev.rosty.core.model.chat.SearchJumpStep
import dev.rosty.core.model.wa.EditRecord
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.Quoted
import dev.rosty.core.model.wa.Reaction
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationEffect
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.names.FakeLiveNames
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

/** The words of a WhatsApp thread in English, for JVM tests (the same text as `values/strings.xml`). */
object EnglishWaWords : WaWords {
    override val placeholder = "Message"
    override val gone = "No longer available"
    override val sending = "Sending…"
    override val waiting = "Waiting for connection"
    override val notSent = "Not sent · Tap to retry"
    override val now = "Now"
    override val offline = "Offline · showing saved messages"
    override val syncing = "Updating…"
    override val you = "You"
    override val deleted = "This message was deleted"
    override val youDeleted = "You deleted this message"

    override fun typing(
        name: String?,
        recording: Boolean,
    ) = when {
        name == null -> if (recording) "recording audio…" else "typing…"
        recording -> "$name is recording audio…"
        else -> "$name is typing…"
    }

    override fun edited(clock: String) = "Edited $clock"

    override fun unread(count: Int) = if (count == 1) "1 unread message" else "$count unread messages"

    override fun size(bytes: Long) = plainSize(bytes)
}

/** Drafts in memory. */
class MemoryDrafts : WaDrafts {
    val saved = mutableMapOf<String, String>()

    override suspend fun load(tag: String) = saved[tag].orEmpty()

    override suspend fun put(
        tag: String,
        text: String,
    ) {
        saved[tag] = text
    }
}

/** A WhatsApp thread reads the store only: it is the same page with the gateway up, down or never reached. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaThreadViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")
    private val drafts = MemoryDrafts()
    private val book = MutableStateFlow(mapOf("+905550000001" to "Ada Lovelace"))
    private val live = FakeLiveNames()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    // A view-model's scope lives on Dispatchers.Main: cancel it before the test Main goes away, or a
    // late withContext(Default) resume of a finished test hits the missing Android Main.
    private val made = mutableListOf<WaThreadViewModel>()

    @After fun reset() {
        made.forEach { it.viewModelScope.cancel() }
        made.clear()
        Dispatchers.resetMain()
    }

    private fun model(
        kit: WaTestKit,
        jid: String = ADA,
    ): WaThreadViewModel {
        kit.offline.start()
        return newModel(kit, jid).also(made::add)
    }

    private fun newModel(
        kit: WaTestKit,
        jid: String,
    ): WaThreadViewModel =
        WaThreadViewModel(
            WaThreadSources(
                offline = kit.offline,
                remote = kit.remote,
                emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                drafts = drafts,
                names = PersonNames({ book.value[it] }, book, live),
                divider = { incoming, unread -> incoming.size - unread },
                now = { kit.now },
            ),
            jid,
            EnglishWaWords,
            EnglishInboxWords,
            days,
        )

    private fun bubbles(state: ConversationUi): List<BubbleUi> =
        state.items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }

    private val minute = 60_000L

    @Test fun theThreadIsReadFromTheStoreWithTheGatewayDown() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.remote.down = true
            kit.chat(ADA, name = "ada.l", pushName = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 90 * minute, "Are we still on?"))
            kit.message(
                WaTestKit.text(ADA, "m2", NOW - 80 * minute, "Yes!", fromMe = true, state = WaSyncRowState.Read),
            )
            kit.message(
                WaTestKit
                    .text(
                        ADA,
                        "m3",
                        NOW - 70 * minute,
                        "Six, not five",
                        fromMe = true,
                        state = WaSyncRowState.Delivered,
                    ).copy(editedMs = NOW - 69 * minute, editHistory = listOf(EditRecord("Five", NOW - 69 * minute))),
            )
            kit.message(
                WaTestKit.text(ADA, "m4", NOW - 60 * minute, "secret").copy(deleted = true, deletedBy = ADA),
            )
            kit.message(
                WaTestKit
                    .text(ADA, "m5", NOW - 50 * minute, "Look at this")
                    .copy(forwarded = true, reactions = listOf(Reaction(null, true, "👍"), Reaction(ADA, false, "👍"))),
            )
            val model = model(kit)
            settle()

            val state = model.state.value
            // The address-book name wins over WhatsApp's; the number is the subtitle.
            assertEquals("Ada Lovelace", state.title)
            assertEquals("+905550000001", state.subtitle)
            assertEquals(ADA, state.avatar.waJid)
            // Offline is said once, on the page; the messages are all there.
            assertEquals("Offline · showing saved messages", state.notice)
            assertTrue(state.loaded)
            val list = bubbles(state)
            assertEquals(listOf("m5", "m4", "m3", "m2", "m1"), list.map { it.key })
            val byKey = list.associateBy { it.key }
            assertEquals(BubbleState.Read, byKey.getValue("m2").state)
            assertEquals(BubbleState.Delivered, byKey.getValue("m3").state)
            assertEquals("Edited ${days.clock(NOW - 70 * minute)}", byKey.getValue("m3").meta)
            // A deleted message keeps its place and says so; its content is not shown.
            assertTrue(byKey.getValue("m4").deleted)
            assertEquals("This message was deleted", byKey.getValue("m4").text)
            assertTrue(byKey.getValue("m5").forwarded)
            assertEquals("👍 2", byKey.getValue("m5").reactions)
            assertEquals(BubbleState.None, byKey.getValue("m1").state)
            assertNull(byKey.getValue("m1").sender)
        }

    @Test fun withoutAContactThePushNameComesBeforeTheStoredChatNameAndAContactSavedLaterWins() =
        scope.runTest {
            book.value = emptyMap()
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "ada.l", pushName = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - minute, "Hi"))
            val model = model(kit)
            settle()

            assertEquals("Ada", model.state.value.title)
            assertEquals("+905550000001", model.state.value.subtitle)
            assertTrue("a stored WhatsApp name is a name: nothing is asked", live.asked.isEmpty())

            book.value = mapOf("+905550000001" to "Ada Lovelace")
            settle()
            assertEquals("Ada Lovelace", model.state.value.title)
        }

    @Test fun anUnknownNumberShowsItsNumberAndTakesTheWhatsAppNameWhenItArrives() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(MERT)
            kit.chat(GROUP, name = "Weekend hike", group = true)
            kit.message(WaTestKit.text(MERT, "m1", NOW - minute, "Hi"))
            kit.message(WaTestKit.text(GROUP, "g1", NOW - minute, "Who brings water?", sender = MERT))
            val model = model(kit, MERT)
            val hike = model(kit, GROUP)
            settle()

            // Nobody names the number: it is the title (nothing repeats it below) and WhatsApp is asked.
            assertEquals("+905550000002", model.state.value.title)
            assertNull(model.state.value.subtitle)
            assertEquals("+905550000002", bubbles(hike.state.value).single().sender)
            assertEquals(setOf("+905550000002"), live.asked.toSet())

            live.land("+905550000002", "Mert K.")
            settle()
            assertEquals("Mert K.", model.state.value.title)
            assertEquals("+905550000002", model.state.value.subtitle)
            // The group keeps its subject; the person inside it is named.
            assertEquals("Weekend hike", hike.state.value.title)
            assertEquals("Mert K.", bubbles(hike.state.value).single().sender)
        }

    @Test fun aGroupShowsWhoWroteEachMessage() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(GROUP, name = "Weekend hike", group = true)
            kit.message(WaTestKit.text(GROUP, "g1", NOW - 30 * minute, "Who brings water?", sender = ADA))
            kit.message(
                WaTestKit.text(GROUP, "g2", NOW - 29 * minute, "I can", sender = MERT, senderName = "Mert K."),
            )
            kit.message(
                WaTestKit
                    .text(GROUP, "g3", NOW - 28 * minute, "Thanks", fromMe = true)
                    .copy(quoted = Quoted("g2", MERT, "I can")),
            )
            val model = model(kit, GROUP)
            settle()

            val state = model.state.value
            assertEquals("Weekend hike", state.title)
            assertNull(state.subtitle)
            assertTrue(state.avatar.group)
            val byKey = bubbles(state).associateBy { it.key }
            // Address book first, then the name WhatsApp carries.
            assertEquals("Ada Lovelace", byKey.getValue("g1").sender)
            assertEquals("Mert K.", byKey.getValue("g2").sender)
            assertNull(byKey.getValue("g3").sender)
            // A quote names no one: the name a loaded message of theirs carried.
            assertEquals("Mert K.", byKey.getValue("g3").quote?.author)
            assertEquals("I can", byKey.getValue("g3").quote?.text)

            kit.remote.emit(9, GatewayEvent.Typing(GROUP, ADA, TypingState.Typing))
            runCurrent()
            assertEquals("Ada Lovelace is typing…", model.state.value.subtitle)
        }

    @Test fun aMessageWrittenOfflineShowsAtOnceWithAClockAndGoesOutLater() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val model = model(kit)
            settle()

            model.send("  On my way  ")
            settle()

            val waiting = bubbles(model.state.value).single()
            assertEquals("On my way", waiting.text)
            assertEquals(BubbleState.Waiting, waiting.state)
            assertEquals("Waiting for connection", waiting.meta)
            assertTrue(waiting.outgoing)
            assertTrue(kit.remote.performed.isEmpty())
            assertEquals(listOf(0L), kit.wakes)
            assertEquals("", drafts.saved[model.tag])

            kit.connect()
            settle()

            val sent = bubbles(model.state.value).single()
            assertEquals(BubbleState.Sent, sent.state)
            // The bubble keeps its key from the moment it was written.
            assertEquals(waiting.key, sent.key)
            assertNull(model.state.value.notice)
        }

    @Test fun aRefusedMessageCanBeRetriedEditedOrCancelled() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val model = model(kit)
            settle()
            kit.connect()
            settle()
            val effects = mutableListOf<ConversationEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            kit.remote.answer = { _, _ ->
                dev.rosty.core.Outcome
                    .Err(
                        dev.rosty.core.RustError
                            .Invalid("too long"),
                    )
            }

            model.send("first")
            settle()
            val failed = bubbles(model.state.value).single()
            assertEquals(BubbleState.Failed, failed.state)
            assertEquals("Not sent · Tap to retry", failed.meta)

            kit.remote.answer = { _, _ ->
                dev.rosty.core.Outcome
                    .Ok(
                        dev.rosty.core.model.wa.WaSyncOutcome
                            .Sent("m1", NOW),
                    )
            }
            model.retry(failed.key)
            settle()
            assertEquals(BubbleState.Sent, bubbles(model.state.value).single().state)

            // A waiting message goes back into the composer to be edited.
            kit.remote.emit(WaRemoteEvent.Disconnected)
            settle()
            model.send("secnd")
            settle()
            val queued = bubbles(model.state.value).first { it.state == BubbleState.Waiting }
            model.cancel(queued.key, restore = true)
            settle()
            assertEquals(listOf("first"), bubbles(model.state.value).map { it.text })
            assertEquals(ConversationEffect.Restore("secnd"), effects.last())
        }

    @Test fun olderMessagesComeFromTheStoreAPageAtATime() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            repeat(120) { n -> kit.ops.put(ADA, "m%03d".format(n), NOW - (200 - n) * minute, "message $n") }
            val model = model(kit)
            settle()

            assertEquals(WaThreadViewModel.PAGE, bubbles(model.state.value).size)
            assertTrue(model.state.value.hasOlder)
            assertEquals("m119", bubbles(model.state.value).first().key)

            model.loadOlder()
            settle()
            assertEquals(2 * WaThreadViewModel.PAGE, bubbles(model.state.value).size)

            model.loadOlder()
            settle()
            assertEquals(120, bubbles(model.state.value).size)
            // The store has the start of the chat: nothing more to ask for.
            assertFalse(model.state.value.hasOlder)
            assertFalse(model.state.value.loadingOlder)
        }

    @Test fun aNewMessageArrivesThroughTheStoreAndIsMarkedReadWhileThePageShows() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada", unread = 2)
            kit.message(WaTestKit.text(ADA, "m1", NOW - 30 * minute, "old"))
            kit.message(WaTestKit.text(ADA, "m2", NOW - 20 * minute, "new one"))
            kit.message(WaTestKit.text(ADA, "m3", NOW - 10 * minute, "new two"))
            val model = model(kit)
            settle()
            kit.connect()
            settle()

            // The divider sits above the first unread message.
            val items = model.state.value.items
            val divider = items.indexOfFirst { it is ConversationItem.Divider }
            assertEquals("2 unread messages", (items[divider] as ConversationItem.Divider).label)
            assertEquals("m2", items[divider - 1].key)
            // Not on screen yet: nothing is marked read.
            assertTrue(kit.ops.writes.none { it is WaSyncEnqueueRequest })

            model.setVisible(true)
            settle()
            val read =
                kit.remote.performed
                    .single()
                    .second as WaSyncAction.MarkRead
            assertEquals(setOf("m2", "m3"), read.ids.toSet())

            kit.remote.emit(5, GatewayEvent.Typing(ADA, ADA, TypingState.Recording))
            runCurrent()
            assertEquals("recording audio…", model.state.value.subtitle)
            // Nothing says they stopped: the line clears on its own.
            advanceTimeBy(WaThreadViewModel.TYPING_SHOWN_MS + 1)
            runCurrent()
            assertEquals("+905550000001", model.state.value.subtitle)

            kit.remote.emit(6, WaTestKit.incoming(ADA, "m4", NOW - minute, "here"))
            settle()
            assertEquals("m4", bubbles(model.state.value).first().key)
        }

    @Test fun theDividerStaysWhereItOpenedAndANewOpeningPlacesItAgain() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada", unread = 2)
            kit.message(WaTestKit.text(ADA, "m1", NOW - 30 * minute, "old"))
            kit.message(WaTestKit.text(ADA, "m2", NOW - 20 * minute, "new one"))
            kit.message(WaTestKit.text(ADA, "m3", NOW - 10 * minute, "new two"))
            val model = model(kit)
            assertEquals(1, model.opened())
            settle()
            kit.connect()
            settle()
            assertEquals(1, model.state.value.opens)
            assertEquals("m2", aboveDivider(model.state.value))

            // A message that arrives while the page is up does not move the divider.
            kit.remote.emit(6, WaTestKit.incoming(ADA, "m4", NOW - minute, "here"))
            settle()
            assertEquals("m4", bubbles(model.state.value).first().key)
            assertEquals("m2", aboveDivider(model.state.value))

            // The thread is opened again on the kept view model: the unread count of that moment, a new divider.
            kit.ops.chats.removeAll { it.jid == ADA }
            kit.chat(ADA, name = "Ada", unread = 1)
            assertEquals(2, model.opened())
            settle()
            assertEquals(2, model.state.value.opens)
            assertEquals("m4", aboveDivider(model.state.value))
            assertEquals(
                "1 unread message",
                (
                    model.state.value.items
                        .first { it is ConversationItem.Divider } as ConversationItem.Divider
                ).label,
            )
        }

    /** The key of the bubble just under the unread divider (the first unread one). */
    private fun aboveDivider(state: ConversationUi): String? {
        val at = state.items.indexOfFirst { it is ConversationItem.Divider }
        return if (at > 0) state.items[at - 1].key else null
    }

    @Test fun attachmentsSayWhatTheyAreUntilTheMediaStoryShowsThem() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(
                WaTestKit
                    .text(ADA, "p1", NOW - 30 * minute, "The view")
                    .copy(kind = WaSyncKind.Image, mime = "image/jpeg", size = 2_048),
            )
            kit.message(
                WaTestKit.text(ADA, "v1", NOW - 20 * minute, "").copy(
                    kind = WaSyncKind.Audio,
                    text = null,
                    mime = "audio/ogg",
                    media = MediaMeta(seconds = 12, voiceNote = true),
                ),
            )
            val model = model(kit)
            settle()

            val byKey = bubbles(model.state.value).associateBy { it.key }
            assertEquals(
                "Photo",
                byKey
                    .getValue("p1")
                    .media
                    .single()
                    .label,
            )
            assertEquals("The view", byKey.getValue("p1").text)
            assertEquals(
                "Voice message (0:12)",
                byKey
                    .getValue("v1")
                    .media
                    .single()
                    .label,
            )
            assertEquals("", byKey.getValue("v1").text)
        }

    @Test fun notLinkedTheMessageComesBackToTheComposer() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope, linked = false)
            val model = model(kit)
            settle()
            val effects = mutableListOf<ConversationEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }

            model.send("hello")
            settle()

            assertEquals(ConversationEffect.Restore("hello"), effects.last())
            assertTrue(
                model.state.value.items
                    .isEmpty(),
            )
            // Without a chat row the number names the page.
            assertEquals("Ada Lovelace", model.state.value.title)
        }

    @Test fun theDraftIsKeptPerChatAndTypingIsSentWhileConnected() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            drafts.saved["wa:$ADA"] = "half a thought"
            val model = model(kit)
            settle()
            assertEquals("half a thought", model.initialDraft(null))
            assertEquals("from a link", model.initialDraft("from a link"))

            model.onDraft("half a thought, finished")
            settle()
            assertEquals("half a thought, finished", drafts.saved["wa:$ADA"])
            // Offline nobody is told.
            assertTrue(kit.remote.typed.isEmpty())

            kit.connect()
            settle()
            model.onDraft("more")
            model.onDraft("more and more")
            settle()
            assertEquals(listOf(ADA to TypingState.Typing), kit.remote.typed)
        }

    @Test fun aSearchHitIsRevealedAfterLoadingThePagesThatReachIt() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            repeat(120) { n -> kit.ops.put(ADA, "m%03d".format(n), NOW - (200 - n) * minute, "message $n") }
            kit.offline.start()
            val asked = mutableListOf<SearchJumpRequest>()
            val model =
                WaThreadViewModel(
                    WaThreadSources(
                        offline = kit.offline,
                        remote = kit.remote,
                        emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                        drafts = drafts,
                        names = PersonNames({ null }, book),
                        searchJump = { request ->
                            asked += request
                            if (request.pagesLoaded < 10) SearchJumpStep.LoadOlder else SearchJumpStep.FurtherBack
                        },
                        now = { kit.now },
                    ),
                    ADA,
                    EnglishWaWords,
                    EnglishInboxWords,
                    days,
                ).also(made::add)
            val revealed = mutableListOf<String>()
            backgroundScope.launch { model.reveals.collect { revealed += it } }
            val effects = mutableListOf<ConversationEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            settle()

            // Loaded already: no page is asked for.
            model.reveal("m119", NOW - 81 * minute)
            settle()
            assertEquals(listOf("m119"), revealed)
            assertTrue(asked.isEmpty())

            // Two pages back.
            model.reveal("m005", NOW - 195 * minute)
            settle()
            assertEquals(listOf("m119", "m005"), revealed)
            assertEquals(listOf(1, 2), asked.map { it.pagesLoaded })
            assertTrue(asked.first().hasOlder)

            // Not in this chat at all: the pill says to look further back, in the phone's language.
            model.reveal("nowhere", NOW - 900 * minute)
            settle()
            assertEquals(2, revealed.size)
            assertTrue(effects.last() is ConversationEffect.Toast)
        }
}
