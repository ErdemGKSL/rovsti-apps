package dev.rovsti.platform.sms

import dev.rovsti.core.model.messages.DeliveryState
import dev.rovsti.core.model.messages.MmsRow
import dev.rovsti.core.model.messages.SmsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsRulesTest {
    private fun sms(
        id: Long,
        date: Long,
    ) = SmsRow(id, 1, "a", "b", date, 1, true, null, DeliveryState.None)

    private fun mms(
        id: Long,
        date: Long,
    ) = MmsRow(id, 1, emptyList(), null, date, false, true, emptyList(), null)

    @Test fun deliveryFollowsBoxAndStatus() {
        assertEquals(DeliveryState.Failed, SmsRules.delivery(5, 0))
        assertEquals(DeliveryState.Failed, SmsRules.delivery(2, 64))
        assertEquals(DeliveryState.Delivered, SmsRules.delivery(2, 0))
        assertEquals(DeliveryState.Pending, SmsRules.delivery(4, -1))
        assertEquals(DeliveryState.Pending, SmsRules.delivery(2, 32))
        assertEquals(DeliveryState.Sent, SmsRules.delivery(2, -1))
        assertEquals(DeliveryState.None, SmsRules.delivery(1, 0))
    }

    @Test fun pageKeepsTheNewestAndSaysWhetherMoreExist() {
        val page = SmsRules.page(listOf(sms(1, 50), sms(2, 10)), listOf(mms(3, 30)), 2)
        assertEquals(listOf(1L), page.sms.map { it.id })
        assertEquals(listOf(3L), page.mms.map { it.id })
        assertTrue(page.hasOlder)
        assertFalse(SmsRules.page(listOf(sms(1, 50)), emptyList(), 2).hasOlder)
    }

    @Test fun recipientIdsSplitOnSpaces() {
        assertEquals(listOf("1", "22"), SmsRules.splitIds(" 1  22 "))
        assertEquals(emptyList<String>(), SmsRules.splitIds(null))
    }

    @Test fun extensionsOfAttachments() {
        assertEquals("jpg", SmsRules.extension("IMAGE/JPEG"))
        assertEquals("mp4", SmsRules.extension("video/mp4"))
        assertEquals("bin", SmsRules.extension("application/pdf"))
    }
}
