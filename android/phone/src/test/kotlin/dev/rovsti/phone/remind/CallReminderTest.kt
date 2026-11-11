package dev.rovsti.phone.remind

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import dev.rovsti.phone.BootReceiver
import dev.rovsti.platform.notify.CallReminders
import dev.rovsti.platform.notify.PlatformChannels
import dev.rovsti.platform.notify.ReminderItem
import dev.rovsti.platform.notify.ReminderRepositories
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar
import java.util.TimeZone

/**
 * E5.12 call reminders on `ShadowAlarmManager`: the alarm goes off at the stored time, a reboot (or an update, or a
 * change of the exact-alarm grant) puts it back through the boot receiver, a missing exact-alarm grant falls back to the
 * inexact alarm, and the notification carries the kind's icon, Call and Message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallReminderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val id = 0x1_0000_0009L
    private val at = 1_900_000_000_000L
    private val json =
        JSONObject()
            .put("id", id)
            .put("number", "+905551112233")
            .put("name", "Ayse Kaya")
            .put("at_ms", at)
            .put("title", "Call back Ayse Kaya")
            .put("body", "You asked to be reminded")
            .toString()

    private fun arm() = ReminderRepositories.calls(context).arm(json)

    // The receiver does this on its own thread; the work itself is `CallReminders.fire`.
    private fun fire() = CallReminders.fire(context, id)

    private fun posted() =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(TAG, (id xor (id ushr 32)).toInt()) as android.app.Notification?

    @Test fun theAlarmIsSetForTheStoredTimeAndWakesTheDevice() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(arm())
        val alarm = alarms.peekNextScheduledAlarm()!!
        assertEquals(at, alarm.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue(alarm.isAllowWhileIdle)
        assertTrue(CallReminders.exactAllowed(context))
    }

    @Test fun aRebootPutsTheAlarmBackFromTheStore() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        arm()
        // A reboot clears every alarm; the stored reminder stays.
        context.getSystemService(AlarmManager::class.java).cancel(alarms.peekNextScheduledAlarm()!!.operation!!)
        assertNull(alarms.peekNextScheduledAlarm())

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val again = alarms.peekNextScheduledAlarm()
        assertNotNull(again)
        assertEquals(at, again!!.triggerAtTime)
        assertEquals(id, shadowOf(again.operation).savedIntent.getLongExtra("reminder_id", 0))
    }

    @Test fun anUpdateAndAChangedExactAlarmGrantRearmToo() {
        for (action in listOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )) {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            arm()
            context.getSystemService(AlarmManager::class.java).cancel(alarms.peekNextScheduledAlarm()!!.operation!!)
            BootReceiver().onReceive(context, Intent(action))
            assertNotNull(action, alarms.peekNextScheduledAlarm())
            CallReminders.disarm(context, id)
        }
    }

    @Test fun anotherBroadcastDoesNotRearm() {
        arm()
        context.getSystemService(AlarmManager::class.java).cancel(alarms.peekNextScheduledAlarm()!!.operation!!)
        BootReceiver().onReceive(context, Intent("something.else"))
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test fun withoutTheExactAlarmGrantTheAlarmIsInexactAndNothingIsLost() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(CallReminders.exactAllowed(context))
        assertTrue(arm())
        val alarm = alarms.peekNextScheduledAlarm()!!
        assertEquals(at, alarm.triggerAtTime)
        assertTrue(alarm.isAllowWhileIdle)
        // The reminder is still stored, so a later boot or grant sets it again.
        assertEquals(1, CallReminders.armed(context).size)
    }

    @Test fun theNotificationHasTheKindIconAccentCallAndMessage() {
        arm()
        fire()
        val n = posted()!!
        assertEquals(PlatformChannels.CALL_REMINDERS, n.channelId)
        assertEquals(dev.rovsti.platform.R.drawable.ic_notif_reminder, n.smallIcon.resId)
        assertEquals(0xFFFF9500.toInt(), n.color)
        assertNotNull(n.getLargeIcon())
        assertEquals(listOf("Call", "Message"), n.actions.map { it.title.toString() })
        val call = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(Intent.ACTION_DIAL, call.action)
        assertEquals("+905551112233", call.data?.schemeSpecificPart)
        val message = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(Intent.ACTION_SENDTO, message.action)
        assertEquals("smsto", message.data?.scheme)
        assertTrue(CallReminders.armed(context).isEmpty())
    }

    @Test fun retimingMovesOnlyTheAlarm() {
        val moved = JSONObject(ReminderItem.retime(json, 42L))
        assertEquals(42L, moved.getLong("at_ms"))
        assertEquals("Call back Ayse Kaya", moved.getString("title"))
        assertEquals("not json", ReminderItem.retime("not json", 42L))
        val item = ReminderItem.parse(json)!!
        assertEquals("Ayse Kaya", item.name)
        assertFalse(item.missed)
    }

    @Test fun pickATimeIsTheNextSuchTimeToday_orTomorrow() {
        val zone = TimeZone.getTimeZone("Europe/Istanbul")
        val now =
            Calendar
                .getInstance(zone)
                .apply { set(2026, Calendar.NOVEMBER, 11, 10, 30, 15) }
                .timeInMillis

        fun at(ms: Long) =
            Calendar.getInstance(zone).apply { timeInMillis = ms }.let {
                Triple(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
            }
        assertEquals(Triple(11, 18, 5), at(RemindTime.next(now, 18, 5, zone)))
        assertEquals(Triple(12, 9, 0), at(RemindTime.next(now, 9, 0, zone)))
        assertEquals(Triple(12, 10, 30), at(RemindTime.next(now, 10, 30, zone)))
    }

    private companion object {
        const val TAG = "call-reminder"
    }
}
