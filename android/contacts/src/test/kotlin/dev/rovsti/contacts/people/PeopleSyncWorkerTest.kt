package dev.rovsti.contacts.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.people.sync.PeopleSyncRunner
import dev.rovsti.people.sync.SliceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** X.1b and people sync v2 S6: the periodic work, the continuation, and what one slice of it does (real planner). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncWorkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = (context as PeopleTestApp).container

    private val ayse = "905321234567@s.whatsapp.net"
    private val can = "905559876543@s.whatsapp.net"
    private val nobody = "905550000000@s.whatsapp.net"

    @Before fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun work(name: String = PeopleSyncWorker.UNIQUE_NAME): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun run(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<PeopleSyncWorker>(context).build().doWork() }

    private fun profile(
        jid: String,
        name: String,
        picture: String?,
    ) = WaProfile(jid, pushName = name, pictureId = picture, fetchedMs = 1)

    private fun script() {
        container.useRealPeople()
        container.numbers = listOf("+905321234567", "+905559876543", "+905550000000")
        container.gw.onWhatsapp += listOf(ayse, can)
        container.gw.profiles[ayse] = profile(ayse, "Ayse", "11")
        container.gw.profiles[can] = profile(can, "Can", null)
        container.gw.pictures[ayse] = PictureAnswer("image", id = "11", dataB64 = b64("ayse-11"))
    }

    // ---- the schedule

    @Test fun theWorkIsUniquePeriodicEverySixHoursOnANetworkWithAFineBattery() {
        PeopleSyncWorker.schedule(context)
        PeopleSyncWorker.schedule(context)
        val infos = work()
        assertEquals("a second schedule keeps the first (KEEP)", 1, infos.size)
        val info = infos.single()
        assertEquals(TimeUnit.HOURS.toMillis(6), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertFalse(info.constraints.requiresCharging())
    }

    @Test fun keepLeavesTheRunningScheduleAlone() {
        PeopleSyncWorker.schedule(context)
        val id = work().single().id
        PeopleSyncWorker.schedule(context)
        assertEquals(id, work().single().id)
    }

    @Test fun theContinuationIsOneTimeOnANetworkAndReplacesAnOlderOne() {
        PeopleSyncWorker.continueLater(context, 60_000)
        PeopleSyncWorker.continueLater(context, 120_000)
        val infos = work(PeopleSyncWorker.CONTINUE_NAME).filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("REPLACE keeps one", 1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        assertNull(infos.single().periodicityInfo)
    }

    @Test fun whatTheNextSliceWaitsFor() {
        val now = 1_000_000L
        val gap = PeopleSyncRunner.CONTINUE_DELAY_MS
        assertNull(PeopleSyncWorker.continueInMs(SliceResult.Done, now))
        assertNull(PeopleSyncWorker.continueInMs(SliceResult.NotLinked, now))
        assertNull(PeopleSyncWorker.continueInMs(SliceResult.AlreadyRunning, now))
        assertNull(
            "a failure waits for the next period",
            PeopleSyncWorker.continueInMs(SliceResult.Failed("auth", ""), now),
        )
        assertEquals(600_000L, PeopleSyncWorker.continueInMs(SliceResult.Waiting(now + 600_000), now))
        assertEquals("never sooner than the gap", gap, PeopleSyncWorker.continueInMs(SliceResult.Waiting(now + 5), now))
        assertEquals(gap, PeopleSyncWorker.continueInMs(SliceResult.Interrupted(SyncWire.SLICE_BUDGET), now))
        assertEquals(
            PeopleSyncWorker.OFFLINE_DELAY_MS,
            PeopleSyncWorker.continueInMs(SliceResult.Interrupted(SyncWire.NETWORK), now),
        )
        assertEquals(300_000L, PeopleSyncWorker.continueInMs(SliceResult.Refused(now + 300_000), now))
        assertNull(PeopleSyncWorker.continueInMs(SliceResult.Refused(null), now))
    }

    // ---- one slice

    @Test fun withoutALinkNothingIsAskedAndNothingChanges() {
        script()
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, container.gw.gateway.core.calls.size)
        assertTrue(container.book().isEmpty())
        assertEquals(0, container.changes)
        assertEquals(PeopleSyncStatus.NotLinked, container.peopleSync.status.value)
        assertTrue("no continuation", work(PeopleSyncWorker.CONTINUE_NAME).isEmpty())
    }

    @Test fun aLinkedRunFillsTheCacheAndDownloadsTheChangedPicturesOnly() {
        script()
        container.connect()
        assertEquals(ListenableWorker.Result.success(), run())

        val book = container.book()
        assertEquals(setOf(ayse, can), book.keys)
        assertEquals("Ayse", book.getValue(ayse).name)
        assertEquals("+905321234567", book.getValue(ayse).numbers.single())
        assertEquals("a person who is not on WhatsApp is not kept", null, book[nobody])
        val file = File(container.dir, book.getValue(ayse).pictureFile!!)
        assertEquals("ayse-11", file.readText())
        assertEquals(1, container.gw.pictureAsks.count { it.knownId == null && it.jid == ayse })
        assertTrue("every part was told", container.changes >= 1)
        val status = container.awaitStatus { it is PeopleSyncStatus.Idle && it.lastMs != null }
        assertTrue(status is PeopleSyncStatus.Idle && status.lastMs != null)
        assertEquals(2, (status as PeopleSyncStatus.Idle).people)
        assertTrue("done: no continuation", work(PeopleSyncWorker.CONTINUE_NAME).isEmpty())

        // A second slice right away: an incremental run asks nothing (fresh negatives, fresh profiles, current files).
        container.gw.lookups.clear()
        container.gw.pictureAsks.clear()
        container.changes = 0
        container.now += 60_000
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(emptyList<List<String>>(), container.gw.lookups)
        assertEquals("no picture is downloaded twice", 0, container.gw.pictureAsks.count { it.jid == ayse })
        assertEquals("nothing changed, nobody is told", 0, container.changes)
    }

    @Test fun lookupsGoInSmallBatchesAGapApart() {
        script()
        container.numbers = (1..40).map { "+90532000%04d".format(it) }
        container.connect()
        run()
        assertEquals(listOf(15, 15, 10), container.gw.lookups.map { it.size })
        assertTrue("paced: no burst", container.pauses.count { it >= 16_000 } >= 2)
    }

    @Test fun aNewPictureIdReplacesTheFileAndTheOldOneIsDropped() {
        script()
        container.connect()
        run()
        val old = container.book().getValue(ayse).pictureFile!!
        container.gw.profiles[ayse] = profile(ayse, "Ayse", "12")
        container.gw.pictures[ayse] = PictureAnswer("image", id = "12", dataB64 = b64("ayse-12"))
        container.gw.pictureAsks.clear()
        // Profiles are asked again once a day.
        container.now += 25L * 3_600_000
        assertEquals(ListenableWorker.Result.success(), run())
        val now = container.book().getValue(ayse).pictureFile!!
        assertEquals("ayse-12", File(container.dir, now).readText())
        assertFalse("the old picture is deleted", File(container.dir, old).exists())
        assertEquals(1, container.gw.pictureAsks.count { it.jid == ayse })
    }

    @Test fun aRemovedPictureIsDropped() {
        script()
        container.connect()
        run()
        val file = File(container.dir, container.book().getValue(ayse).pictureFile!!)
        assertTrue(file.exists())
        container.gw.profiles[ayse] = profile(ayse, "Ayse", null)
        container.now += 25L * 3_600_000
        assertEquals(ListenableWorker.Result.success(), run())
        assertNull(container.book().getValue(ayse).pictureFile)
        assertFalse("the file of a removed picture is deleted", file.exists())
    }

    @Test fun aStrayFileOfAnOldPictureIsDeleted() {
        script()
        container.connect()
        container.dir.mkdirs()
        val stray = File(container.dir, "905321234567_s.whatsapp.net~10.jpg").apply { writeText("x") }
        run()
        assertFalse(stray.exists())
    }

    @Test fun noGatewayInterruptsAndContinuesLaterWithoutAFailure() {
        script()
        container.connect()
        container.link.gatewayOpener = { null }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(1, work(PeopleSyncWorker.CONTINUE_NAME).size)
        assertEquals(PeopleSyncStatus.Idle(null), container.awaitStatus { it is PeopleSyncStatus.Idle })
    }

    @Test fun aLinkNotReadInTimeRetriesWithoutRecordingAnything() {
        script()
        container.link.setSettled(false)
        val result = runBlocking { container.peopleSync.runSlice() }
        assertEquals(SliceResult.LinkNotReady, result)
        assertEquals(0, container.gw.gateway.core.calls.size)
        assertEquals(PeopleSyncStatus.NotLinked, container.peopleSync.status.value)
    }

    @Test fun profilesAnOldGatewayDidNotAnswerKeepTheRunGoing() {
        script()
        container.gw.profiles[can] = WaProfile(can, pushName = "Can")
        container.connect()
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals("Ayse", container.book().getValue(ayse).name)
        assertTrue(
            "the legacy throttle leaves a resumable run",
            container.awaitStatus { it is PeopleSyncStatus.RateLimited } is PeopleSyncStatus.RateLimited,
        )
        assertEquals(1, work(PeopleSyncWorker.CONTINUE_NAME).size)
    }

    @Test fun theNumbersOfTheRunDoNotNeedTheLinkToBeStarted() {
        // WorkManager can wake the process with no UI: the worker alone builds the sync, which starts following the link.
        assertEquals(PeopleSyncStatus.NotLinked, container.peopleSync.status.value)
    }
}
