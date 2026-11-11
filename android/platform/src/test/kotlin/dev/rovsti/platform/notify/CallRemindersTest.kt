package dev.rovsti.platform.notify

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import dev.rovsti.bridge.CallReminderReceiver
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.PlatformPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallRemindersTest : RemindersTestBase() {
    private val id = 0x1_0000_0009L
    private val json =
        JSONObject()
            .put("id", id)
            .put("number", "+905551112233")
            .put("at_ms", 1_900_000_000_000)
            .put("title", "Call Ayse")
            .put("body", "You asked to be reminded")
            .toString()

    private fun repository() = ReminderRepositories.calls(context)

    @Test fun armSetsAnAllowWhileIdleAlarmWithTheSameRequestCodeAndExtras() {
        assertTrue(repository().arm(json))

        val alarm = alarms.peekNextScheduledAlarm()!!
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(1_900_000_000_000, alarm.triggerAtTime)
        assertTrue(alarm.isAllowWhileIdle)
        val pending = shadowOf(alarm.operation)
        assertEquals((id xor (id ushr 32)).toInt(), pending.requestCode)
        assertTrue(pending.isBroadcastIntent)
        val intent = pending.savedIntent
        assertEquals("dev.rovsti.bridge.CALL_REMINDER_FIRE", intent.action)
        assertEquals(id, intent.getLongExtra("reminder_id", 0))
        assertEquals(context.packageName, intent.`package`)
        assertEquals(CallReminderReceiver::class.java.name, intent.component?.className)
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        // the prefs file and key the GPUI build wrote
        val stored = context.getSharedPreferences("rovsti_call_reminders", 0).getString("armed", null)
        assertEquals(PlatformPrefs.CALL_REMINDERS, "rovsti_call_reminders")
        assertEquals(
            id,
            org.json
                .JSONArray(stored)
                .getJSONObject(0)
                .getLong("id"),
        )
    }

    @Test fun aMalformedItemIsRefusedAndSetsNothing() {
        assertFalse(repository().arm("{\"id\":0,\"number\":\"1\"}"))
        assertFalse(repository().arm("{\"id\":4,\"number\":\"\"}"))
        assertFalse(repository().arm("not json"))
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test fun firingPostsTheNotificationOnTheChannelAndForgetsTheItem() {
        repository().arm(json)
        val fire = shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent
        CallReminderReceiver().onReceive(context, Intent(fire))

        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification("call-reminder", (id xor (id ushr 32)).toInt())
        assertNotNull(posted)
        assertEquals(PlatformChannels.CALL_REMINDERS, posted.channelId)
        assertEquals("Call Ayse", posted.extras.getCharSequence("android.title").toString())
        assertEquals(listOf("Call", "Message"), posted.actions.map { it.title.toString() })
        assertTrue(CallReminders.armed(context).isEmpty())
    }

    @Test fun theTapDialsWithTheNumberInThisAppOnly() {
        repository().arm(json)
        CallReminderReceiver().onReceive(
            context,
            Intent(shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent),
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification("call-reminder", (id xor (id ushr 32)).toInt())
        val dial = shadowOf(posted.contentIntent).savedIntent
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel", dial.data?.scheme)
        assertEquals("+905551112233", dial.data?.schemeSpecificPart)
        assertEquals(context.packageName, dial.`package`)
    }

    @Test fun aWrongActionOrAnUnknownIdDoesNothing() {
        CallReminderReceiver().onReceive(context, Intent("other").putExtra("reminder_id", id))
        CallReminderReceiver().onReceive(context, Intent(CallReminders.ACTION_FIRE).putExtra("reminder_id", 77L))
        val nm = context.getSystemService(NotificationManager::class.java)
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test fun rearmAfterBootSetsTheSameAlarmAgain() {
        repository().arm(json)
        val before = shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!)
        // A reboot clears every alarm; the stored item stays.
        context.getSystemService(AlarmManager::class.java).cancel(alarms.peekNextScheduledAlarm()!!.operation!!)
        assertNull(alarms.peekNextScheduledAlarm())

        assertTrue(repository().rearmAll())

        val again = alarms.peekNextScheduledAlarm()!!
        assertEquals(1_900_000_000_000, again.triggerAtTime)
        assertEquals(before.requestCode, shadowOf(again.operation).requestCode)
        assertEquals(before.savedIntent.action, shadowOf(again.operation).savedIntent.action)
        assertEquals(id, shadowOf(again.operation).savedIntent.getLongExtra("reminder_id", 0))
    }

    @Test fun disarmCancelsTheAlarmAndForgetsTheItem() {
        val repo = repository()
        repo.arm(json)
        assertTrue(repo.disarm(id))
        assertNull(alarms.peekNextScheduledAlarm())
        assertFalse(repo.disarm(id))
    }

    @Test fun armedFlowFollowsArmDisarmAndFire() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repository()
            val seen = mutableListOf<List<Long>>()
            val job = launch { repo.armed().collect { seen += it.map(ReminderItem::id) } }
            repo.arm(json)
            repo.disarm(id)
            repo.arm(json)
            CallReminderReceiver().onReceive(
                context,
                Intent(shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent),
            )
            job.cancel()
            assertEquals(emptyList<Long>(), seen.first())
            assertTrue(seen.any { it == listOf(id) })
            assertEquals(emptyList<Long>(), seen.last())
        }

    @Test fun firingEmitsReminderFired() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<String>()
            val job = launch { PlatformEvents.events.collect { events += it.kind } }
            repository().arm(json)
            CallReminderReceiver().onReceive(
                context,
                Intent(shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent),
            )
            job.cancel()
            assertTrue("reminder_fired" in events)
        }
}
