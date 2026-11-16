package dev.rosty.contacts.people

import androidx.test.core.app.ApplicationProvider
import dev.rosty.core.Outcome
import dev.rosty.core.model.people.PeopleBundleRecord
import dev.rosty.core.model.people.PeopleJobResult
import dev.rosty.core.model.people.PeopleJobStatus
import dev.rosty.core.model.people.PeopleJobWire
import dev.rosty.core.model.people.PeopleSyncBundleAnswer
import dev.rosty.core.model.people.PeopleSyncStartAnswer
import dev.rosty.core.model.people.PeopleSyncStatusAnswer
import dev.rosty.core.model.people.SyncWire
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.people.sync.SliceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * People sync v3, step 7: the runner performs the planner's job steps against the gateway's people job (the real
 * planner on the host library, a scripted gateway). The gateway prepares, the phone downloads once; an unchanged run
 * writes nothing; the user's Stop cancels the job on the gateway, the system's stop does not; an old gateway without
 * the job routes falls back to the v2 steps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncJobTest {
    private val container get() = ApplicationProvider.getApplicationContext<PeopleTestApp>().container
    private val sync get() = container.peopleSync
    private val gw get() = container.gw.gateway

    private val jobId = "5f0c9a1b22d4e7a3"
    private val numbers = listOf("+100000000001", "+100000000002", "+100000000003")
    private val ada = "100000000001@s.whatsapp.net"
    private val bo = "100000000002@s.whatsapp.net"
    private val temp: File = Files.createTempDirectory("people-bundle").toFile().apply { deleteOnExit() }

    @Before fun script() {
        container.useRealPeople()
        container.numbers = numbers
        container.connect()
    }

    private fun status(
        state: String,
        phase: String,
        done: Int,
        total: Int,
        rev: Long,
        id: String = jobId,
        result: PeopleJobResult? = null,
        etaMs: Long? = null,
    ) = PeopleJobStatus(
        jobId = id,
        rev = rev,
        state = state,
        phase = phase,
        done = done,
        total = total,
        numbers = numbers.size,
        etaMs = etaMs,
        result = result,
    )

    private fun ready(
        rev: Long,
        records: Int,
        id: String = jobId,
    ) = status(PeopleJobWire.READY, PeopleJobWire.PHASE_BUNDLE, 3, 3, rev, id, PeopleJobResult(records, 1, 70))

    /** The full picture the gateway op wrote to the cache; the runner downscales, stores and deletes it. */
    private fun pictureFile(text: String): File = File(temp, "$jobId-1.bin").apply { writeText(text) }

    private fun firstBundle(picture: File) =
        PeopleSyncBundleAnswer(
            records =
                listOf(
                    PeopleBundleRecord(
                        seq = 1,
                        number = numbers[0],
                        hash = "1111111111111111",
                        onWhatsapp = true,
                        jid = ada,
                        pushName = "Ada",
                        pictureId = "11",
                        picturePath = picture.absolutePath,
                    ),
                    PeopleBundleRecord(
                        seq = 2,
                        number = numbers[1],
                        hash = "2222222222222222",
                        onWhatsapp = true,
                        jid = bo,
                        pushName = "Bo",
                    ),
                    PeopleBundleRecord(seq = 3, number = numbers[2], hash = "3333333333333333", onWhatsapp = false),
                ),
            lastSeq = 3,
            more = false,
            bytes = 70,
        )

    /** A first job: started, two watches, one bundle part, closed. Returns the temp picture file. */
    private fun firstRun(): File {
        val picture = pictureFile("ada-11")
        gw.peopleStart = {
            Outcome.Ok(PeopleSyncStartAnswer(true, status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_LOOKUP, 0, 3, 1)))
        }
        val watches =
            ArrayDeque(
                listOf(
                    status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_PROFILES, 1, 2, 2),
                    ready(3, records = 3),
                ),
            )
        gw.peopleStatus = { Outcome.Ok(PeopleSyncStatusAnswer(status = watches.removeFirstOrNull() ?: ready(3, 3))) }
        gw.peopleBundle = { Outcome.Ok(firstBundle(picture)) }
        assertEquals(SliceResult.Done, runBlocking { sync.run(SyncWire.START_RESTART, null) })
        return picture
    }

    @Test fun aJobRunIsPreparedOnTheGatewayAndDownloadedOnce() {
        val picture = firstRun()

        val start = gw.peopleStarts.single()
        assertEquals("Sync Now asks everything again", PeopleJobWire.MODE_FULL, start.mode)
        assertEquals(numbers, start.entries.map { it.n })
        assertTrue("nothing synced yet: no hashes", start.entries.all { it.h == null && it.p == null })
        assertEquals("the long-poll follows the revision", listOf(1L, 2L), gw.peopleWatches.map { it.rev })
        assertTrue(gw.peopleWatches.all { (it.waitMs ?: 0) > 0 })
        assertEquals("one bundle request", listOf(0), gw.peopleFetches.map { it.after })
        assertEquals("the finished job is closed", listOf(jobId), gw.peopleCancels.toList())
        assertTrue("no per-contact call", container.gw.lookups.isEmpty() && container.gw.pictureAsks.isEmpty())

        val book = container.book()
        assertEquals(setOf(ada, bo), book.keys)
        assertEquals("Ada", book.getValue(ada).name)
        assertEquals("Bo", book.getValue(bo).name)
        assertEquals("ada-11", File(container.dir, book.getValue(ada).pictureFile!!).readText())
        assertFalse("the temp picture is deleted", picture.exists())
        assertTrue("the readers were told", container.changes >= 1)

        val idle = container.awaitStatus { it is PeopleSyncStatus.Idle && it.lastMs != null } as PeopleSyncStatus.Idle
        assertEquals(2, idle.people)
        assertTrue("the run counts its changes: ${idle.changed}", (idle.changed ?: 0) >= 2)
    }

    @Test fun anUnchangedRunSendsTheHashesAndWritesNothing() {
        firstRun()
        val peopleJson = File(container.peopleData, "people.json")
        assertTrue(peopleJson.isFile)
        peopleJson.setLastModified(1_000_000L)
        val written = peopleJson.readText()
        val file = File(container.dir, container.book().getValue(ada).pictureFile!!)
        container.changes = 0
        container.now += 60_000

        val second = "7a3e5f0c9a1b22d4"
        gw.peopleStart = { Outcome.Ok(PeopleSyncStartAnswer(true, ready(9, records = 0, id = second))) }
        gw.peopleBundle =
            { Outcome.Ok(PeopleSyncBundleAnswer(records = emptyList(), lastSeq = 3, more = false, bytes = 0)) }
        assertEquals(SliceResult.Done, runBlocking { sync.run(SyncWire.START_AUTO, null) })

        val start = gw.peopleStarts.last()
        assertEquals(PeopleJobWire.MODE_AUTO, start.mode)
        val entries = start.entries.associateBy { it.n }
        assertEquals("1111111111111111", entries.getValue(numbers[0]).h)
        assertEquals("the picture on disk is named", "11", entries.getValue(numbers[0]).p)
        assertEquals("2222222222222222", entries.getValue(numbers[1]).h)
        assertNull(entries.getValue(numbers[1]).p)
        assertEquals("a ready job is downloaded without a watch", 2, gw.peopleWatches.size)
        assertEquals(listOf(jobId, second), gw.peopleCancels.toList())

        assertEquals("nobody is told", 0, container.changes)
        assertEquals("people.json is not written", 1_000_000L, peopleJson.lastModified())
        assertEquals(written, peopleJson.readText())
        assertTrue("the picture stays", file.isFile)
        val idle = container.awaitStatus { it is PeopleSyncStatus.Idle && it.changed == 0 }
        assertEquals("Nothing changed", 0, (idle as PeopleSyncStatus.Idle).changed)
    }

    @Test fun theUsersStopCancelsTheJobOnTheGatewayAndTheLongPollReturnsAtOnce() {
        val watching = CompletableDeferred<Unit>()
        gw.peopleStart = {
            Outcome.Ok(PeopleSyncStartAnswer(true, status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_LOOKUP, 0, 3, 1)))
        }
        gw.peopleStatus = {
            watching.complete(Unit)
            // The gateway's long-poll: it answers when the job changes, here because the cancel arrives.
            val deadline = System.currentTimeMillis() + 10_000
            while (gw.peopleCancels.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            Outcome.Ok(
                PeopleSyncStatusAnswer(status = status(PeopleJobWire.CANCELLED, PeopleJobWire.PHASE_LOOKUP, 0, 3, 2)),
            )
        }
        val began = System.currentTimeMillis()
        runBlocking {
            val slice = async(Dispatchers.Default) { sync.run(SyncWire.START_RESTART, null) }
            withTimeout(10_000) { watching.await() }
            sync.stop()
            val result = runCatching { slice.await() }
            assertTrue("cancelled: ${result.exceptionOrNull()}", result.isFailure)
        }
        assertTrue("the long-poll did not run out", System.currentTimeMillis() - began < 9_000)
        assertEquals(listOf(jobId), gw.peopleCancels.toList())
        val run = runBlocking { sync.runner.refresh() }!!.run!!
        assertEquals(SyncWire.INTERRUPTED, run.phase.kind)
        assertEquals(SyncWire.CANCELLED, run.phase.reason)
    }

    @Test fun aStopByTheSystemLeavesTheJobWorkingAndTheNextSliceFollowsItById() {
        val watching = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        gw.peopleStart = {
            Outcome.Ok(PeopleSyncStartAnswer(true, status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_LOOKUP, 0, 3, 1)))
        }
        gw.peopleStatus = {
            watching.complete(Unit)
            release.await(10, TimeUnit.SECONDS)
            Outcome.Ok(
                PeopleSyncStatusAnswer(status = status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_PROFILES, 1, 2, 2)),
            )
        }
        runBlocking {
            val slice = async(Dispatchers.Default) { sync.run(SyncWire.START_RESTART, null) }
            withTimeout(10_000) { watching.await() }
            slice.cancel()
            release.countDown()
            runCatching { slice.await() }
        }
        assertTrue("the system's stop does not cancel the job", gw.peopleCancels.isEmpty())
        val run = runBlocking { sync.runner.refresh() }!!.run!!
        assertEquals(SyncWire.INTERRUPTED, run.phase.kind)
        assertEquals(SyncWire.SLICE_BUDGET, run.phase.reason)
        assertEquals(jobId, run.job?.jobId)

        // The continuation follows the same job: no second start.
        val picture = pictureFile("ada-11")
        gw.peopleStatus = { Outcome.Ok(PeopleSyncStatusAnswer(status = ready(4, records = 3))) }
        gw.peopleBundle = { Outcome.Ok(firstBundle(picture)) }
        assertEquals(SliceResult.Done, runBlocking { sync.run(SyncWire.START_AUTO, null) })
        assertEquals(1, gw.peopleStarts.size)
        assertEquals(jobId, gw.peopleWatches.last().jobId)
        assertEquals("Ada", container.book().getValue(ada).name)
    }

    @Test fun aBoundedSliceEndsWhileTheGatewayWorksAndComesBackAtItsEta() {
        var rev = 1L
        gw.peopleStart = {
            Outcome.Ok(
                PeopleSyncStartAnswer(true, status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_LOOKUP, 0, 3, rev)),
            )
        }
        gw.peopleStatus = {
            // Each long-poll takes its time on the sync's clock.
            container.now += 30_000
            rev++
            Outcome.Ok(
                PeopleSyncStatusAnswer(
                    status = status(PeopleJobWire.RUNNING, PeopleJobWire.PHASE_PROFILES, 1, 2, rev, etaMs = 240_000),
                ),
            )
        }
        val result = runBlocking { sync.run(SyncWire.START_AUTO, 60_000) }
        assertEquals(SliceResult.Interrupted(SyncWire.SERVER_WORKING, 240_000), result)
        assertEquals(240_000L, PeopleSyncWorker.continueInMs(result, container.now))
        assertTrue("the job goes on without the phone", gw.peopleCancels.isEmpty())
        val row = container.awaitStatus { it is PeopleSyncStatus.Preparing }
        assertEquals(
            PeopleSyncStatus.Preparing(PeopleJobWire.PHASE_PROFILES, 1, 2, null, null, watched = false),
            row,
        )
    }

    @Test fun anOldGatewayWithoutTheJobRoutesFallsBackToTheV2Steps() {
        // FakeWaGateway's default start answer: no job routes (the bare 404 of an old gateway).
        container.gw.onWhatsapp += ada
        container.gw.profiles[ada] = WaProfile(ada, pushName = "Ada", fetchedMs = 1)
        assertEquals(SliceResult.Done, runBlocking { sync.run(SyncWire.START_RESTART, null) })
        assertEquals(1, gw.peopleStarts.size)
        assertTrue(
            "looked up per batch",
            container.gw.lookups
                .flatten()
                .containsAll(listOf(ada, bo)),
        )
        assertEquals("Ada", container.book().getValue(ada).name)
        assertNotNull(runBlocking { sync.runner.refresh() }?.last)

        // The gateway is not asked again for a day.
        container.now += 60_000
        assertEquals(SliceResult.Done, runBlocking { sync.run(SyncWire.START_RESTART, null) })
        assertEquals(1, gw.peopleStarts.size)
    }
}
