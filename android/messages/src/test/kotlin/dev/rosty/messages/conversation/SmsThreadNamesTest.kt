package dev.rosty.messages.conversation

import dev.rosty.core.ops.NativeChatOps
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.names.FakeLiveNames
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.spam.SpamCore
import dev.rosty.platform.sms.FakeSmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The name-order rule on the SMS thread header: contact, WhatsApp name, number. */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsThreadNamesTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val number = "+905550000001"
    private val other = "+905550000002"
    private val book = MutableStateFlow(emptyMap<String, String>())
    private val live = FakeLiveNames()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private object Words : SmsWords {
        override val placeholder = "Message"
        override val sending = "Sending…"
        override val notSent = "Not sent"
        override val now = "now"
        override val readOnly = "Read only"

        override fun counter(
            left: Int,
            parts: Int,
            unicode: Boolean,
        ) = "$left/$parts"

        override fun people(count: Int) = "$count people"

        override fun unread(count: Int) = "$count unread"

        override fun part(contentType: String) = contentType
    }

    private fun TestScope.model(to: List<String>): SmsThreadViewModel {
        val core = SpamCore()
        return SmsThreadViewModel(
            SmsThreadSources(
                sms = FakeSmsRepository(),
                ops = core.ops,
                chat = NativeChatOps(core.core),
                store = MessagesStore(core.ops, backgroundScope, io = dispatcher, debounceMs = 0),
                names = PersonNames({ book.value[it] }, book, live, readable = { "read:$it" }),
                roleHeld = MutableStateFlow(true),
                now = { 1_790_000_000_000L },
            ),
            threadId = 0,
            recipients = to,
            words = Words,
            days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday"),
            default = dispatcher,
        )
    }

    @Test fun anUnknownNumberOnWhatsAppIsNamedInPlaceAndASavedContactWins() =
        scope.runTest {
            val model = model(listOf(number))
            advanceUntilIdle()

            // Nobody names it: the number as people read it, nothing under it, and WhatsApp is asked.
            assertEquals("read:$number", model.state.value.title)
            assertNull(model.state.value.subtitle)
            assertEquals(setOf(number), live.asked.toSet())

            live.land(number, "Ada")
            advanceUntilIdle()
            assertEquals("Ada", model.state.value.title)
            assertEquals(number, model.state.value.subtitle)

            // Saved to the address book later: the contact beats the WhatsApp name.
            book.value = mapOf(number to "Ada Lovelace")
            advanceUntilIdle()
            assertEquals("Ada Lovelace", model.state.value.title)
        }

    @Test fun everyMemberOfAGroupThreadIsNamedByTheRule() =
        scope.runTest {
            book.value = mapOf(number to "Ada Lovelace")
            live.land(other, "Mert K.")
            val model = model(listOf(number, other))
            advanceUntilIdle()

            assertEquals("Ada Lovelace, Mert K.", model.state.value.title)
            assertEquals("2 people", model.state.value.subtitle)
        }
}
