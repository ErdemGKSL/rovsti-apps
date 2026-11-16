package dev.rosty.contacts.people

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.rosty.core.Outcome
import dev.rosty.core.model.people.PeopleJobResult
import dev.rosty.core.model.people.PeopleJobStatus
import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.model.people.PeopleSyncBundleAnswer
import dev.rosty.core.model.people.PeopleSyncStartAnswer
import dev.rosty.core.model.people.PeopleSyncStatusAnswer
import dev.rosty.core.model.people.SyncWire
import dev.rosty.core.model.wa.PictureAnswer
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.people.sync.ForegroundPeopleSyncWorker
import dev.rosty.people.sync.SyncNotices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * People sync v2 S11: the worker as a foreground service on the real planner. One run goes to the end without the
 * 8-minute budget, waits out a short WhatsApp limit itself and leaves a long one to a continuation; the Stop button
 * keeps the checkpoint and schedules nothing, a stop by the system keeps it and continues later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncForegroundTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = (context as PeopleTestApp).container
    private val sync get() = container.peopleSync

    private fun jid(i: Int) = "90532000%04d@s.whatsapp.net".format(i)

    @Before fun script() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        container.useRealPeople()
        container.numbers = (1..30).map { "+90532000%04d".format(it) }
        for (i in 1..30 step 3) {
            container.gw.onWhatsapp += jid(i)
            container.gw.profiles[jid(i)] = WaProfile(jid(i), pushName = "P$i", pictureId = "$i", fetchedMs = 1)
            container.gw.pictures[jid(i)] = PictureAnswer("image", id = "$i", dataB64 = b64("p$i"))
        }
        container.connect()
    }

    @After fun noHook() {
        container.pauseHook = null
    }

    private fun worker(start: String = SyncWire.START_RESTART) =
        TestListenableWorkerBuilder<PeopleSyncWorker>(context)
            .setInputData(workDataOf(ForegroundPeopleSyncWorker.KEY_START to start))
            .build()

    private fun continuations(): List<WorkInfo> =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(PeopleSyncWorker.CONTINUE_NAME)
            .get()
            .filter { it.state == WorkInfo.State.ENQUEUED }

    private fun stored() =
        runBlocking { sync.runner.refresh() }
            ?.run

    @Test fun aForegroundRunWaitsOutAShortLimitAndFinishesWithoutAContinuation() {
        var refused = 0
        val known = container.gw.profiles.toMap()
        container.gw.gateway.profiles = { jids ->
            // 150 s: longer than the planner sleeps inline (90 s), shorter than the worker's 5 minutes.
            if (refused++ == 0) {
                rateLimited(retryAfterMs = 150_000, source = "whatsapp")
            } else {
                Outcome.Ok(jids.mapNotNull(known::get))
            }
        }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        assertEquals("everyone's name arrived in one run", 10, container.book().values.count { it.name != null })
        assertTrue("the worker waited the limit out", container.pauses.any { it >= 150_000 })
        assertTrue("done: nothing continues later", continuations().isEmpty())
        assertTrue("finished", runBlocking { sync.runner.refresh() }?.last != null)
    }

    @Test fun aLongLimitEndsTheForegroundRunAndContinuesLater() {
        container.gw.gateway.profiles = { rateLimited(retryAfterMs = 20 * 60_000L, source = "whatsapp") }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        assertEquals(1, continuations().size)
        assertTrue("no wait of 20 minutes inside the worker", container.pauses.none { it >= 5 * 60_000L })
        assertEquals(SyncWire.WAITING, stored()!!.phase.kind)
    }

    @Test fun stopKeepsTheCheckpointAndSchedulesNothing() {
        container.pauseHook = { sync.stop() }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        val run = stored()!!
        assertEquals(SyncWire.INTERRUPTED, run.phase.kind)
        assertEquals(SyncWire.CANCELLED, run.phase.reason)
        assertTrue("Continue resumes it", run.resumable)
        assertTrue("a Stop is not undone by a continuation", continuations().isEmpty())
    }

    @Test fun stopDuringTheWaitForALimitStopsTheRunToo() {
        container.gw.gateway.profiles = { rateLimited(retryAfterMs = 150_000, source = "whatsapp") }
        container.pauseHook = { ms ->
            if (ms >= 150_000) {
                sync.stop()
                delay(500)
            }
        }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        val run = stored()!!
        assertEquals(SyncWire.INTERRUPTED, run.phase.kind)
        assertEquals(SyncWire.CANCELLED, run.phase.reason)
        assertTrue(continuations().isEmpty())
    }

    @Test fun aStopByTheSystemKeepsTheCheckpointAndContinuesLater() {
        // The dataSync time budget (Service.onTimeout) and lost constraints reach the worker as a cancellation.
        val held = CompletableDeferred<Unit>()
        container.pauseHook = {
            held.complete(Unit)
            awaitCancellation()
        }
        runBlocking {
            val work = async(Dispatchers.Default) { worker().doWork() }
            withTimeout(10_000) { held.await() }
            work.cancel()
            runCatching { work.await() }
        }
        val run = stored()!!
        assertEquals(SyncWire.INTERRUPTED, run.phase.kind)
        assertEquals("continues by itself, not 'stopped'", SyncWire.SLICE_BUDGET, run.phase.reason)
        assertTrue(run.resumable)
        assertEquals(1, continuations().size)
    }

    /**
     * People sync v3 with the owner's answer to Q1: an automatic run (the periodic work, `auto`) is foreground too, and
     * the notification follows what the gateway's job does while the slice long-polls it.
     */
    @Test fun anAutomaticRunIsForegroundAndTheNotificationFollowsTheGatewaysJob() {
        val numbers = (1..10).map { "+1000000000%02d".format(it) }
        container.numbers = numbers
        val job = "5f0c9a1b22d4e7a3"

        fun status(
            state: String,
            phase: String,
            done: Int,
            rev: Long,
        ) = PeopleJobStatus(
            jobId = job,
            rev = rev,
            state = state,
            phase = phase,
            done = done,
            total = 10,
            numbers = 10,
            result = PeopleJobResult(0, 0, 0).takeIf { state == PeopleJobWire.READY },
        )
        val gw = container.gw.gateway
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        gw.peopleStart = {
            Outcome.Ok(PeopleSyncStartAnswer(true, status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_LOOKUP, 4, 1)))
        }
        val watches =
            ArrayDeque(
                listOf(
                    status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_PROFILES, 2, 2),
                    status(PeopleJobWire.READY, PeopleJobWire.PHASE_BUNDLE, 10, 3),
                ),
            )
        gw.peopleStatus = { body ->
            // The long-poll takes a while; meanwhile the worker posts (at most every 2 s).
            Thread.sleep(2_500)
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(SyncNotices.NOTIFICATION_ID)
                ?.let { seen += it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() }
            Outcome.Ok(
                PeopleSyncStatusAnswer(
                    status =
                        watches.removeFirstOrNull()
                            ?: status(PeopleJobWire.READY, PeopleJobWire.PHASE_BUNDLE, 10, body.rev ?: 3),
                ),
            )
        }
        gw.peopleBundle =
            { Outcome.Ok(PeopleSyncBundleAnswer(records = emptyList(), lastSeq = 10, more = false, bytes = 0)) }

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(SyncWire.START_AUTO).doWork() })
        assertEquals(PeopleJobWire.MODE_AUTO, gw.peopleStarts.single().mode)
        assertTrue("$seen", seen.any { it == "Gateway is checking numbers 4/10" })
        assertTrue("done: nothing continues later", continuations().isEmpty())
    }

    @Test fun theNotificationShowsTheStageWhileTheRunGoes() {
        container.numbers = (1..60).map { "+90532000%04d".format(it) }
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        container.pauseHook = {
            // The worker posts at most every 2 s: "Starting…" first, then the stage once something is counted.
            delay(1_000)
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(SyncNotices.NOTIFICATION_ID)
                ?.let { seen += it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() }
        }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        val words = Regex("""Checking numbers \d+/60|Getting names \d+/\d+|Downloading photos \d+/\d+""")
        assertTrue("$seen", seen.any(words::matches))
    }
}
