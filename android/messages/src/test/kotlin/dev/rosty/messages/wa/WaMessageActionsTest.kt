package dev.rosty.messages.wa

import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.Card
import dev.rosty.core.model.wa.CardNumber
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.FoundUrl
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.HeaderPresence
import dev.rosty.core.model.wa.HeaderPresenceKind
import dev.rosty.core.model.wa.LinkMeta
import dev.rosty.core.model.wa.LinkPreview
import dev.rosty.core.model.wa.MY_VOTE
import dev.rosty.core.model.wa.MentionEncoded
import dev.rosty.core.model.wa.MentionPick
import dev.rosty.core.model.wa.MentionQuery
import dev.rosty.core.model.wa.MessageReceipts
import dev.rosty.core.model.wa.Participant
import dev.rosty.core.model.wa.PlaceView
import dev.rosty.core.model.wa.PlaceViewRequest
import dev.rosty.core.model.wa.Poll
import dev.rosty.core.model.wa.PollOptionView
import dev.rosty.core.model.wa.PollView
import dev.rosty.core.model.wa.PresenceViewRequest
import dev.rosty.core.model.wa.ReceiptMember
import dev.rosty.core.model.wa.ReceiptRow
import dev.rosty.core.model.wa.ReceiptSection
import dev.rosty.core.model.wa.SectionRows
import dev.rosty.core.model.wa.SystemNote
import dev.rosty.core.model.wa.TypingKind
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaMentionSpan
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.TextMark
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.names.FakeLiveNames
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.web.FetchedPage
import dev.rosty.platform.web.PagePreviewFetcher
import dev.rosty.wa.presence.WaPresenceWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The rules of `rosty_core::wa_actions` as the JVM tests need them (the real ones are Rust's, tested there): a query
 * is the word after an `@` before the caret, a picked `@Name` becomes `@<user>`, the first `http(s)://` word is a link.
 */
class FakeWaActionRules(
    private val meta: LinkMeta? = null,
) : WaActionRules {
    override suspend fun mentionQuery(
        text: String,
        caret: Int,
    ): MentionQuery? {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val at = before.lastIndexOf('@').takeIf { it >= 0 } ?: return null
        if (at > 0 && !before[at - 1].isWhitespace()) return null
        val query = before.substring(at + 1)
        return if (query.any { it.isWhitespace() }) null else MentionQuery(at, query)
    }

    override suspend fun encode(
        text: String,
        picks: List<MentionPick>,
    ): MentionEncoded {
        val out = StringBuilder()
        var cursor = 0
        for (pick in picks.sortedBy { it.start }) {
            out.append(text, cursor, pick.start).append('@').append(pick.jid.substringBefore('@'))
            cursor = pick.end
        }
        out.append(text, cursor, text.length)
        return MentionEncoded(out.toString(), picks.map { it.jid }.distinct())
    }

    override suspend fun firstLink(text: String): FoundUrl? {
        val match = Regex("https?://\\S+").find(text) ?: return null
        return FoundUrl(match.value, match.range.first, match.range.last + 1)
    }

    override suspend fun sections(
        members: List<ReceiptMember>,
        voice: Boolean,
    ): List<SectionRows> {
        fun section(
            kind: ReceiptSection,
            at: (ReceiptMember) -> Long?,
            pick: (ReceiptMember) -> Boolean,
        ) = members
            .filter(pick)
            .map { ReceiptRow(it.jid, at(it), it.left) }
            .takeIf { it.isNotEmpty() }
            ?.let { SectionRows(kind, it) }
        return listOfNotNull(
            section(ReceiptSection.Read, { it.readMs }) { it.readMs != null },
            section(ReceiptSection.Delivered, { it.deliveredMs }) { it.readMs == null && it.deliveredMs != null },
            section(ReceiptSection.Waiting, { null }) { it.readMs == null && it.deliveredMs == null },
        )
    }

    override suspend fun parse(
        html: String,
        url: String,
    ): LinkMeta? = meta

    // ---- E6-WA R5: Kotlin copies of `rosty_core::wa_rich` for the cases the JVM tests use.

    override suspend fun pollView(
        poll: Poll,
        own: List<String>,
    ): PollView {
        fun isMe(jid: String) = jid == MY_VOTE || own.any { WaIds.same(it, jid) }
        val voters = poll.options.flatMap { it.voters }.distinct()
        val top = poll.options.maxOfOrNull { it.voters.size } ?: 0
        return PollView(
            question = poll.question,
            multiple = poll.multiple,
            voters = voters.size,
            options =
                poll.options.map { option ->
                    PollOptionView(
                        name = option.name,
                        count = option.voters.size,
                        percent =
                            if (voters.isEmpty()) {
                                0
                            } else {
                                (option.voters.size * 100 + voters.size / 2) /
                                    voters.size
                            },
                        mine = option.name in poll.myVotes || option.voters.any(::isMe),
                        leading = top > 0 && option.voters.size == top,
                        voters = option.voters,
                    )
                },
            voted = poll.myVotes.isNotEmpty() || poll.options.any { o -> o.voters.any(::isMe) },
        )
    }

    override suspend fun placeView(req: PlaceViewRequest): PlaceView {
        val coords = "%.5f, %.5f".format(Locale.ROOT, req.latitude, req.longitude)
        return PlaceView(
            title = req.name?.takeIf { it.isNotBlank() },
            subtitle = req.address?.takeIf { it.isNotBlank() },
            coords = coords,
            geoUri = "geo:${req.latitude},${req.longitude}",
            live = req.live,
        )
    }

    /** `FN:` names the card; each `TEL` line is a number, its `waid=` its WhatsApp id. */
    override suspend fun cards(vcard: String): List<Card> =
        vcard.split("END:VCARD").filter { it.contains("BEGIN:VCARD") }.map { card ->
            val lines = card.lines().map { it.trim() }
            Card(
                name = lines.firstOrNull { it.startsWith("FN:") }?.removePrefix("FN:"),
                numbers =
                    lines.filter { it.startsWith("TEL") }.map { line ->
                        val waid = Regex("waid=(\\d+)").find(line)?.groupValues?.get(1)
                        CardNumber(line.substringAfterLast(':'), waJid = waid?.let { "$it@s.whatsapp.net" })
                    },
            )
        }

    override suspend fun presenceView(req: PresenceViewRequest): Outcome<HeaderPresence> {
        val live = req.typing.filter { req.nowMs - it.atMs < WaPresenceWatch.TYPING_TTL_MS }
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

/** A page fetcher that answers one page and counts what it was asked. */
class FakePageFetcher(
    private val html: String? = "<title>Example</title>",
) : PagePreviewFetcher {
    val pages = mutableListOf<String>()

    override suspend fun page(
        url: String,
        maxBytes: Int,
    ): FetchedPage? {
        pages += url
        return html?.let { FetchedPage(url, it) }
    }

    override suspend fun image(
        url: String,
        maxBytes: Int,
    ): ByteArray? = null
}

/** E6-WA R4: edit, forward, delete for me, info, mentions, link cards and the disappearing timer in a thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaMessageActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")
    private val book = MutableStateFlow(mapOf("+905550000001" to "Ada Lovelace", "+100000000011" to "Anna"))
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
        links: WaLinkPreviews? = null,
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
                links = links,
            ),
            jid,
            EnglishWaWords,
            EnglishInboxWords,
            days,
        ).also(made::add)
    }

    private fun bubbles(state: ConversationUi): List<BubbleUi> =
        state.items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }

    private fun queued(kit: WaTestKit): List<Pair<String, WaSyncAction>> =
        kit.ops.writes
            .filterIsInstance<WaSyncEnqueueRequest>()
            .map { it.chat to it.action }

    private fun mine(
        id: String,
        text: String,
        atMs: Long = NOW - minute,
        editableUntil: Long? = null,
    ): WaSyncMessage = WaTestKit.text(ADA, id, atMs, text, fromMe = true).copy(editableUntilMs = editableUntil)

    // ---- edit

    @Test fun onlyMyMessageInsideTheWindowIsEditedAndSendSavesTheNewText() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.remote.down = true
            kit.chat(ADA)
            kit.message(mine("m1", "See you at 5", editableUntil = NOW + 10 * minute))
            kit.message(mine("m2", "Old one", atMs = NOW - 30 * minute, editableUntil = NOW - minute))
            kit.message(WaTestKit.text(ADA, "m3", NOW - minute, "Theirs"))
            val model = model(kit)
            settle()

            assertTrue(model.canEdit("m1"))
            assertFalse("the window is over", model.canEdit("m2"))
            assertFalse("not mine", model.canEdit("m3"))

            model.startEdit("m1")
            settle()
            assertEquals(
                "m1",
                model.state.value.editing
                    ?.key,
            )
            assertEquals(
                "See you at 5",
                model.state.value.editing
                    ?.text,
            )

            model.send("See you at 6")
            settle()
            assertNull(model.state.value.editing)
            assertEquals(ADA to WaSyncAction.Edit("m1", "See you at 6"), queued(kit).single())
        }

    @Test fun cancellingAnEditSendsNothing() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(mine("m1", "Hello", editableUntil = NOW + minute))
            val model = model(kit)
            settle()

            model.startEdit("m1")
            settle()
            model.cancelEdit()
            settle()
            assertNull(model.state.value.editing)
            assertTrue(queued(kit).isEmpty())
        }

    // ---- forward

    @Test fun forwardQueuesARealForwardInTheTargetChat() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.remote.down = true
            kit.chat(ADA)
            kit.chat(MERT)
            kit.message(WaTestKit.text(ADA, "m1", NOW - minute, "Look at this"))
            kit.message(
                WaTestKit.text(ADA, "t1", NOW - 2 * minute, "").copy(system = SystemNote(SystemNote.DISAPPEARING, 0)),
            )
            val model = model(kit)
            settle()

            assertTrue(model.canForward("m1"))
            assertFalse("a system line is not a message", model.canForward("t1"))
            assertEquals("Look at this", model.forwardText("m1"))
            assertTrue(model.forwardTo("m1", MERT))
            settle()
            val (chat, action) = queued(kit).single()
            assertEquals(MERT, chat)
            action as WaSyncAction.Forward
            assertEquals(ADA, action.sourceChat)
            assertEquals("m1", action.messageId)
        }

    @Test fun aMessageForwardedManyTimesSaysSo() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(
                WaTestKit
                    .text(ADA, "m1", NOW - minute, "Chain letter")
                    .copy(forwarded = true, forwardingScore = 7, forwardedMany = true),
            )
            val model = model(kit)
            settle()

            val bubble = bubbles(model.state.value).single()
            assertTrue(bubble.forwarded)
            assertTrue(bubble.forwardedMany)
        }

    // ---- delete for me

    @Test fun deleteForMeHidesTheMessageAtOnceAndQueuesIt() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.remote.down = true
            kit.chat(ADA)
            kit.message(WaTestKit.text(ADA, "m1", NOW - 2 * minute, "Keep"))
            kit.message(WaTestKit.text(ADA, "m2", NOW - minute, "Hide"))
            val model = model(kit)
            settle()

            model.deleteForMe("m2")
            settle()
            assertEquals(ADA to WaSyncAction.DeleteForMe("m2"), queued(kit).single())
            assertEquals(listOf("m1"), bubbles(model.state.value).map { it.key })
        }

    // ---- message info

    @Test fun infoGroupsTheMembersByWhatTheyDidAndMarksThoseWhoLeft() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(GROUP, name = "Weekend hike", group = true)
            kit.message(WaTestKit.text(GROUP, "g1", NOW - minute, "Who brings water?", fromMe = true))
            kit.remote.receipts["g1"] =
                MessageReceipts(
                    GROUP,
                    "g1",
                    listOf(
                        ReceiptMember(ANNA, name = "Anna", deliveredMs = NOW - 50_000, readMs = NOW - 40_000),
                        ReceiptMember(MERT, deliveredMs = NOW - 50_000, left = true),
                        ReceiptMember(LID_ONLY, name = "Lale"),
                    ),
                )
            val model = model(kit, GROUP)
            settle()

            assertTrue(model.canInfo("g1"))
            val (info, problem) = model.info("g1")
            assertNull(problem)
            val sections = info!!.sections
            assertEquals(
                listOf(ReceiptSection.Read, ReceiptSection.Delivered, ReceiptSection.Waiting),
                sections.map { it.section },
            )
            assertEquals("Anna", sections[0].rows.single().name)
            assertEquals(days.clock(NOW - 40_000), sections[0].rows.single().time)
            assertTrue("a member who left is marked", sections[1].rows.single().left)
            assertNull(sections[2].rows.single().time)
        }

    @Test fun infoFromAnOlderGatewaySaysItIsUnavailable() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(mine("m1", "Hi"))
            val model = model(kit)
            settle()

            assertEquals(null to WaInfoProblem.OldGateway, model.info("m1"))
        }

    // ---- mentions

    @Test fun anAtSignInAGroupListsTheMembersAndAPickGoesOutAsAMention() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(GROUP, name = "Weekend hike", group = true)
            kit.remote.groups[GROUP] =
                GroupInfo(
                    GROUP,
                    "Weekend hike",
                    listOf(
                        Participant(ANNA, admin = false, name = "Anna", pn = ANNA),
                        Participant(LID_ONLY, admin = false, name = "Lale"),
                    ),
                )
            val model = model(kit, GROUP)
            settle()

            model.onComposer("hi @la", 6)
            settle()
            val options = model.state.value.mentionOptions
            assertEquals(listOf(LID_ONLY), options.map { it.jid })

            val (text, caret) = model.pickMention("hi @la", 6, options.single())!!
            assertEquals("hi @Lale ", text)
            assertEquals(text.length, caret)
            settle()
            assertTrue(
                model.state.value.mentionOptions
                    .isEmpty(),
            )

            model.onComposer("hi @Lale see you", 16)
            settle()
            model.send("hi @Lale see you")
            settle()
            val action = queued(kit).single().second as WaSyncAction.SendText
            assertEquals("hi @123456789012345 see you", action.text)
            assertEquals(listOf(LID_ONLY), action.mentions)
        }

    @Test fun aMentionNameEditedAwayIsNoLongerAMention() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(GROUP, group = true)
            kit.remote.groups[GROUP] =
                GroupInfo(GROUP, "Hike", listOf(Participant(ANNA, admin = false, name = "Anna", pn = ANNA)))
            val model = model(kit, GROUP)
            settle()

            model.onComposer("@", 1)
            settle()
            val (text, _) =
                model.pickMention(
                    "@",
                    1,
                    model.state.value.mentionOptions
                        .single(),
                )!!
            assertEquals("@Anna ", text)
            model.onComposer("@An ", 3)
            settle()
            model.send("@An ")
            settle()
            val action = queued(kit).single().second as WaSyncAction.SendText
            assertEquals("@An", action.text)
            assertTrue(action.mentions.isEmpty())
        }

    @Test fun aReceivedMentionReadsAsANameAndIsMarked() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(GROUP, group = true)
            kit.message(
                WaTestKit
                    .text(GROUP, "g1", NOW - minute, "hey @100000000011 look", sender = MERT)
                    .copy(mentionSpans = listOf(WaMentionSpan(4, 17, ANNA))),
            )
            val model = model(kit, GROUP)
            settle()

            val bubble = bubbles(model.state.value).single()
            assertEquals("hey @Anna look", bubble.text)
            assertEquals(listOf(TextMark(4, 9)), bubble.marks)
        }

    // ---- link cards

    @Test fun aLinkGetsItsCardAfterTheTypingStopsAndItGoesWithTheText() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.remote.down = true
            kit.chat(ADA)
            val fetcher = FakePageFetcher()
            val rules = FakeWaActionRules(LinkMeta(title = "Example Domain", description = "For examples"))
            val links = WaLinkPreviews(fetcher, rules, LinkPreviewSetting.InMemory(), thumbnail = { null })
            val model = model(kit, rules = rules, links = links)
            settle()

            val text = "see https://example.org/a"
            model.onComposer(text, text.length)
            settle()
            val chip = model.state.value.linkChip
            assertNotNull(chip)
            assertEquals("Example Domain", chip!!.title)
            assertEquals("example.org", chip.host)
            assertFalse(chip.loading)
            assertEquals(listOf("https://example.org/a"), fetcher.pages)

            model.send(text)
            settle()
            val action = queued(kit).single().second as WaSyncAction.SendText
            assertEquals("https://example.org/a", action.linkPreview?.url)
            assertEquals("Example Domain", action.linkPreview?.title)
            assertNull(model.state.value.linkChip)
        }

    @Test fun theXOfTheChipSendsTheLinkWithoutACard() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.remote.down = true
            kit.chat(ADA)
            val rules = FakeWaActionRules(LinkMeta(title = "Example"))
            val links = WaLinkPreviews(FakePageFetcher(), rules, LinkPreviewSetting.InMemory(), thumbnail = { null })
            val model = model(kit, rules = rules, links = links)
            settle()

            val text = "https://example.org"
            model.onComposer(text, text.length)
            settle()
            model.removeLink()
            settle()
            assertNull(model.state.value.linkChip)
            // Typing on does not bring it back.
            model.onComposer("$text !", text.length + 2)
            settle()
            assertNull(model.state.value.linkChip)

            model.send("$text !")
            settle()
            assertNull((queued(kit).single().second as WaSyncAction.SendText).linkPreview)
        }

    @Test fun withLinkPreviewsOffNothingIsFetched() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            val fetcher = FakePageFetcher()
            val rules = FakeWaActionRules(LinkMeta(title = "Example"))
            val links = WaLinkPreviews(fetcher, rules, LinkPreviewSetting.InMemory(initial = false))
            val model = model(kit, rules = rules, links = links)
            settle()

            model.onComposer("https://example.org", 19)
            settle()
            assertNull(model.state.value.linkChip)
            assertTrue(fetcher.pages.isEmpty())
        }

    @Test fun aReceivedLinkCardIsShownUnderTheText() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(
                WaTestKit
                    .text(ADA, "m1", NOW - minute, "https://www.example.org/x")
                    .copy(linkPreview = LinkPreview("https://www.example.org/x", "Example", "For examples")),
            )
            val model = model(kit)
            settle()

            val card = bubbles(model.state.value).single().link
            assertEquals("Example", card?.title)
            assertEquals("For examples", card?.description)
            assertEquals("example.org", card?.host)
        }

    // ---- disappearing messages

    @Test fun theTimerIsSetThroughTheGatewayAndItsLineIsANotice() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA)
            kit.message(
                WaTestKit
                    .text(ADA, "t1", NOW - minute, "")
                    .copy(system = SystemNote(SystemNote.DISAPPEARING, WaWords.WEEK_S, ADA)),
            )
            val model = model(kit)
            settle()

            val line = bubbles(model.state.value).single()
            assertTrue(line.notice)
            assertEquals("Ada Lovelace turned on disappearing messages: 7 Days", line.text)
            assertFalse(model.canEdit("t1"))
            assertFalse(model.canInfo("t1"))

            model.setTimer(WaWords.DAY_S)
            settle()
            assertEquals(listOf(ADA to ChatActionKind.Disappearing(WaWords.DAY_S)), kit.remote.chatActions)
        }

    private companion object {
        const val ANNA = "100000000011@s.whatsapp.net"
        const val LID_ONLY = "123456789012345@lid"
    }
}
