package dev.rovsti.contacts.people

import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.people.sync.SliceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * People sync v2: a run survives being stopped, cut off or killed, and continues without asking anything twice; one
 * slice at a time (real planner, the runner of `:people`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncResumeTest {
    private val container get() = ApplicationProvider.getApplicationContext<PeopleTestApp>().container
    private val sync get() = container.peopleSync

    private fun jid(i: Int) = "90532000%04d@s.whatsapp.net".format(i)

    @Before fun script() {
        container.useRealPeople()
        container.numbers = (1..45).map { "+90532000%04d".format(it) }
        for (i in 1..45 step 2) {
            container.gw.onWhatsapp += jid(i)
            container.gw.profiles[jid(i)] = WaProfile(jid(i), pushName = "P$i", fetchedMs = 1)
        }
        container.connect()
    }

    /** Lookups the gateway answered, flattened. */
    private fun asked() = container.gw.lookups.flatten()

    @Test fun stopMidRunThenContinueAsksNothingTwice() {
        // The slice is held in the pace gap after the first lookup batch, and stopped there.
        val waiting = CompletableDeferred<Unit>()
        container.pauseHook = {
            waiting.complete(Unit)
            awaitCancellation()
        }
        runBlocking {
            val slice = async(Dispatchers.Default) { sync.run(SyncWire.START_RESTART, null) }
            withTimeout(10_000) { waiting.await() }
            sync.stop()
            val result = runCatching { slice.await() }
            assertTrue("cancelled: ${result.exceptionOrNull()}", result.isFailure)
        }
        container.pauseHook = null
        val stored =
            sync.runner.status.value.stored
                ?.run!!
        assertEquals(SyncWire.INTERRUPTED, stored.phase.kind)
        assertEquals(SyncWire.CANCELLED, stored.phase.reason)
        assertTrue(stored.resumable)
        val shown = container.awaitStatus { it is PeopleSyncStatus.Interrupted }
        assertTrue("$shown", shown is PeopleSyncStatus.Interrupted)
        assertEquals("the first batch is stored", 8, container.book().size)
        val firstBatch = asked().toSet()

        // Continue: the rest is asked, the first batch is not asked again.
        container.gw.lookups.clear()
        assertEquals(SliceResult.Done, runBlocking { sync.run(SyncWire.START_RESUME, null) })
        assertTrue(asked().none { it in firstBatch })
        assertEquals(30, asked().size)
        assertEquals(23, container.book().size)
        assertEquals(23, (container.awaitStatus { it is PeopleSyncStatus.Idle } as PeopleSyncStatus.Idle).people)
    }

    @Test fun aSliceCutByItsBudgetContinuesWhereItStopped() {
        // A slice deadline cuts the run after a few batches, as WorkManager's limit would.
        assertEquals(
            SliceResult.Interrupted(SyncWire.SLICE_BUDGET),
            runBlocking { sync.run(SyncWire.START_AUTO, 30_000) },
        )
        val firstSlice = asked().toSet()
        assertTrue(firstSlice.isNotEmpty() && firstSlice.size < 45)
        container.gw.lookups.clear()
        assertEquals(SliceResult.Done, runBlocking { sync.run(SyncWire.START_AUTO, null) })
        assertTrue(asked().none { it in firstSlice })
        assertEquals(45, firstSlice.size + asked().size)
    }

    @Test fun noConnectionInterruptsResumably() {
        container.gw.gateway.core
            .on("wa.lookup_users") { throw IOException("offline") }
        assertEquals(
            SliceResult.Interrupted(SyncWire.NETWORK),
            runBlocking { sync.run(SyncWire.START_RESTART, null) },
        )
        assertTrue(
            sync.runner.status.value.stored
                ?.run
                ?.resumable == true,
        )
    }

    @Test fun aSecondSliceWhileOneRunsIsRefusedAtOnce() {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        container.pauseHook = {
            inside.complete(Unit)
            release.await()
        }
        runBlocking {
            val first = async(Dispatchers.Default) { sync.run(SyncWire.START_RESTART, null) }
            withTimeout(10_000) { inside.await() }
            assertEquals(SliceResult.AlreadyRunning, sync.run(SyncWire.START_AUTO, null))
            container.pauseHook = null
            release.complete(Unit)
            assertEquals(SliceResult.Done, first.await())
        }
    }
}
