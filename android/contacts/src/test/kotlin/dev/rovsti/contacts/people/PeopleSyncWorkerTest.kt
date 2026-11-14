package dev.rovsti.contacts.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.WaProfile
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

/** X.1b: the periodic work (unique, constraints) and what one run of it does. */
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

    private fun work(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(PeopleSyncWorker.UNIQUE_NAME).get()

    private fun run(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<PeopleSyncWorker>(context).build().doWork() }

    private fun profile(
        jid: String,
        name: String,
        picture: String?,
    ) = WaProfile(jid, pushName = name, pictureId = picture, fetchedMs = 1)

    private fun script() {
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

    // ---- one run

    @Test fun withoutALinkNothingIsAskedAndNothingChanges() {
        script()
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, container.gw.gateway.core.calls.size)
        assertTrue(container.people.book.isEmpty())
        assertEquals(0, container.changes)
        assertEquals(SyncOutcome.Never, container.peopleSync.status.value.outcome)
    }

    @Test fun aLinkedRunFillsTheCacheAndDownloadsTheChangedPicturesOnly() {
        script()
        container.connect()
        assertEquals(ListenableWorker.Result.success(), run())

        val book = container.people.book
        assertEquals(setOf(ayse, can), book.keys)
        assertEquals("Ayse", book.getValue(ayse).name)
        assertEquals("+905321234567", book.getValue(ayse).numbers.single())
        assertEquals("a person who is not on WhatsApp is not kept", null, book[nobody])
        val file = File(container.dir, book.getValue(ayse).pictureFile!!)
        assertEquals("ayse-11", file.readText())
        assertEquals(
            "only Ayse has a picture to download",
            1,
            container.gw.pictureAsks.count {
                it.knownId == null &&
                    it.jid == ayse
            },
        )
        assertEquals(1, container.changes)
        assertEquals(SyncOutcome.Ok, container.peopleSync.status.value.outcome)
        assertEquals(2, container.peopleSync.status.value.people)

        // A second run: the numbers are known, the picture is on disk and current, Can's "no picture" is fresh.
        container.gw.lookups.clear()
        container.gw.pictureAsks.clear()
        container.changes = 0
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals("only the unknown number is asked about again", listOf(listOf(nobody)), container.gw.lookups)
        assertEquals(
            "no picture is downloaded twice",
            emptyList<Any>(),
            container.gw.pictureAsks.filter {
                it.jid ==
                    ayse
            },
        )
        assertEquals("nothing changed, nobody is told", 0, container.changes)
    }

    @Test fun lookupsGoInBatchesOfFifty() {
        script()
        container.numbers = (1..120).map { "+90532000%04d".format(it) }
        container.connect()
        run()
        assertEquals(listOf(50, 50, 20), container.gw.lookups.map { it.size })
    }

    @Test fun aNewPictureIdReplacesTheFileAndTheOldOneIsDropped() {
        script()
        container.connect()
        run()
        val old =
            container.people.book
                .getValue(ayse)
                .pictureFile!!
        container.gw.profiles[ayse] = profile(ayse, "Ayse", "12")
        container.gw.pictures[ayse] = PictureAnswer("image", id = "12", dataB64 = b64("ayse-12"))
        container.gw.pictureAsks.clear()
        assertEquals(ListenableWorker.Result.success(), run())
        val now =
            container.people.book
                .getValue(ayse)
                .pictureFile!!
        assertEquals("ayse-12", File(container.dir, now).readText())
        assertFalse("the old picture is deleted", File(container.dir, old).exists())
        assertEquals(1, container.gw.pictureAsks.size)
    }

    @Test fun aRemovedPictureIsDropped() {
        script()
        container.connect()
        run()
        val file =
            File(
                container.dir,
                container.people.book
                    .getValue(ayse)
                    .pictureFile!!,
            )
        assertTrue(file.exists())
        container.gw.profiles[ayse] = profile(ayse, "Ayse", null)
        assertEquals(ListenableWorker.Result.success(), run())
        assertNull(
            container.people.book
                .getValue(ayse)
                .pictureFile,
        )
        assertFalse("the file of a removed picture is deleted", file.exists())
    }

    @Test fun aStrayFileNobodyHoldsIsDeleted() {
        script()
        container.connect()
        container.dir.mkdirs()
        val stray = File(container.dir, "stray~1.jpg").apply { writeText("x") }
        run()
        assertFalse(stray.exists())
    }

    @Test fun aGatewayErrorAsksForARetry() {
        script()
        container.connect()
        container.link.gatewayOpener = { null }
        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(SyncOutcome.Failed, container.peopleSync.status.value.outcome)
    }

    @Test fun theNumbersOfTheRunDoNotNeedTheLinkToBeStarted() {
        // WorkManager can wake the process with no UI: the worker alone builds the sync, which starts following the link.
        assertEquals(SyncOutcome.Never, container.peopleSync.status.value.outcome)
        assertEquals(false, container.peopleSync.status.value.linked)
    }
}
