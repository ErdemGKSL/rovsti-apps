package dev.rosty.stops.offline

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.platform.notify.PlatformChannels
import dev.rosty.stops.TestStopsApp
import dev.rosty.stops.settings.FakeTilesOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * E7.10 S10: the offline download as a foreground worker. It runs the container's download to the end, shows its
 * progress on the map_download channel with Cancel, says how it went, and the notification's Cancel stops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestStopsApp::class)
class OfflineAreaWorkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = (context as TestStopsApp).container
    private val tiles = FakeTilesOps()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        container.tileOpener = { tiles.openHandle(it) }
    }

    private fun worker(area: MapArea? = FakeTilesOps.AREA) =
        TestListenableWorkerBuilder<OfflineAreaWorker>(context)
            .setInputData(area?.let(OfflineAreaWorker::dataOf) ?: workDataOf())
            .build()

    private fun text(id: Int): String? =
        shadowOf(manager)
            .getNotification(id)
            ?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()

    @Test fun anAreaDownloadsToTheEndAndSaysSo() {
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        assertEquals(FakeTilesOps.AREA.bbox, tiles.starts.single().bbox)
        assertEquals(OfflineState.Finished(4, 0, 4, cancelled = false), container.offline.state.value)
        assertEquals("Map area downloaded (4 tiles)", text(OfflineNotices.DONE_ID))
        val done = shadowOf(manager).getNotification(OfflineNotices.DONE_ID)!!
        assertEquals(PlatformChannels.MAP_DOWNLOAD, done.channelId)
        assertEquals(OfflineNotices.KIND.accent, done.color)
        assertTrue("the glass large icon", done.getLargeIcon() != null)
    }

    @Test fun progressShowsWithCancelAndTheNotificationsCancelStopsIt() {
        tiles.hold = true
        runBlocking {
            val work = async(Dispatchers.Default) { worker().doWork() }
            withTimeout(TIMEOUT) {
                while (container.offline.state.value !is OfflineState.Running ||
                    (container.offline.state.value as OfflineState.Running).done < tiles.progress.last()
                ) {
                    delay(10)
                }
            }
            withTimeout(TIMEOUT) { while (text(OfflineNotices.NOTIFICATION_ID) == null) delay(10) }
            val running = shadowOf(manager).getNotification(OfflineNotices.NOTIFICATION_ID)!!
            assertTrue("ongoing", running.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(
                "Cancel",
                running.actions
                    .single()
                    .title
                    .toString(),
            )
            OfflineCancelReceiver().onReceive(context, Intent(OfflineCancelReceiver.ACTION_CANCEL))
            assertEquals(ListenableWorker.Result.success(), withTimeout(TIMEOUT) { work.await() })
        }
        assertEquals(1, tiles.cancels)
        val end = container.offline.state.value as OfflineState.Finished
        assertTrue(end.cancelled)
        assertEquals("Download stopped: 2 of 4 tiles kept", text(OfflineNotices.DONE_ID))
    }

    @Test fun aSourceThatCannotOpenFailsWithTheCoresSentence() {
        container.tileOpener = { Outcome.Err(RustError.Io("disk full")) }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        assertEquals(OfflineState.Failed("disk full"), container.offline.state.value)
    }

    @Test fun workWithoutAnAreaFails() {
        assertEquals(ListenableWorker.Result.failure(), runBlocking { worker(area = null).doWork() })
        assertEquals(null, OfflineAreaWorker.areaOf(workDataOf("south" to 1.0)))
        assertEquals(FakeTilesOps.AREA, OfflineAreaWorker.areaOf(OfflineAreaWorker.dataOf(FakeTilesOps.AREA)))
    }

    @Test fun theProgressStepsFollowTheFraction() {
        assertEquals(0, progressSteps(OfflineState.Starting))
        assertEquals(50, progressSteps(OfflineState.Running(2, 4, 0.5f)))
        assertEquals(100, progressSteps(OfflineState.Running(9, 4, 2f)))
        assertEquals(100, progressSteps(OfflineState.Finished(4, 0, 4, false)))
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
