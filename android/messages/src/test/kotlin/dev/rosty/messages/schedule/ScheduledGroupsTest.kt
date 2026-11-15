package dev.rosty.messages.schedule

import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.wa.ScheduledState
import dev.rosty.core.model.wa.ScheduledText
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledGroupsTest {
    private fun sms(
        id: Long,
        to: String,
        at: Long,
    ) = ScheduledSms(id, at, ScheduleTarget.Sms(to, null), "text $id", 0L)

    private fun wa(
        id: String,
        chat: String,
        at: Long,
        state: ScheduledState = ScheduledState.Pending,
    ) = ScheduledText(id, chat, "wa $id", at, 0L, state)

    @Test fun oneGroupPerConversationTheSoonestFirst() {
        val local =
            listOf(sms(1, "+90 555 000 00 01", 300), sms(2, "05550000001", 100), sms(3, "+90 555 000 00 02", 200))
        val remote = listOf(wa("a", "ada@s.whatsapp.net", 50))
        val groups = scheduledGroups(local, remote)
        assertEquals(3, groups.size)
        assertEquals(ScheduleDest.WhatsApp("ada@s.whatsapp.net"), groups[0].dest)
        // The same number written two ways is one conversation.
        assertEquals(listOf(100L, 300L), groups[1].items.map { it.atMs })
        assertEquals(1, groups[2].items.size)
    }

    @Test fun sentGatewayItemsAreHistoryNotCounted() {
        val remote =
            listOf(wa("a", "ada@s.whatsapp.net", 50, ScheduledState.Sent("m1")), wa("b", "ada@s.whatsapp.net", 70))
        assertEquals(1, scheduledCount(ScheduleState(emptyList(), remote)))
    }
}
