package dev.rovsti.contacts.people

import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.model.wa.PictureAnswer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** X.1b: the foreground trigger and the "Sync now" control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncLiveTest {
    private val container get() = ApplicationProvider.getApplicationContext<PeopleTestApp>().container
    private val ayse = "905321234567@s.whatsapp.net"

    private fun script() {
        container.numbers = listOf("+905321234567")
        container.gw.onWhatsapp += ayse
        container.gw.profiles[ayse] =
            dev.rovsti.core.model.wa
                .WaProfile(ayse, pushName = "Ayse", pictureId = "1", fetchedMs = 1)
        container.gw.pictures[ayse] = PictureAnswer("image", id = "1", dataB64 = b64("p"))
        container.connect()
    }

    @Test fun staleDataSyncsOnTheForegroundAndFreshDataDoesNot() {
        script()
        val sync = container.peopleSync
        runBlocking { sync.run() }
        val first = container.gw.lookups.size + container.gw.pictureAsks.size

        container.now += 60_000
        sync.syncIfStale()
        Thread.sleep(200)
        assertEquals("a minute later is not stale", first, container.gw.lookups.size + container.gw.pictureAsks.size)

        container.now += PeopleSync.PERIOD_MS
        container.gw.profiles[ayse] =
            dev.rovsti.core.model.wa
                .WaProfile(ayse, pushName = "Ayse B", pictureId = "1", fetchedMs = 1)
        sync.syncIfStale()
        val deadline = System.currentTimeMillis() + 5_000
        while (container.people.book
                .getValue(ayse)
                .name != "Ayse B" &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(
            "Ayse B",
            container.people.book
                .getValue(ayse)
                .name,
        )
    }

    @Test fun aProfileEventUpdatesThePersonAtOnce() {
        script()
        val sync = container.peopleSync
        runBlocking { sync.run() }
        container.link.emit(
            dev.rovsti.core.model.wa.GatewayEvent.Profile(
                jid = ayse,
                pushName = "Ayse Yilmaz",
                pictureId = "1",
                fetchedMs = 5,
            ),
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (container.people.book
                .getValue(ayse)
                .name != "Ayse Yilmaz" &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(
            "Ayse Yilmaz",
            container.people.book
                .getValue(ayse)
                .name,
        )
        assertTrue(container.changes >= 1)
    }

    @Test fun whileUnlinkedProfileEventsAreIgnored() {
        container.peopleSync
        container.link.emit(
            dev.rovsti.core.model.wa.GatewayEvent
                .Profile(jid = ayse, pushName = "X", pictureId = "1", fetchedMs = 5),
        )
        Thread.sleep(300)
        assertTrue(container.people.book.isEmpty())
    }
}
