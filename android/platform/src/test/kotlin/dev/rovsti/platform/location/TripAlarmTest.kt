package dev.rovsti.platform.location

import android.app.AlarmManager
import android.app.NotificationManager
import dev.rovsti.core.FakeRustCore
import dev.rovsti.platform.Headless
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.PlatformEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The alarm ring, timeout, dismiss and snooze emit the event kinds the GPUI bridge always emitted
 * (`trip_alarm` with `ringing`, `timed_out`, `snoozed`, `dismissed`), ask Rust through `Headless`, and fall back to
 * the local default snooze when Rust gives no answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripAlarmTest {
    private val app = RuntimeEnvironment.getApplication()
    private val fake = FakeRustCore()
    private val events = java.util.Collections.synchronizedList(mutableListOf<PlatformEvent>())
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var collector: Job? = null
    private val dir =
        File.createTempFile("alarm", "").let {
            it.delete()
            it.mkdirs()
            it.absolutePath
        }

    @Before fun setUp() {
        Headless.useCore(fake)
        collector = scope.launch { PlatformEvents.events.collect { events += it } }
        // Vibration only: the test needs no audio player.
        File(dir, "stops-prefs.json").writeText("""{"sound":false,"vibrate":true}""")
        TripPrefs.save(app, TripSpec(dir, "Trip", "", 10_000L, false, alarm = """{"ring_timeout_ms":10000}"""))
    }

    @After fun tearDown() {
        TripAlarm.stopRinging(app)
        collector?.cancel()
        Headless.useCore(null)
        TripPrefs.clear(app)
        File(dir).deleteRecursively()
    }

    private fun alarmEvents() =
        synchronized(events) {
            events.filter { it.kind == TripAlarm.EVENT }.map { JSONObject(it.json) }
        }

    private fun actions() = synchronized(requests) { requests.map { JSONObject(it).getString("action") } }

    private fun script(answer: (String) -> String) {
        fake.onHeadless("trip_alarm") { request ->
            requests += request
            answer(JSONObject(request).getString("action"))
        }
    }

    private fun awaitEvent(event: String): JSONObject {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            alarmEvents().firstOrNull { it.getString("event") == event }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no $event event; saw ${alarmEvents()}")
    }

    @Test fun ringPostsTheAlarmNotificationAndEmitsRinging() {
        TripAlarm.ring(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = nm.activeNotifications.single { it.id == TripAlarm.NOTIFICATION_ID }
        assertEquals("trip_alarm_v2", posted.notification.channelId)
        assertEquals(
            "Wake up",
            posted.notification.extras
                .getCharSequence("android.title")
                .toString(),
        )
        assertEquals(listOf("ringing"), alarmEvents().map { it.getString("event") })
        assertTrue(TripAlarm.ringing)
        // A second ring while ringing does nothing.
        TripAlarm.ring(app)
        assertEquals(1, alarmEvents().size)
        // The process-death guard is armed like a snooze.
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isNotEmpty())
    }

    @Test fun ringThenTimeoutThenDismissEmitsTheSameKindsAsToday() {
        val reRing = System.currentTimeMillis() + 120_000L
        script { action -> if (action == "timeout") """{"ring_at_ms":$reRing}""" else """{"dismissed":true}""" }
        TripAlarm.ring(app)

        org.robolectric.shadows.ShadowLooper
            .idleMainLooper(11, TimeUnit.SECONDS)
        val timedOut = awaitEvent("timed_out")
        assertEquals(reRing, timedOut.getLong("at_ms"))
        assertFalse(TripAlarm.ringing)

        val done = CountDownLatch(1)
        TripAlarm.dismiss(app) { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("ringing", "timed_out", "dismissed"), alarmEvents().map { it.getString("event") })
        assertEquals(listOf("timeout", "dismiss"), actions())
        assertEquals(dir, JSONObject(requests.first()).getString("dir"))
    }

    @Test fun snoozeAsksRustAndArmsTheReRingItAnswers() {
        val reRing = System.currentTimeMillis() + 300_000L
        script { """{"ring_at_ms":$reRing}""" }
        TripAlarm.ring(app)
        val done = CountDownLatch(1)
        TripAlarm.snooze(app) { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(reRing, awaitEvent("snoozed").getLong("at_ms"))
        assertEquals(listOf("snooze"), actions())
        assertTrue(
            shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
                it.triggerAtMs ==
                    reRing
            },
        )
    }

    @Test fun aRefusedSnoozeBecomesADismissal() {
        script { action -> if (action == "snooze") """{"silenced":true}""" else """{"dismissed":true}""" }
        TripAlarm.ring(app)
        val done = CountDownLatch(1)
        TripAlarm.snooze(app) { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        awaitEvent("dismissed")
        assertEquals(listOf("snooze", "dismiss"), actions())
    }

    @Test fun aNullHeadlessAnswerKeepsTheLocalFallbackSnooze() {
        // No script: the fake answers "" like a missing library, so Headless gives null.
        TripAlarm.ring(app)
        val before = System.currentTimeMillis()
        val done = CountDownLatch(1)
        TripAlarm.snooze(app) { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        val at = awaitEvent("snoozed").getLong("at_ms")
        val minutes = TRIP_DEFAULT_SNOOZE_MIN * 60_000L
        assertTrue("at=$at", at in (before + minutes)..(System.currentTimeMillis() + minutes))
    }

    @Test fun aNullAnswerToTheTimeoutAlsoFallsBackToTheDefaultSnooze() {
        TripAlarm.ring(app)
        val before = System.currentTimeMillis()
        org.robolectric.shadows.ShadowLooper
            .idleMainLooper(11, TimeUnit.SECONDS)
        val at = awaitEvent("timed_out").getLong("at_ms")
        assertTrue(at >= before + TRIP_DEFAULT_SNOOZE_MIN * 60_000L)
    }

    @Test fun aTestRingNeverTellsRust() {
        script { """{"dismissed":true}""" }
        TripAlarm.ring(app, test = true, planOverride = """{"sound":false,"vibrate":true}""")
        val done = CountDownLatch(1)
        TripAlarm.dismiss(app) { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        awaitEvent("dismissed")
        assertEquals(emptyList<String>(), actions())
    }

    @Test fun pureSnoozeRules() {
        assertEquals(1_000L + 60_000L, snoozeAt(1_000L, 0))
        assertEquals(1_000L + 15 * 60_000L, snoozeAt(1_000L, 99))
        assertEquals(500L + 5 * 60_000L, snoozeAt(null as String?, 500L))
        assertEquals(500L + 5 * 60_000L, snoozeAt("not json", 500L))
        assertEquals(9_000L, snoozeAt("""{"ring_at_ms":9000}""", 500L))
        assertNull(snoozeAt("""{"silenced":true}""", 500L))
        assertEquals("""{"event":"ringing"}""", tripAlarmEventJson("ringing", 5L))
        assertEquals(7L, JSONObject(tripAlarmEventJson("snoozed", 7L)).getLong("at_ms"))
        assertEquals(10_000L + 5 * 60_000L + 1_000L, guardRingAt(1_000L, 10_000L))
        assertEquals(true to true, alarmChannels(null))
        assertEquals(true to false, alarmChannels("""{"sound":true,"vibrate":false}"""))
        assertEquals(true to false, alarmChannels("""{"sound":false,"vibrate":false}"""))
        assertEquals(true to true, alarmChannels("{broken"))
        assertEquals("""{"dir":"d","action":"snooze"}""", tripAlarmRequestJson("d", "snooze"))
        assertEquals("full_screen", JSONObject(fullScreenErrorJson()).getString("error"))
    }
}
