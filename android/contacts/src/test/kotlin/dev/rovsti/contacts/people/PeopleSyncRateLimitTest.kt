package dev.rovsti.contacts.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.WaProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * X.1b: the gateway's rate limit (lookups 60 then 1/s, profiles and pictures 120 then 2/s) paces the sync instead of
 * failing it. The fake buckets run on the container's clock, which every requested pause moves on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncRateLimitTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = (context as PeopleTestApp).container

    private val ayse = "905321234567@s.whatsapp.net"

    /** Ayse first, then 149 numbers nobody uses WhatsApp on. */
    private val numbers = listOf("+905321234567") + (1..149).map { "+90532000%04d".format(it) }
    private val jids = numbers.map { PeopleSync.jidOf(it)!! }

    @Before fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun run(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<PeopleSyncWorker>(context).build().doWork() }

    private fun script() {
        container.numbers = numbers
        container.gw.onWhatsapp += ayse
        container.gw.profiles[ayse] = WaProfile(ayse, pushName = "Ayse", pictureId = "11", fetchedMs = 1)
        container.gw.pictures[ayse] = PictureAnswer("image", id = "11", dataB64 = b64("ayse-11"))
        container.connect()
    }

    private fun bucket(
        burst: Int,
        perSec: Double,
        start: Int = burst,
    ) = FakeBucket(burst, perSec, { container.now }, start)

    private val outcome get() = container.peopleSync.status.value.outcome

    @Test fun aFirstSyncOfOneHundredFiftyNumbersIsPacedAndNeverRefused() {
        script()
        val lookups = bucket(60, 1.0)
        container.gw.lookupBucket = lookups
        container.gw.profileBucket = bucket(120, 2.0)

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(SyncOutcome.Ok, outcome)
        assertEquals("every number is looked up once", jids, container.gw.lookups.flatten())
        assertEquals("the pacing keeps under the allowance", 0, lookups.refused)
        assertTrue("the run waited for the refill", container.pauses.isNotEmpty())
        assertTrue(container.pauses.sum() <= PeopleSync.RUN_WAIT_MS)
        assertEquals(
            "Ayse",
            container.people.book
                .getValue(ayse)
                .name,
        )
        assertTrue(
            container.people.book
                .getValue(ayse)
                .pictureFile != null,
        )
    }

    @Test fun aRefusedLookupWaitsForTheRefillAndAsksAgain() {
        script()
        // Another app spent the allowance a moment ago.
        val lookups = bucket(60, 1.0, start = 0)
        container.gw.lookupBucket = lookups

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(SyncOutcome.Ok, outcome)
        assertTrue("the gateway refused at least once", lookups.refused > 0)
        assertEquals("every number is looked up once", jids, container.gw.lookups.flatten())
    }

    @Test fun aPersistentRateLimitKeepsTheProgressAndTheNextRunContinues() {
        script()
        // The first batch fits the burst, then nothing comes back.
        container.gw.lookupBucket = bucket(60, 0.0)

        assertEquals("a partial run retries soon", ListenableWorker.Result.retry(), run())

        assertEquals("partial progress is not a failure", SyncOutcome.Partial, outcome)
        assertEquals(listOf(jids.take(50)), container.gw.lookups)
        assertEquals(
            "what was found is kept, with its profile",
            "Ayse",
            container.people.book
                .getValue(ayse)
                .name,
        )
        assertTrue(container.pauses.sum() <= PeopleSync.LOOKUP_WAIT_MS)

        // The limit is gone: the numbers never asked go first, the ones the partial run asked go last.
        container.gw.lookupBucket = null
        container.gw.lookups.clear()
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(SyncOutcome.Ok, outcome)
        assertEquals(jids.drop(50).take(50), container.gw.lookups.first())
        assertEquals(
            "every unknown number is asked once",
            (jids.drop(50) + jids.drop(1).take(49)).toSet(),
            container.gw.lookups
                .flatten()
                .toSet(),
        )
        assertEquals(
            149,
            container.gw.lookups
                .flatten()
                .size,
        )
    }

    @Test fun aRateLimitBeforeAnyAnswerIsAFailureButNoCrash() {
        script()
        container.gw.lookupBucket = bucket(60, 0.0, start = 0)

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals(SyncOutcome.Failed, outcome)
        assertTrue(container.people.book.isEmpty())
        assertTrue("the waits stay inside the budget", container.pauses.sum() <= PeopleSync.LOOKUP_WAIT_MS)
    }

    @Test fun aRateLimitedPictureWaitsAndTheLoopGoesOn() {
        // Six people with a picture each; every first ask is refused, more than MAX_FAILURES (5) in a row.
        val people = (1..6).map { "90533000000$it@s.whatsapp.net" }
        container.numbers = people.map { "+" + it.substringBefore('@') }
        for (jid in people) {
            container.gw.onWhatsapp += jid
            container.gw.profiles[jid] = WaProfile(jid, pushName = jid.take(12), pictureId = "p", fetchedMs = 1)
            container.gw.pictures[jid] = PictureAnswer("image", id = "p", dataB64 = b64(jid))
        }
        val refused = mutableSetOf<String>()
        container.gw.pictureOverride = { body -> if (refused.add(body.jid)) rateLimited() else null }
        container.connect()

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(SyncOutcome.Ok, outcome)
        assertEquals(
            "every picture is downloaded",
            people.toSet(),
            container.gw.pictureAsks
                .map { it.jid }
                .toSet(),
        )
        assertTrue(
            people.all {
                container.people.book
                    .getValue(it)
                    .pictureFile != null
            },
        )
        assertTrue("the refusals were waited out", container.pauses.isNotEmpty())
    }
}
