package dev.rosty.messages.remind

import dev.rosty.core.model.chat.ReminderSlot
import dev.rosty.messages.ChatsTab
import dev.rosty.messages.EXTRA_MESSAGE_AT
import dev.rosty.messages.EXTRA_MESSAGE_ID
import dev.rosty.messages.EXTRA_THREAD_TAG
import dev.rosty.messages.IntentFacts
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.messagesRouteFor
import dev.rosty.messages.of
import dev.rosty.messages.starred.MarkCore
import dev.rosty.messages.starred.MarkKit
import dev.rosty.messages.starred.MarkTarget
import dev.rosty.platform.notify.MessageNotifications
import dev.rosty.platform.notify.MessageReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E6.13, M33: the slots and the payload are Rust's, the alarm `:platform`'s; the tap opens the message. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemindCenterTest {
    private var now = 1_790_000_000_000L

    private fun TestScope.kit(): MarkKit {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return MarkKit(CoroutineScope(backgroundScope.coroutineContext + dispatcher), dispatcher, { now })
            .also { advanceUntilIdle() }
    }

    private val target = MarkTarget("sms:7", "sms:12", 1_789_999_000_000L, "Mert Kaya", "Altıda garda", "Mert Kaya")

    @Test fun theStoredAlarmsAreSetAgainAtStart() =
        runTest {
            assertEquals(1, kit().repo.rearmed)
        }

    @Test fun theFourSlotsComeFromRustWithTheirTimes() =
        runTest {
            val slots = kit().reminders.slots()
            assertEquals(ReminderSlot.entries, slots.map { it.key })
            assertEquals(now + 3_600_000, slots[1].dueMs)
        }

    @Test fun armingStoresRustsPayloadWithTheTitleInThePhonesLanguage() =
        runTest {
            val kit = kit()
            val armed = kit.reminders.arm(target, ReminderSlot.In1Hour) { "Hatırlatma: $it" }
            advanceUntilIdle()
            assertNotNull(armed)
            val item = kit.repo.now.single()
            assertEquals("sms:7", item.tag)
            assertEquals("sms:12", item.messageId)
            assertEquals(target.atMs, item.messageAtMs)
            assertEquals(MarkCore.due(ReminderSlot.In1Hour, now), item.atMs)
            assertEquals("Hatırlatma: Mert Kaya", item.title)
            assertEquals("Altıda garda", item.body)
            // The bubble's badge follows the armed list.
            assertEquals(mapOf("sms:12" to item.atMs), kit.reminders.badges("sms:7").first())
            assertTrue(
                kit.reminders
                    .badges("sms:8")
                    .first()
                    .isEmpty(),
            )
            assertEquals(item.id, kit.reminders.of("sms:12")?.id)
        }

    @Test fun aSecondReminderOnTheSameMessageReplacesTheFirst() =
        runTest {
            val kit = kit()
            kit.reminders.arm(target, ReminderSlot.In15Min) { it }
            advanceUntilIdle()
            now += 1_000
            kit.reminders.arm(target, ReminderSlot.TomorrowMorning) { it }
            advanceUntilIdle()
            val item = kit.repo.now.single()
            assertEquals(MarkCore.due(ReminderSlot.TomorrowMorning, now), item.atMs)
        }

    @Test fun theRemindersOfAMergedWhatsAppChatFollowIt() =
        runTest {
            val kit = kit()
            val lid = "wa:777000000000001@lid"
            val number = "wa:905550000001@s.whatsapp.net"
            val onLid = MarkTarget(lid, "L1", 1_789_999_000_000L, "ada", "Altıda garda", "ada")
            kit.reminders.arm(onLid, ReminderSlot.In1Hour) { "Hatırlatma: $it" }
            now += 1_000
            kit.reminders.arm(target, ReminderSlot.Tonight) { it }
            advanceUntilIdle()
            val before = kit.repo.now.single { it.messageId == "L1" }

            kit.reminders.rekey(lid, number)
            advanceUntilIdle()

            // The same reminder (id, time, words), now of the chat that stays: its badge and its tap follow.
            val after = kit.repo.now.single { it.messageId == "L1" }
            assertEquals(number, after.tag)
            assertEquals(listOf(before.id, before.atMs, before.title), listOf(after.id, after.atMs, after.title))
            assertEquals(mapOf("L1" to after.atMs), kit.reminders.badges(number).first())
            assertTrue(
                kit.reminders
                    .badges(lid)
                    .first()
                    .isEmpty(),
            )
            // The SMS reminder is untouched, and following the step again changes nothing.
            assertEquals(
                "sms:7",
                kit.repo.now
                    .single { it.messageId == "sms:12" }
                    .tag,
            )
            kit.reminders.rekey(lid, number)
            advanceUntilIdle()
            assertEquals(2, kit.repo.now.size)
        }

    @Test fun cancelTakesItBack() =
        runTest {
            val kit = kit()
            val armed = kit.reminders.arm(target, ReminderSlot.Tonight) { it }!!
            advanceUntilIdle()
            assertTrue(kit.reminders.cancel(armed.id))
            advanceUntilIdle()
            assertTrue(kit.repo.now.isEmpty())
            assertNull(kit.reminders.of("sms:12"))
        }

    @Test fun theNotificationsTapOpensTheConversationOnTheMessage() {
        // The extras are `:platform`'s: its notification is what the tap comes from.
        assertEquals(MessageNotifications.EXTRA_TAG, EXTRA_THREAD_TAG)
        assertEquals(MessageReminders.EXTRA_MESSAGE_ID, EXTRA_MESSAGE_ID)
        assertEquals(MessageReminders.EXTRA_MESSAGE_AT, EXTRA_MESSAGE_AT)
        val intent =
            android.content
                .Intent()
                .putExtra(EXTRA_THREAD_TAG, "sms:7")
                .putExtra(EXTRA_MESSAGE_ID, "sms:12")
                .putExtra(EXTRA_MESSAGE_AT, 99L)
        assertEquals(
            ChatsTab to listOf(MessagesRoute.SmsThread(7, reveal = "sms:12", revealAtMs = 99)),
            messagesRouteFor(IntentFacts.of(intent)),
        )
        assertEquals(
            ChatsTab to
                listOf(MessagesRoute.WaThread("905550000002@s.whatsapp.net", reveal = "3EB0AA", revealAtMs = 5)),
            messagesRouteFor(
                IntentFacts(threadTag = "wa:905550000002@s.whatsapp.net", messageId = "3EB0AA", messageAtMs = 5),
            ),
        )
        // A chat reminder (from a notification, M41) has no message: the conversation just opens.
        assertEquals(
            ChatsTab to listOf(MessagesRoute.SmsThread(7)),
            messagesRouteFor(IntentFacts(threadTag = "sms:7", messageId = "", messageAtMs = 0)),
        )
    }
}
