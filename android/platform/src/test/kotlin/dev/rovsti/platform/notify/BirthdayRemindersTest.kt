package dev.rovsti.platform.notify

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import dev.rovsti.bridge.BirthdayReminderReceiver
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
class BirthdayRemindersTest : RemindersTestBase() {
    private val id = 0x1_0000_0009L
    private val json =
        JSONObject()
            .put("id", id)
            .put("contact_id", 42L)
            .put("at_ms", 1_900_000_000_000)
            .put("title", "Ayse turns 30")
            .put("body", "You asked to be reminded")
            .toString()

    private fun repository() = ReminderRepositories.birthdays(context)

    @Test fun armSetsAnInexactAlarmWithTheSameRequestCodeAndExtras() {
        assertTrue(repository().arm(json))

        val alarm = alarms.peekNextScheduledAlarm()!!
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(1_900_000_000_000, alarm.triggerAtTime)
        assertTrue(alarm.isAllowWhileIdle)
        val pending = shadowOf(alarm.operation)
        assertEquals((id xor (id ushr 32)).toInt(), pending.requestCode)
        assertTrue(pending.isBroadcastIntent)
        val intent = pending.savedIntent
        assertEquals("dev.rovsti.bridge.BIRTHDAY_REMINDER_FIRE", intent.action)
        assertEquals(id, intent.getLongExtra("birthday_reminder_id", 0))
        assertEquals(context.packageName, intent.`package`)
        assertEquals(BirthdayReminderReceiver::class.java.name, intent.component?.className)
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        // the prefs file and key the GPUI build wrote
        val stored = context.getSharedPreferences("rovsti_birthday_reminders", 0).getString("armed", null)
        assertEquals(PlatformPrefs.BIRTHDAY_REMINDERS, "rovsti_birthday_reminders")
        assertEquals(
            id,
            org.json
                .JSONArray(stored)
                .getJSONObject(0)
                .getLong("id"),
        )
    }

    @Test fun aMalformedItemIsRefusedAndSetsNothing() {
        assertFalse(repository().arm("{\"id\":0,\"contact_id\":1}"))
        assertFalse(repository().arm("{\"id\":4,\"contact_id\":0}"))
        assertFalse(repository().arm("not json"))
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test fun firingPostsTheNotificationOnTheChannelAndForgetsTheItem() {
        repository().arm(json)
        val fire = shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent
        BirthdayReminderReceiver().onReceive(context, Intent(fire))

        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification("birthday-reminder", (id xor (id ushr 32)).toInt())
        assertNotNull(posted)
        assertEquals(PlatformChannels.BIRTHDAYS, posted.channelId)
        assertEquals("Ayse turns 30", posted.extras.getCharSequence("android.title").toString())
        assertTrue(posted.actions == null || posted.actions.isEmpty())
        assertTrue(BirthdayReminders.armed(context).isEmpty())
    }

    @Test fun theTapOpensTheAppWithTheContactId() {
        repository().arm(json)
        BirthdayReminderReceiver().onReceive(
            context,
            Intent(shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent),
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification("birthday-reminder", (id xor (id ushr 32)).toInt())
        val launch = shadowOf(posted.contentIntent).savedIntent
        assertEquals(42L, launch.getLongExtra("open_contact_id", 0))
        assertEquals(BirthdayReminders.EXTRA_CONTACT_ID, "open_contact_id")
    }

    @Test fun aWrongActionOrAnUnknownIdDoesNothing() {
        BirthdayReminderReceiver().onReceive(context, Intent("other").putExtra("birthday_reminder_id", id))
        BirthdayReminderReceiver().onReceive(
            context,
            Intent(BirthdayReminders.ACTION_FIRE).putExtra("birthday_reminder_id", 77L),
        )
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
        assertEquals(id, shadowOf(again.operation).savedIntent.getLongExtra("birthday_reminder_id", 0))
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
            val job = launch { repo.armed().collect { seen += it.map(BirthdayItem::id) } }
            repo.arm(json)
            repo.disarm(id)
            repo.arm(json)
            BirthdayReminderReceiver().onReceive(
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
            BirthdayReminderReceiver().onReceive(
                context,
                Intent(shadowOf(alarms.peekNextScheduledAlarm()!!.operation!!).savedIntent),
            )
            job.cancel()
            assertTrue("birthday_reminder_fired" in events)
        }
}
