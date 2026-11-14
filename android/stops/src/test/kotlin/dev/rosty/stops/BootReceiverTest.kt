package dev.rosty.stops

import android.content.Intent
import dev.rosty.platform.Headless
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestStopsApp::class)
class BootReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication() as TestStopsApp

    @After fun reset() = Headless.useCore(null)

    @Test fun theRequestEndsTheTripAsCancelled() {
        val json = JSONObject(BootReceiver.endRequest("/data/no_backup"))
        assertEquals("/data/no_backup", json.getString("dir"))
        assertEquals("cancelled", json.getString("reason"))
    }

    @Test fun aRebootEndsTheStaleTripThroughTheHeadlessCall() {
        val asked = CountDownLatch(1)
        val requests = mutableListOf<String>()
        app.container.fake.core.onHeadless(BootReceiver.KIND) {
            requests += it
            asked.countDown()
            """{"state":"ended"}"""
        }
        Headless.useCore(app.container.fake.core)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue("the headless call was made", asked.await(5, TimeUnit.SECONDS))
        val req = JSONObject(requests.single())
        assertEquals(app.noBackupFilesDir.absolutePath, req.getString("dir"))
        assertEquals("cancelled", req.getString("reason"))
    }

    @Test fun otherBroadcastsAreIgnored() {
        val requests = mutableListOf<String>()
        app.container.fake.core.onHeadless(BootReceiver.KIND) {
            requests += it
            ""
        }
        Headless.useCore(app.container.fake.core)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        Thread.sleep(200)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(requests.isEmpty())
    }
}
