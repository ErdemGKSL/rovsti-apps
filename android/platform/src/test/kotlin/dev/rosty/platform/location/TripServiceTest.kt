package dev.rosty.platform.location

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import dev.rosty.bridge.TripService
import dev.rosty.core.Envelope
import dev.rosty.core.FakeRustCore
import dev.rosty.core.model.headless.TripFixAnswer
import dev.rosty.core.model.headless.TripFixRequest
import dev.rosty.platform.Headless
import dev.rosty.platform.PlatformEvent
import dev.rosty.platform.PlatformEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripServiceTest {
    private val app = RuntimeEnvironment.getApplication()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val fake = FakeRustCore()
    private val events = java.util.Collections.synchronizedList(mutableListOf<PlatformEvent>())
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var collector: Job? = null
    private val dir =
        File.createTempFile("trip", "").let {
            it.delete()
            it.mkdirs()
            it.absolutePath
        }

    private val spec = TripSpec(dir, "Home - Work", "Outside the circle", 10_000L, true)

    private fun startIntent() = Intent(app, TripService::class.java).putExtra(TripService.EXTRA_SPEC, spec.toJson())

    @Before fun setUp() {
        Headless.useCore(fake)
        collector = scope.launch { PlatformEvents.events.collect { events += it } }
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(
            app.getSystemService(LocationManager::class.java),
        ).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        TripPrefs.clear(app)
    }

    @After fun tearDown() {
        collector?.cancel()
        Headless.useCore(null)
        TripPrefs.clear(app)
        File(dir).deleteRecursively()
    }

    private fun statuses() =
        synchronized(events) { events.filter { it.kind == TripService.EVENT_STATUS }.map { it.json } }

    @Test fun aUserStartPostsTheLocationForegroundNotification() {
        val controller = Robolectric.buildService(TripService::class.java, startIntent()).create()
        controller.startCommand(0, 1)
        val service = controller.get()
        val shadow = shadowOf(service)

        assertEquals(TripService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        val notification = checkNotNull(shadow.lastForegroundNotification)
        assertEquals("trip", notification.channelId)
        assertEquals("Trip in progress", nm.getNotificationChannel("trip").name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel("trip").importance)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        assertEquals("Home - Work", notification.extras.getCharSequence("android.title").toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        // The trip survives a process death: the spec is saved.
        assertEquals(spec, TripPrefs.load(app))
        assertTrue(TripService.running)
        controller.destroy()
    }

    @Test fun aRedeliveredStartThatAndroidRefusesPostsTripProblemAndKeepsTheTrip() {
        TripPrefs.save(app, spec)
        val controller =
            Robolectric
                .buildService(
                    TripService::class.java,
                    Intent(app, TripService::class.java),
                ).create()
        shadowOf(controller.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background"))
        controller.startCommand(android.app.Service.START_FLAG_REDELIVERY, 1)

        val posted = nm.activeNotifications.single { it.id == TripService.NOTIFICATION_ID + 1 }
        assertEquals("trip_problem", posted.notification.channelId)
        assertEquals(
            "Stops paused your trip",
            posted.notification.extras
                .getCharSequence("android.title")
                .toString(),
        )
        // Only Stop or Rust's `ended` clears the saved trip.
        assertEquals(spec, TripPrefs.load(app))
        assertEquals(listOf(tripRestartErrorJson()), statuses())
        controller.destroy()
    }

    @Test fun aFixIsSentToRustAsTheTripFixRequest() {
        val seen = CountDownLatch(1)
        var request: String? = null
        val answer = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_fix.answer.json").readText()
        fake.onHeadless("trip_fix") {
            request = it
            seen.countDown()
            answer
        }
        val controller = Robolectric.buildService(TripService::class.java, startIntent()).create()
        controller.startCommand(0, 1)

        val fix =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 41.0
                longitude = 28.9
                accuracy = 10f
                speed = 10f
                time = 2_000L
                elapsedRealtimeNanos = 2_000_000_000L
            }
        shadowOf(app.getSystemService(LocationManager::class.java)).simulateLocation(fix)
        assertTrue("trip_fix was not asked", seen.await(10, TimeUnit.SECONDS))

        val model = Envelope.json.decodeFromString(TripFixRequest.serializer(), checkNotNull(request))
        // The phone's language goes with every fix: the notification follows it.
        // The monotonic clock of the fix goes too: the trip's speed history runs on it.
        assertEquals(TripFixRequest(dir, 41.0, 28.9, 10.0, 10.0, 2_000L, elapsedMs = 2_000L, locale = "en-US"), model)
        val golden = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_fix.request.json").readText()
        assertEquals(
            org.json
                .JSONObject(golden.replace("\$DIR", dir))
                .keys()
                .asSequence()
                .toSet(),
            org.json
                .JSONObject(request)
                .keys()
                .asSequence()
                .toSet(),
        )

        // Rust's answer is passed on as a status and is a fixture-shaped TripFixAnswer.
        awaitUntil { statuses().isNotEmpty() }
        val status = TripStatus.parse(statuses().first())
        assertTrue(status is TripStatus.Fixed)
        assertEquals(
            Envelope.json.decodeFromString(TripFixAnswer.serializer(), answer),
            (status as TripStatus.Fixed).answer,
        )
        controller.destroy()
    }

    @Test fun theRequestAsksForRustsIntervalWithNoThrottleAndNoBatching() {
        val controller = Robolectric.buildService(TripService::class.java, startIntent()).create()
        controller.startCommand(0, 1)
        val requests =
            shadowOf(
                app.getSystemService(LocationManager::class.java),
            ).getLocationRequests(LocationManager.GPS_PROVIDER)
        val request = requests.single()
        assertEquals(10_000L, request.intervalMillis)
        // Not half of it: fixes other apps trigger would each cost a Rust call, a file write and a notification.
        assertEquals(10_000L, request.minUpdateIntervalMillis)
        assertEquals(0f, request.minUpdateDistanceMeters, 0f)
        assertEquals(0L, request.maxUpdateDelayMillis)
        assertEquals(android.location.LocationRequest.QUALITY_HIGH_ACCURACY, request.quality)
        controller.destroy()
    }

    @Test fun whenNoProviderFindsAFixRustPlansTheRetryAndTheStatusStaysSearching() {
        val seen = CountDownLatch(1)
        var request: String? = null
        val answer = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_no_fix.answer.json").readText()
        fake.onHeadless("trip_no_fix") {
            request = it
            seen.countDown()
            answer
        }
        TripClock.clock =
            dev.rosty.design.time
                .RostyClock { 65_000L }
        TripClock.elapsed = { 65_000L }
        val manager = app.getSystemService(LocationManager::class.java)
        val controller = Robolectric.buildService(TripService::class.java, startIntent()).create()
        controller.startCommand(0, 1)
        // Location switched off: both providers answer nothing at once.
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )) {
            shadowOf(manager).setProviderEnabled(provider, false)
        }
        TripService.onWatchdog(app)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (seen.count > 0 && System.nanoTime() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("trip_no_fix was not asked", seen.await(1, TimeUnit.SECONDS))
        val golden = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_no_fix.request.json").readText()
        val sent = org.json.JSONObject(checkNotNull(request))
        val wanted = org.json.JSONObject(golden.replace("\$DIR", dir))
        assertEquals(wanted.keys().asSequence().toSet(), sent.keys().asSequence().toSet())
        for (key in listOf("dir", "now_ms", "elapsed_ms")) assertEquals(wanted.get(key), sent.get(key))
        awaitUntil { TripPrefs.load(app)?.nextFixMs == 300_000L }
        // The status stays "searching": the answer is not a fix.
        assertTrue(statuses().all { TripStatus.parse(it) == TripStatus.Searching })
        assertTrue(statuses().isNotEmpty())
        TripClock.clock = dev.rosty.design.time.SystemRostyClock
        TripClock.elapsed = { android.os.SystemClock.elapsedRealtime() }
        controller.destroy()
    }

    @Test fun aStartWithNoSavedTripAndNoSpecLeavesWithoutClearing() {
        val controller =
            Robolectric
                .buildService(
                    TripService::class.java,
                    Intent(app, TripService::class.java),
                ).create()
        controller.startCommand(0, 1)
        // It still promised a foreground start (startForegroundService requires one), then left.
        assertEquals(TripService.NOTIFICATION_ID, shadowOf(controller.get()).lastForegroundNotificationId)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertNull(TripPrefs.load(app))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun theStopActionEndsTheTripAndReportsStopped() {
        val controller = Robolectric.buildService(TripService::class.java, startIntent()).create()
        controller.startCommand(0, 1)
        controller.get().onStartCommand(Intent(TripService.ACTION_STOP).setClass(app, TripService::class.java), 0, 2)
        assertNull(TripPrefs.load(app))
        assertEquals(listOf("""{"state":"stopped"}"""), statuses())
        controller.destroy()
    }

    @Test fun thePureHelpersKeepTheirBounds() {
        assertEquals(1_000L, tripIntervalMs(5))
        assertEquals(300_000L, tripIntervalMs(9_999_999))
        assertEquals(10_000L + 5_000L, tripWatchdogMs(10_000L))
        assertEquals(300_000L + 30_000L, tripWatchdogMs(300_000L))
        assertEquals(LocationManagerGps, tripProvider(true, true))
        assertEquals(LocationManager.FUSED_PROVIDER, tripProvider(false, true))
        assertEquals(LocationManager.NETWORK_PROVIDER, tripProvider(false, false))
        assertTrue(tripStartIsBackground(null, 0, false))
        assertTrue(tripStartIsBackground(null, android.app.Service.START_FLAG_REDELIVERY, true))
        assertTrue(!tripStartIsBackground(null, 0, true))
        assertTrue(Build.VERSION.SDK_INT >= 31)
    }

    private val LocationManagerGps = LocationManager.GPS_PROVIDER

    private fun awaitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("condition not met in time", condition())
    }
}
