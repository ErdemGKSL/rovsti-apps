package dev.rosty.messages.conversation

import dev.rosty.core.Outcome
import dev.rosty.core.model.chat.UnreadDividerAnswer
import dev.rosty.core.model.chat.UnreadDividerRequest
import dev.rosty.core.model.messages.DeliveryState
import dev.rosty.core.model.messages.EventResult
import dev.rosty.core.model.messages.SmsKey
import dev.rosty.core.model.messages.SmsRow
import dev.rosty.core.model.messages.TimelineAnswer
import dev.rosty.core.model.messages.TimelineItem
import dev.rosty.core.model.messages.TimelineRequest
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
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The unread divider of an SMS thread is placed once per opening (plan `thread-scroll.md`, step 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsThreadDividerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val number = "+905550000001"
    private val book = MutableStateFlow(emptyMap<String, String>())

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

    private fun row(
        id: Long,
        read: Boolean,
    ) = SmsRow(id, THREAD, number, "Message $id", NOW + id * 60_000L, INBOX, read, null, DeliveryState.None)

    private fun aboveDivider(state: ConversationUi): String? {
        val at = state.items.indexOfFirst { it is ConversationItem.Divider }
        return if (at > 0) state.items[at - 1].key else null
    }

    @Test fun theDividerStaysWhereItOpenedAndANewOpeningPlacesItAgain() =
        scope.runTest {
            val sms = FakeSmsRepository(listOf(row(1, read = true), row(2, read = false), row(3, read = false)))
            val core = SpamCore()
            // The core's timeline and divider as plain rules: provider rows oldest first; the divider above the last
            // `unread` incoming bubbles (`unread_jump.divider`).
            core.core.onTyped("sms_timeline.build", TimelineRequest.serializer(), TimelineAnswer.serializer()) { req ->
                val rows =
                    req.events
                        .flatMap { it.page?.sms.orEmpty() }
                        .distinctBy { it.id }
                        .sortedBy { it.dateMs }
                val items =
                    rows.map {
                        val key = SmsKey.Sms(it.id)
                        TimelineItem(
                            key,
                            it.box != INBOX,
                            it.body,
                            emptyList(),
                            it.dateMs,
                            it.delivery,
                            null,
                            "sms:${it.id}",
                        )
                    }
                val results = req.events.map { EventResult(true, null, null) }
                Outcome.Ok(TimelineAnswer(THREAD, items, false, rows.firstOrNull()?.dateMs, results))
            }
            core.core.onTyped(
                "unread_jump.divider",
                UnreadDividerRequest.serializer(),
                UnreadDividerAnswer.serializer(),
            ) { req ->
                var seen = 0
                val at =
                    req.incoming.indices.reversed().firstOrNull { i ->
                        if (req.incoming[i]) seen++
                        seen == req.unread
                    }
                Outcome.Ok(UnreadDividerAnswer(at, "", 0))
            }
            val model =
                SmsThreadViewModel(
                    SmsThreadSources(
                        sms = sms,
                        ops = core.ops,
                        chat = NativeChatOps(core.core),
                        store = MessagesStore(core.ops, backgroundScope, io = dispatcher, debounceMs = 0),
                        names = PersonNames({ book.value[it] }, book, FakeLiveNames()),
                        roleHeld = MutableStateFlow(true),
                        now = { NOW + DAY },
                    ),
                    threadId = THREAD,
                    recipients = listOf(number),
                    words = Words,
                    days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday"),
                    default = dispatcher,
                )
            assertEquals(1, model.opened())
            advanceUntilIdle()
            assertEquals(1, model.state.value.opens)
            assertEquals("sms:2", aboveDivider(model.state.value))

            // A message that arrives while the page is up does not move the divider.
            sms.insertRestored(listOf(row(4, read = false)))
            advanceUntilIdle()
            assertEquals(
                "sms:4",
                model.state.value.items
                    .first()
                    .key,
            )
            assertEquals("sms:2", aboveDivider(model.state.value))

            // Opened again on the kept view model: what is unread now (4 and 5) gets the divider.
            sms.insertRestored(listOf(row(5, read = false)))
            assertEquals(2, model.opened())
            advanceUntilIdle()
            assertEquals(2, model.state.value.opens)
            assertEquals("sms:4", aboveDivider(model.state.value))
            val divider =
                model.state.value.items
                    .first { it is ConversationItem.Divider } as ConversationItem.Divider
            assertEquals("2 unread", divider.label)
        }

    private companion object {
        const val THREAD = 7L
        const val INBOX = 1
        const val NOW = 1_790_000_000_000L
        const val DAY = 86_400_000L
    }
}
