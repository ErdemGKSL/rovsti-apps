package dev.rosty.stops.notice

import dev.rosty.core.model.stops.NoticePrefs
import dev.rosty.core.model.stops.NoticeWhen
import dev.rosty.core.ops.NativeWaOps
import dev.rosty.stops.HostCore
import dev.rosty.stops.R
import dev.rosty.wa.WaLinkError
import dev.rosty.wa.WaLinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pure rules of the arriving-soon message; the naming runs on the real core (`wa.names`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeLogicTest {
    private val waOps = NativeWaOps(HostCore.real())

    private fun named() = runBlocking { namedChats(NoticeFixtures.chats, NoticeFixtures.profiles::get, waOps::names) }

    @Test fun peopleAreNamedBySavedContactThenWhatsAppThenNumber() {
        val byJid = named().associateBy { it.jid }
        // A saved contact: its name, the number under it. Not the push name, not the chat's own name.
        val ayse = byJid.getValue(NoticeFixtures.AYSE)
        assertEquals("Ayşe Demir", ayse.name)
        assertEquals("+905550001122", ayse.number)
        assertFalse(ayse.group)
        // No saved contact: WhatsApp's name, the verified business name before the push name.
        assertEquals("Corner Bakery", byJid.getValue(NoticeFixtures.SHOP).name)
        assertEquals("+905550003344", byJid.getValue(NoticeFixtures.SHOP).number)
        // Neither: the number, once (no second line repeating it).
        val stranger = byJid.getValue(NoticeFixtures.STRANGER)
        assertEquals("+905550005566", stranger.name)
        assertNull(stranger.number)
        // A group is its subject and has no number line.
        val family = byJid.getValue(NoticeFixtures.FAMILY)
        assertEquals("Family", family.name)
        assertNull(family.number)
        assertTrue(family.group)
    }

    @Test fun theOrderIsTheGatewaysAndBlankNamesCountAsNone() {
        assertEquals(NoticeFixtures.chats.map { it.jid }, named().map { it.jid })
        val blank =
            runBlocking {
                namedChats(
                    listOf(NoticeFixtures.chats[0].copy(name = "  ")),
                    { NoticeFixtures.profiles.getValue(it).copy(contactName = " ", pushName = "") },
                    waOps::names,
                )
            }
        assertEquals("+905550001122", blank.single().name)
        assertEquals(emptyList<NoticeChat>(), runBlocking { namedChats(emptyList(), { null }, waOps::names) })
    }

    @Test fun theSearchMatchesNameAndNumberInAnyCase() {
        val chats = named()
        assertEquals(chats, filterChats(chats, "  "))
        assertEquals(listOf(NoticeFixtures.AYSE), filterChats(chats, "demir").map { it.jid })
        assertEquals(listOf(NoticeFixtures.AYSE), filterChats(chats, " AYŞE  dem ").map { it.jid })
        assertEquals(listOf(NoticeFixtures.SHOP), filterChats(chats, "3344").map { it.jid })
        assertEquals(listOf(NoticeFixtures.FAMILY), filterChats(chats, "fam").map { it.jid })
        assertEquals(emptyList<NoticeChat>(), filterChats(chats, "nobody"))
        assertEquals(emptyList<NoticeChat>(), filterChats(emptyList(), "a"))
    }

    @Test fun aTripCarriesTheMessageOnlyWithAChatAndALink() {
        val notice = NoticeFixtures.notice
        assertTrue(noticeAvailable(notice, NoticeFixtures.connected, settled = true))
        assertFalse(noticeAvailable(null, NoticeFixtures.connected, settled = true))
        assertFalse(noticeAvailable(notice.copy(chat = ""), NoticeFixtures.connected, settled = true))
        for (link in listOf(
            WaLinkState.None,
            WaLinkState.LoggedOut,
            WaLinkState.Banned,
            WaLinkState.Linking(),
            WaLinkState.Error(WaLinkError.Unreachable),
        )) {
            assertFalse("$link", noticeAvailable(notice, link, settled = true))
        }
        // The stored link has not been read yet: a chosen chat counts (the service sends through the stored link).
        assertTrue(noticeAvailable(notice, WaLinkState.None, settled = false))
        assertFalse(noticeAvailable(NoticePrefs("", "", "x", NoticeWhen.WithAlarm), WaLinkState.None, settled = false))
    }

    @Test fun theLiveCardSaysWhatBecameOfTheMessage() {
        assertNull(noticeLine(planned = false, sent = false, failed = false))
        assertEquals(R.string.notice_line_planned, noticeLine(planned = true, sent = false, failed = false))
        assertEquals(R.string.notice_line_failed, noticeLine(planned = true, sent = false, failed = true))
        // Sent wins over a failure reported earlier, and shows even when the trip file was read without its notice.
        assertEquals(R.string.notice_line_sent, noticeLine(planned = true, sent = true, failed = true))
        assertEquals(R.string.notice_line_sent, noticeLine(planned = false, sent = true, failed = false))
    }
}
