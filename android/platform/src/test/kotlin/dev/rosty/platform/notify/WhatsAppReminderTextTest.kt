package dev.rosty.platform.notify

import dev.rosty.platform.PlatformPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `plans/wa-vault.md` 13.1, round 6: no WhatsApp message text stays in the alarm payloads of `rosty_message_reminders`.
 * A reminder of an earlier build loses its text once; SMS reminders keep theirs; a new "Remind me" on a WhatsApp
 * notification never sends the text to Rust.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsAppReminderTextTest : RemindersTestBase() {
    private fun reminder(
        id: Long,
        tag: String,
        preview: String,
    ) = JSONObject()
        .put("id", id)
        .put("tag", tag)
        .put("message_id", "m$id")
        .put("message_at_ms", 1_699_999_000_000)
        .put("at_ms", 1_700_003_600_000 + id)
        .put("title", "Ada")
        .put("preview", preview)
        .put("notif_title", "Reminder: Ada")
        .put("notif_body", preview.ifEmpty { MessageReminders.NO_PREVIEW_BODY })
        .toString()

    private fun armedJson() =
        context.getSharedPreferences(PlatformPrefs.MESSAGE_REMINDERS, 0).getString(ReminderStore.ARMED, "")!!

    @Test fun theTextOfWhatsAppRemindersGoesAndEverythingElseStays() {
        assertTrue(MessageReminders.arm(context, reminder(1, "wa:100000000001@s.whatsapp.net", "See you at six")))
        assertTrue(MessageReminders.arm(context, reminder(2, "sms:7", "Call me back")))
        assertTrue(MessageReminders.arm(context, reminder(3, "wa:100000000002@s.whatsapp.net", "")))

        assertEquals(1, MessageReminders.scrubWhatsAppText(context))

        val stored = armedJson()
        assertFalse("the WhatsApp text is gone", "See you at six" in stored)
        assertTrue("the SMS text stays", "Call me back" in stored)
        val byId = MessageReminders.armed(context).associateBy { it.id }
        assertEquals(setOf(1L, 2L, 3L), byId.keys)
        val wa = byId.getValue(1)
        assertEquals(MessageReminders.NO_PREVIEW_BODY, wa.body)
        assertEquals("", wa.raw.getString("preview"))
        assertEquals("Reminder: Ada", wa.title)
        assertEquals(1_700_003_600_001, wa.atMs)
        assertEquals("wa:100000000001@s.whatsapp.net", wa.tag)
        assertEquals("Call me back", byId.getValue(2).body)

        // Idempotent: nothing left to change, nothing written.
        assertEquals(0, MessageReminders.scrubWhatsAppText(context))
        assertEquals(stored, armedJson())
    }

    @Test fun nothingArmedWritesNothing() {
        assertEquals(0, MessageReminders.scrubWhatsAppText(context))
        assertFalse(context.getSharedPreferences(PlatformPrefs.MESSAGE_REMINDERS, 0).contains(ReminderStore.ARMED))
    }

    @Test fun remindMeOnAWhatsAppNotificationSendsNoText() {
        val wa = JSONObject(ReplyActions.remindRequest("wa:100000000001@s.whatsapp.net", "Ada", "Secret", 1, 0))
        assertEquals("", wa.getString("body"))
        assertEquals("Ada", wa.getString("title"))
        val sms = JSONObject(ReplyActions.remindRequest("sms:7", "Ada", "Hello", 1, 0))
        assertEquals("Hello", sms.getString("body"))
    }
}
