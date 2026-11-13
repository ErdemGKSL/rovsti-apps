package dev.rovsti.contacts.data

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.WaMarksAnswer
import dev.rovsti.core.model.contacts.WaMarksRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.search.WireNumber
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.ops.NativeContactsWaOps
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaLinkState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaMarksTest {
    private val ayse = WireContact(1, "Ayşe", listOf(WireNumber("+90 532 123 45 67")))
    private val cem = WireContact(2, "Cem", listOf(WireNumber("+90 216 555 03 04")))
    private val ready = Book.Ready(persistentListOf(ayse, cem), persistentListOf())
    private val connected = WaLinkState.Connected(InstanceStatus("inst", InstanceState.Connected))

    private val requests = mutableListOf<WaMarksRequest>()
    private val core =
        FakeRustCore().also {
            // The real matching is Rust's (contacts.wa_marks fixtures); the fake marks every contact whose digits end
            // a chat user.
            it.onTyped("contacts.wa_marks", WaMarksRequest.serializer(), WaMarksAnswer.serializer()) { req ->
                requests += req
                Outcome.Ok(
                    WaMarksAnswer(
                        req.contacts
                            .mapNotNull { c ->
                                val digits =
                                    c.numbers
                                        .first()
                                        .raw
                                        .filter(Char::isDigit)
                                req.chatUsers.firstOrNull { it == digits }?.let { c.id to "$it@s.whatsapp.net" }
                            }.toMap(),
                    ),
                )
            }
        }

    private fun chat(
        jid: String,
        group: Boolean = false,
    ) = RichChat(jid = jid, unread = 0, isGroup = group)

    @Test fun notLinkedMeansNoMarksAndNoRequest() =
        runTest(UnconfinedTestDispatcher()) {
            val wa = FakeWaRepository(WaLinkState.None).apply { setChats(listOf(chat("905321234567@s.whatsapp.net"))) }
            val marks = WaMarks(wa, MutableStateFlow(ready), NativeContactsWaOps(core), backgroundScope)
            assertEquals(emptyMap<Long, String>(), marks.marks.value)
            assertEquals("the address book is not sent when nothing is linked", 0, requests.size)
        }

    @Test fun chatsAndBookGiveTheMarks() =
        runTest(UnconfinedTestDispatcher()) {
            val wa =
                FakeWaRepository(connected).apply {
                    setChats(
                        listOf(
                            chat("905321234567@s.whatsapp.net"),
                            chat("120363@g.us", group = true),
                            chat("status@broadcast"),
                        ),
                    )
                }
            val marks = WaMarks(wa, MutableStateFlow(ready), NativeContactsWaOps(core), backgroundScope)
            assertEquals(mapOf(1L to "905321234567@s.whatsapp.net"), marks.marks.value)
            assertEquals("only 1:1 chats are sent", listOf("905321234567"), requests.single().chatUsers)
        }

    @Test fun marksFollowTheChatsOnResyncAndTheLinkWhenItIsLost() =
        runTest(UnconfinedTestDispatcher()) {
            val wa = FakeWaRepository(connected)
            val marks = WaMarks(wa, MutableStateFlow(ready), NativeContactsWaOps(core), backgroundScope)
            assertEquals(emptyMap<Long, String>(), marks.marks.value)

            wa.setChats(listOf(chat("905321234567@s.whatsapp.net")))
            assertEquals(setOf(1L), marks.marks.value.keys)

            wa.setChats(listOf(chat("902165550304@s.whatsapp.net")))
            assertEquals(setOf(2L), marks.marks.value.keys)

            wa.setState(WaLinkState.LoggedOut)
            assertEquals(emptyMap<Long, String>(), marks.marks.value)
        }

    @Test fun anUnreadBookGivesNoMarks() =
        runTest(UnconfinedTestDispatcher()) {
            val wa = FakeWaRepository(connected).apply { setChats(listOf(chat("905321234567@s.whatsapp.net"))) }
            val book = MutableStateFlow<Book>(Book.Loading)
            val marks = WaMarks(wa, book, NativeContactsWaOps(core), backgroundScope)
            assertEquals(emptyMap<Long, String>(), marks.marks.value)
            book.value = ready
            assertEquals(setOf(1L), marks.marks.value.keys)
        }

    @Test fun theHomeCodeOfTheBookGoesWithTheRequest() =
        runTest(UnconfinedTestDispatcher()) {
            // F39: the core compares a contact saved as `0532 …` with the chat `90532…` in E.164 (`home_cc`).
            val wa = FakeWaRepository(connected).apply { setChats(listOf(chat("905321234567@s.whatsapp.net"))) }
            val asked = mutableListOf<List<WireContact>>()
            WaMarks(wa, MutableStateFlow(ready), NativeContactsWaOps(core), backgroundScope) { contacts ->
                asked += contacts
                "90"
            }
            assertEquals("90", requests.single().homeCc)
            assertEquals(listOf(ayse, cem), asked.single())
        }
}
