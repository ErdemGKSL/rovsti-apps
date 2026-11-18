package dev.rosty.stops.notice

import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.platform.location.FakeTripRepository
import dev.rosty.platform.location.TripStatus
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.TestStopsContainer
import dev.rosty.stops.trip.TripLinkRelease
import dev.rosty.wa.WaLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Stops' WhatsApp key (`plans/wa-vault.md` 13.3): a trip started while the vault is open keeps a copy of the link in
 * RAM for its arriving-soon message and drops it at the trip's end; a trip started while locked carries no message
 * and asks nothing of the vault or the gateway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripWaLinkTest {
    private val a = Destination("Pier West", Coord(41.0, 28.994))
    private val b = Destination("Pier East", Coord(41.0, 29.006))

    /** Every time something asked the repository for the gateway. */
    private var gatewayAsks = 0

    private fun container(locked: Boolean): TestStopsContainer {
        val fake = FakeStopsCore(prefs = StopsPrefs(notice = NoticeFixtures.notice))
        return TestStopsContainer(RuntimeEnvironment.getApplication(), fake).also {
            if (locked) it.fakeVault.lockVault()
            it.fakeWa.setState(NoticeFixtures.connected)
            it.fakeWa.gatewayOpener = {
                gatewayAsks += 1
                null
            }
        }
    }

    @Test
    fun aTripStartedWhileLockedCarriesNoMessageAndTakesNoKey() =
        runBlocking {
            val c = container(locked = true)
            assertNull(c.arrivingSoon.noticeFor(a, b))
            assertTrue("vault.trip_hold" !in c.fakeVault.calls)
            assertNull(c.fakeVault.tripLink)
            assertEquals("no gateway request while locked", 0, gatewayAsks)
        }

    @Test
    fun aTripStartedWhileOpenKeepsTheLinkUntilItEnds() =
        runBlocking {
            val c = container(locked = false)
            assertEquals(NoticeFixtures.notice, c.arrivingSoon.noticeFor(a, b))
            assertNotNull("the trip holds the link in RAM", c.fakeVault.tripLink)
            // The vault locks (screen off): the trip keeps its copy.
            c.waVault.lock()
            assertNotNull(c.fakeVault.tripLink)
            // The user stops the trip: the copy goes.
            c.trips.stop()
            waitUntil { c.fakeVault.tripLink == null }
            assertNull(c.fakeVault.tripLink)
        }

    @Test
    fun theTripsEndDropsTheLinkHoweverItEnds() =
        runBlocking {
            val inner = FakeTripRepository()
            var released = 0
            val trips = TripLinkRelease(inner, CoroutineScope(Dispatchers.Unconfined)) { released += 1 }
            inner.push(TripStatus.Searching)
            assertEquals(0, released)
            inner.push(TripStatus.Ended)
            assertEquals(1, released)
            inner.push(TripStatus.Stopped)
            assertEquals(2, released)
            trips.stop()
            assertEquals(3, released)
            assertFalse(inner.running)
        }

    @Test
    fun theLinkStateAloneNeverStartsAMessageWhileLocked() =
        runBlocking {
            val c = container(locked = true)
            c.fakeWa.setState(WaLinkState.None)
            assertNull(c.arrivingSoon.noticeFor(a, b))
        }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) Thread.sleep(10)
    }
}
