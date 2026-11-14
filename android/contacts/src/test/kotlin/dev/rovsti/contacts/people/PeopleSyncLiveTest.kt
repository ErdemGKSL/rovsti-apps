package dev.rovsti.contacts.people

import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.people.sync.SliceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** X.1b and people sync v2: the foreground trigger, live profile events and the live picture pass (real planner). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncLiveTest {
    private val container get() = ApplicationProvider.getApplicationContext<PeopleTestApp>().container
    private val ayse = "905321234567@s.whatsapp.net"

    private fun script() {
        container.useRealPeople()
        container.numbers = listOf("+905321234567")
        container.gw.onWhatsapp += ayse
        container.gw.profiles[ayse] = WaProfile(ayse, pushName = "Ayse", pictureId = "1", fetchedMs = 1)
        container.gw.pictures[ayse] = PictureAnswer("image", id = "1", dataB64 = b64("p"))
        container.connect()
    }

    private fun waitFor(
        what: String,
        ok: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!ok() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(what, ok())
    }

    @Test fun staleDataSyncsOnTheForegroundAndFreshDataDoesNot() {
        script()
        val sync = container.peopleSync
        assertEquals(SliceResult.Done, runBlocking { sync.runSlice() })
        val first = container.gw.lookups.size + container.gw.pictureAsks.size

        container.now += 60_000
        sync.syncIfStale()
        Thread.sleep(300)
        assertEquals("a minute later is not stale", first, container.gw.lookups.size + container.gw.pictureAsks.size)

        // Older than the period and than the profile freshness: the profile is asked again.
        container.now += 25L * 3_600_000
        container.gw.profiles[ayse] = WaProfile(ayse, pushName = "Ayse B", pictureId = "1", fetchedMs = 1)
        sync.syncIfStale()
        waitFor("the stale sync ran") { container.book()[ayse]?.name == "Ayse B" }
    }

    @Test fun aProfileEventUpdatesThePersonAtOnceAndTheLivePassFetchesTheNewPicture() {
        script()
        val sync = container.peopleSync
        runBlocking { sync.runSlice() }
        container.gw.pictures[ayse] = PictureAnswer("image", id = "2", dataB64 = b64("p2"))
        container.gw.pictureAsks.clear()
        container.link.emit(GatewayEvent.Profile(jid = ayse, pushName = "Ayse Yilmaz", pictureId = "2", fetchedMs = 5))
        waitFor("the name is in") { container.book()[ayse]?.name == "Ayse Yilmaz" }
        assertTrue(container.changes >= 1)
        // The debounced live pass (2 s) asks for the new picture under the shared pace.
        waitFor("the live pass fetched it") {
            container.book()[ayse]?.pictureId == "2" &&
                !container.book()[ayse]!!.pictureStale
        }
        assertEquals(1, container.gw.pictureAsks.count { it.jid == ayse })
        assertEquals(
            "the live pass leaves no run behind",
            null,
            sync.runner.status.value.stored
                ?.run,
        )
    }

    @Test fun whileUnlinkedProfileEventsAreIgnored() {
        container.useRealPeople()
        container.peopleSync
        container.link.emit(GatewayEvent.Profile(jid = ayse, pushName = "X", pictureId = "1", fetchedMs = 5))
        Thread.sleep(300)
        assertTrue(container.book().isEmpty())
    }
}
