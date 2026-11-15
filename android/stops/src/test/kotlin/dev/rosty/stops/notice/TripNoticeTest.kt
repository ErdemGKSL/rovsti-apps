package dev.rosty.stops.notice

import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.wa.WaLinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a trip carries the arriving-soon message (the hook the trip start asks). */
class TripNoticeTest {
    private val prefs = MutableStateFlow(StopsPrefs(notice = NoticeFixtures.notice))
    private val link = MutableStateFlow<WaLinkState>(NoticeFixtures.connected)
    private val settled = MutableStateFlow(true)
    private val hook = TripNotice(prefs, { prefs.value }, link, settled)
    private val a = Destination("Pier West", Coord(41.0, 28.994))
    private val b = Destination("Pier East", Coord(41.0, 29.006))

    private fun asked() = runBlocking { hook.noticeFor(a, b) }

    @Test fun aTripCarriesTheMessageOfTheSettings() {
        assertEquals(NoticeFixtures.notice, asked())
        // The settings are read at the start, not when the hook was made.
        val later = NoticeFixtures.notice.copy(template = "On my way to {stop}")
        prefs.value = StopsPrefs(notice = later)
        assertEquals(later, asked())
    }

    @Test fun theSwitchTakesItOffForTheNextTripAndStaysAsLeft() {
        assertTrue(hook.on.value)
        hook.toggle()
        assertFalse(hook.on.value)
        assertNull(asked())
        assertNull(asked())
        hook.toggle()
        assertEquals(NoticeFixtures.notice, asked())
    }

    @Test fun noChatOrNoLinkMeansNoMessage() {
        prefs.value = StopsPrefs()
        assertNull(asked())
        prefs.value = StopsPrefs(notice = NoticeFixtures.notice.copy(chat = ""))
        assertNull(asked())
        prefs.value = StopsPrefs(notice = NoticeFixtures.notice)
        link.value = WaLinkState.LoggedOut
        assertNull(asked())
        // The link is still being read at a cold start: the chosen chat counts.
        link.value = WaLinkState.None
        settled.value = false
        assertEquals(NoticeFixtures.notice, asked())
        settled.value = true
        assertNull(asked())
    }
}
