package dev.rosty.messages.inbox

import dev.rosty.core.Outcome
import dev.rosty.core.model.chat.GlobalSearchAnswer
import dev.rosty.core.model.chat.GlobalSearchRequest
import dev.rosty.core.model.chat.SearchCandidate
import dev.rosty.core.model.chat.SearchHit
import dev.rosty.core.model.chat.SearchPrepareAnswer
import dev.rosty.core.model.chat.SearchPrepareRequest
import dev.rosty.core.model.chat.Utf16Span
import dev.rosty.core.model.messages.DeliveryState
import dev.rosty.core.model.messages.InboxRow
import dev.rosty.core.model.messages.InboxThread
import dev.rosty.core.model.messages.MergeAnswer
import dev.rosty.core.model.messages.MergeRequest
import dev.rosty.core.model.messages.Message
import dev.rosty.core.model.messages.MessageDirection
import dev.rosty.core.model.messages.PersonKey
import dev.rosty.core.model.messages.PhoneNumber
import dev.rosty.core.model.messages.SmsRow
import dev.rosty.core.model.messages.ThreadId
import dev.rosty.core.model.search.IndexAnswer
import dev.rosty.core.model.search.IndexNewRequest
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.ChatMeta
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaMessage
import dev.rosty.core.ops.ContactIndexHandle
import dev.rosty.core.ops.NativeChatOps
import dev.rosty.core.ops.NativeSearchOps
import dev.rosty.messages.data.Book
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.names.FakeLiveNames
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.spam.SpamCore
import dev.rosty.messages.wa.WaChats
import dev.rosty.platform.sms.FakeSmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The name-order rule on the inbox rows and the search results (contact, WhatsApp name, number). */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxNamesTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val now = 1_790_000_000_000L
    private val stranger = "+905550000001"
    private val mert = "905550000002@s.whatsapp.net"
    private val live = FakeLiveNames()
    private val time = RowTime(ZoneId.of("UTC"), Locale.US, true, "Yesterday")

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private class FixedWaChats(
        list: List<RichChat>,
        private val found: List<SearchCandidate> = emptyList(),
    ) : WaChats {
        override val chats: Flow<List<RichChat>> = MutableStateFlow(list)

        override suspend fun act(
            jid: String,
            kind: ChatActionKind,
        ) = false

        override suspend fun searchCandidates(jids: List<String>) =
            found.filter {
                it.chatKey.removePrefix("wa:") in
                    jids
            }
    }

    /** Rust's `inbox.merge` in miniature for strangers: one row per number, titled by its thread or the number. */
    private fun merge(req: MergeRequest): List<InboxRow> {
        val sms =
            req.sms.map { row ->
                val id = ThreadId.Sms(row.threadId)
                val last = Message(id, MessageDirection.Incoming, null, row.snippet, row.dateMs, row.unread == 0)
                row.addresses.single() to InboxThread(id, last, row.unread, null, false)
            }
        val wa = req.wa.map { "+" + (it.id as ThreadId.WhatsApp).jid.substringBefore('@') to it }
        return (sms + wa).map { (number, thread) ->
            InboxRow(
                PersonKey.Number(PhoneNumber(number)),
                thread.title ?: number,
                listOf(thread),
                thread.last,
                thread.unread,
                listOf(thread.id.channel),
            )
        }
    }

    private suspend fun TestScope.model(
        core: SpamCore,
        wa: WaChats,
        book: MutableStateFlow<Book>,
    ): InboxViewModel {
        core.core.onTyped("search.index_new", IndexNewRequest.serializer(), IndexAnswer.serializer()) {
            Outcome.Ok(IndexAnswer(1, 0))
        }
        core.core.onTyped("inbox.merge", MergeRequest.serializer(), MergeAnswer.serializer()) { req ->
            Outcome.Ok(MergeAnswer(merge(req)))
        }
        core.core.onTyped(
            "global_search.prepare",
            SearchPrepareRequest.serializer(),
            SearchPrepareAnswer.serializer(),
        ) {
            Outcome.Ok(SearchPrepareAnswer(true, 2, "%${it.query}%", it.chats.map { c -> c.key }, 3))
        }
        core.core.onTyped("global_search.search", GlobalSearchRequest.serializer(), GlobalSearchAnswer.serializer()) {
            val hits =
                it.items.map { c ->
                    SearchHit(c.chatKey, c.chatTitle, c.messageId, c.atMs, c.text, Utf16Span(0, 1))
                }
            Outcome.Ok(GlobalSearchAnswer(hits, ""))
        }
        val search = NativeSearchOps(core.core)
        val index = (ContactIndexHandle.open(emptyList(), ops = search, release = {}) as Outcome.Ok).value
        book.value = Book(index, emptyMap(), 1)
        val sms =
            FakeSmsRepository(
                listOf(SmsRow(1, 7, stranger, "Dinner at eight", now, 1, false, null, DeliveryState.Sent)),
            )
        val chat = NativeChatOps(core.core)
        val names = PersonNames({ null }, book, live, readable = { "read:$it" })
        return InboxViewModel(
            InboxSources(
                sms = sms,
                smsReadable = MutableStateFlow(true),
                roleHeld = MutableStateFlow(true),
                wa = wa,
                book = book,
                store = MessagesStore(core.ops, backgroundScope, io = dispatcher, debounceMs = 0),
                ops = core.ops,
                chat = chat,
                now = { now },
                names = names,
            ),
            InboxBuilder(EnglishInboxWords, time),
            EnglishInboxWords,
            GlobalSearch(chat, sms, wa, time) { now },
            default = dispatcher,
        )
    }

    private fun InboxViewModel.titles() = state.value.rows.map { it.title }

    @Test fun anSmsFromAnUnknownNumberTakesItsWhatsAppNameWhenItArrives() =
        scope.runTest {
            val model = model(SpamCore(), FixedWaChats(emptyList()), MutableStateFlow(Book.Empty))
            advanceUntilIdle()

            // Nobody names the number: it shows at once, as people read it, and WhatsApp is asked.
            assertEquals(listOf("read:$stranger"), model.titles())
            assertTrue(stranger in live.asked)

            live.land(stranger, "Ada")
            advanceUntilIdle()
            // The name swaps in: no restart, no refresh.
            assertEquals(listOf("Ada"), model.titles())
        }

    @Test fun aWhatsAppChatOfAStrangerShowsThePushNameBeforeTheStoredChatName() =
        scope.runTest {
            val last = WaMessage("m1", mert, mert, false, now - 5, MessageBody.Text("On my way"), emptyList())
            val chat = RichChat(mert, "mert.k", last, 0, false, ChatMeta(pushName = "Mert K."))
            val model = model(SpamCore(), FixedWaChats(listOf(chat)), MutableStateFlow(Book.Empty))
            advanceUntilIdle()

            assertEquals(listOf("read:$stranger", "Mert K."), model.titles())
            // A stored WhatsApp name is a name: only the nameless number is asked.
            assertEquals(setOf(stranger), live.asked.toSet())
        }

    @Test fun aSearchResultIsCalledWhatItsInboxRowIsCalled() =
        scope.runTest {
            val last = WaMessage("m1", mert, mert, false, now - 5, MessageBody.Text("Dinner?"), emptyList())
            val chat = RichChat(mert, "mert.k", last, 0, false, ChatMeta(pushName = "Mert K."))
            // The store's candidate still carries the stored chat name; the row's name wins.
            val found = listOf(SearchCandidate("wa:$mert", "mert.k", "m1", null, "Dinner?", now - 5))
            live.land(stranger, "Ada")
            val model = model(SpamCore(), FixedWaChats(listOf(chat), found), MutableStateFlow(Book.Empty))
            advanceUntilIdle()

            model.onAction(InboxAction.SetQuery("Dinner"))
            advanceUntilIdle()

            val hits =
                model.state.value.messages
                    ?.hits
                    .orEmpty()
            assertEquals(setOf("Ada", "Mert K."), hits.map { it.title }.toSet())
        }
}
