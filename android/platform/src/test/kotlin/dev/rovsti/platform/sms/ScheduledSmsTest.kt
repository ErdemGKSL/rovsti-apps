package dev.rovsti.platform.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.rovsti.bridge.ReceiverWork
import dev.rovsti.bridge.ScheduledSmsReceiver
import dev.rovsti.core.model.messages.FiredSms
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.PlatformPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSmsManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduledSmsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val item =
        ScheduledItem(id = 0x1_0000_0007, atMs = 1_900_000_000_000, to = "+905551112233", body = "later", subId = 2)

    @Before fun inline() {
        ReceiverWork.executor = { it.run() }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun restore() {
        ReceiverWork.executor = { Thread(it).start() }
        ShadowSmsManager.reset()
    }

    private fun prefs() = context.getSharedPreferences(PlatformPrefs.SCHEDULED_SMS, Context.MODE_PRIVATE)

    @Test fun armSetsAnExactAlarmWithTheSameRequestCodeAndExtras() {
        val mode = ScheduledSms.arm(context, item)

        assertEquals(ArmMode.Exact, mode)
        val alarm = alarms.nextScheduledAlarm!!
        assertNotNull(alarm)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(item.atMs, alarm.triggerAtTime)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertTrue(alarm.isAllowWhileIdle)
        val pending = shadowOf(alarm.operation)
        // `(id xor (id ushr 32)).toInt()`: what the GPUI build used, so an old alarm is the one cancelled.
        assertEquals((item.id xor (item.id ushr 32)).toInt(), pending.requestCode)
        assertEquals(ScheduledSms.requestCode(item.id), pending.requestCode)
        assertTrue(pending.isBroadcastIntent)
        val intent = pending.savedIntent
        assertEquals("dev.rovsti.bridge.SCHEDULED_SMS_FIRE", intent.action)
        assertEquals(item.id, intent.getLongExtra("scheduled_id", 0))
        assertEquals(context.packageName, intent.`package`)
        assertEquals(ScheduledSmsReceiver::class.java.name, intent.component?.className)
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test fun withExactAlarmsDeniedAnInexactWindowIsSetAndReported() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val mode = ScheduledSms.arm(context, item)

        assertEquals(ArmMode.Inexact, mode)
        val alarm = alarms.nextScheduledAlarm!!
        assertEquals(item.atMs, alarm.triggerAtTime)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(ScheduledSms.requestCode(item.id), shadowOf(alarm.operation).requestCode)
    }

    @Test fun armStoresThePayloadAndDisarmCancelsIt() {
        ScheduledSms.arm(context, item)
        assertEquals(listOf(item), ScheduledSms.armed(context))
        assertTrue(prefs().getString("armed", "")!!.contains("later"))

        assertTrue(ScheduledSms.disarm(context, item.id))

        assertEquals(emptyList<ScheduledItem>(), ScheduledSms.armed(context))
        assertNull(alarms.peekNextScheduledAlarm())
        assertEquals(false, ScheduledSms.disarm(context, item.id))
    }

    @Test fun aMalformedItemIsRejected() {
        assertNull(ScheduledSms.arm(context, """{"id":0,"to":"1"}"""))
        assertNull(ScheduledSms.arm(context, """{"id":5,"to":""}"""))
        assertNull(ScheduledSms.arm(context, "nope"))
        assertNull(alarms.peekNextScheduledAlarm())
        assertEquals(ArmMode.Exact, ScheduledSms.arm(context, item.toJson().toString()))
    }

    @Test fun armingAgainReplacesTheItem() {
        ScheduledSms.arm(context, item)
        ScheduledSms.arm(context, item.copy(body = "edited"))
        assertEquals(listOf("edited"), ScheduledSms.armed(context).map { it.body })
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun rearmAllSetsEveryStoredAlarm() {
        ScheduledSms.arm(context, item)
        ScheduledSms.arm(context, item.copy(id = 9))
        alarms.scheduledAlarms.toList().forEach {
            context
                .getSystemService(
                    AlarmManager::class.java,
                ).cancel(it.operation!!)
        }
        assertEquals(0, alarms.scheduledAlarms.size)

        ScheduledSms.rearmAll(context)

        assertEquals(2, alarms.scheduledAlarms.size)
    }

    @Test fun theReceiverFiresScheduledFiredAndClearsThePref() =
        runTest(UnconfinedTestDispatcher()) {
            ScheduledSms.arm(context, item)
            val events = ArrayList<PlatformEvent>()
            val job = launch { PlatformEvents.events.toList(events) }

            ScheduledSmsReceiver().onReceive(
                context,
                Intent(ScheduledSms.ACTION_FIRE).putExtra(ScheduledSms.EXTRA_ID, item.id),
            )
            job.cancel()

            val fired = events.single { it.kind == "scheduled_fired" }
            val o = JSONObject(fired.json)
            assertEquals(item.id, o.getLong("id"))
            assertEquals(true, o.getBoolean("ok"))
            // The armed payload is gone; the outcome waits in the fired list for the next pull.
            assertEquals(JSONArray("[]").toString(), JSONArray(prefs().getString("armed", "[]")).toString())
            val pulled = JSONArray(ScheduledSms.pullFired(context))
            assertEquals(1, pulled.length())
            assertEquals(item.id, pulled.getJSONObject(0).getLong("id"))
            assertEquals("[]", ScheduledSms.pullFired(context))
            // And the message really went to the SMS manager, on the item's SIM.
            val sent =
                shadowOf(context.getSystemService(android.telephony.SmsManager::class.java).createForSubscriptionId(2))
            assertEquals("+905551112233", sent.lastSentTextMessageParams.destinationAddress)
            assertEquals("later", sent.lastSentTextMessageParams.text)
        }

    @Test fun aFiredItemThatIsNoLongerArmedDoesNothing() =
        runTest(UnconfinedTestDispatcher()) {
            val events = ArrayList<PlatformEvent>()
            val job = launch { PlatformEvents.events.toList(events) }
            ScheduledSmsReceiver().onReceive(
                context,
                Intent(ScheduledSms.ACTION_FIRE).putExtra(ScheduledSms.EXTRA_ID, 77L),
            )
            ScheduledSmsReceiver().onReceive(context, Intent(ScheduledSms.ACTION_FIRE))
            job.cancel()
            assertTrue(events.none { it.kind == "scheduled_fired" })
        }

    @Test fun bootRearmsInsteadOfFiring() {
        ScheduledSms.arm(context, item)
        alarms.scheduledAlarms.toList().forEach {
            context
                .getSystemService(
                    AlarmManager::class.java,
                ).cancel(it.operation!!)
        }
        ScheduledSmsReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(1, ScheduledSms.armed(context).size)
    }

    @Test fun theRepositoryReportsTheModeAndTheFiredFlow() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = AndroidScheduledSmsRepository(context, UnconfinedTestDispatcher(testScheduler))
            assertEquals(ArmResult.Armed(ArmMode.Exact), repo.arm(item))
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            assertEquals(ArmResult.Armed(ArmMode.Inexact), repo.arm(item.copy(id = 8)))
            assertEquals(ArmResult.Rejected, repo.arm(item.copy(id = 0)))
            assertEquals(setOf(item.id, 8L), repo.armed().map { it.id }.toSet())

            val fired = ArrayList<FiredSms>()
            val job = launch { repo.fired().toList(fired) }
            PlatformEvents.emit("scheduled_fired", """{"id":8,"ok":false}""")
            PlatformEvents.emit("other", """{"id":1}""")
            PlatformEvents.emit("scheduled_fired", "{broken")
            job.cancel()
            assertEquals(listOf(8L to false), fired.map { it.id to it.ok })
            assertTrue(repo.disarm(8))
            assertEquals("[]", repo.pullFired())
        }
}
