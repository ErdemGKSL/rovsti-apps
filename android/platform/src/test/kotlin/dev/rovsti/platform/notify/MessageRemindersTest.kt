package dev.rovsti.platform.notify

import android.app.NotificationManager
import android.content.Intent
import dev.rovsti.bridge.MessageReminderReceiver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageRemindersTest : RemindersTestBase() {
    private val id = 1_700_000_000_000L
    private val json =
        JSONObject()
            .put("id", id)
            .put("tag", "sms:7")
            .put("message_id", "m1")
            .put("message_at_ms", 1_699_999_000_000)
            .put("at_ms", 1_700_003_600_000)
            .put("notif_title", "Reminder: Ayse")
            .put("notif_body", "Call me")
            .toString()

    private fun repository() = ReminderRepositories.messages(context)

    @Test fun armFireAndRearmKeepTheContract() {
        assertTrue(repository().arm(json))
        val op = shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!)
        assertEquals((id xor (id ushr 32)).toInt(), op.requestCode)
        assertEquals("dev.rovsti.bridge.MESSAGE_REMINDER_FIRE", op.savedIntent.action)
        assertEquals(id, op.savedIntent.getLongExtra("message_reminder_id", 0))
        assertEquals(MessageReminderReceiver::class.java.name, op.savedIntent.component?.className)

        context
            .getSystemService(
                android.app.AlarmManager::class.java,
            ).cancel(alarms.peekNextScheduledAlarm()!!.operation!!)
        assertTrue(repository().rearmAll())
        assertEquals(1_700_003_600_000, alarms.peekNextScheduledAlarm()!!.triggerAtTime)

        MessageReminderReceiver().onReceive(context, Intent(op.savedIntent))
        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification("message-reminder", (id xor (id ushr 32)).toInt())
        assertEquals(PlatformChannels.MESSAGE_REMINDERS, posted.channelId)
        val launch = shadowOf(posted.contentIntent).savedIntent
        assertEquals("sms:7", launch.getStringExtra("open_thread_tag"))
        assertEquals("m1", launch.getStringExtra("open_message_id"))
        assertTrue(MessageReminders.armed(context).isEmpty())
    }

    @Test fun aChatLevelReminderCarriesNoMessageExtras() {
        repository().arm(JSONObject(json).put("message_id", "").toString())
        MessageReminderReceiver().onReceive(
            context,
            Intent(shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent),
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification("message-reminder", (id xor (id ushr 32)).toInt())
        assertNull(shadowOf(posted.contentIntent).savedIntent.getStringExtra("open_message_id"))
    }
}
