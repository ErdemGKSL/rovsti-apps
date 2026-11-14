package dev.rosty.messages.wa.status

import dev.rosty.core.model.wa.StatusAuthor
import dev.rosty.core.model.wa.StatusItem
import dev.rosty.core.model.wa.StatusList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The viewer rules, in step with the tests of `rosty_wa_proto::status`. */
class StatusPlayerTest {
    private fun item(
        id: String,
        author: String,
        at: Long,
        viewed: Boolean = false,
    ) = StatusItem(id = id, author = author, timestampMs = at, viewed = viewed)

    @Test fun thePlayerWalksUpdatesThenAuthorsAndCloses() {
        val player = StatusPlayer.of(listOf(2, 0, 1), 0, 0, durationMs = 5_000)!!
        assertEquals(2, player.count)
        assertEquals(StatusStep.Stay, player.advance(4_999))
        assertEquals(1f, player.fill(0), 0.001f)
        assertEquals(StatusStep.Item, player.advance(1))
        assertEquals(0 to 1, player.author to player.item)
        assertEquals(1f, player.fill(0))
        assertEquals(0f, player.fill(1))
        // The author with no updates is skipped.
        assertEquals(StatusStep.Author, player.forward())
        assertEquals(2 to 0, player.author to player.item)
        assertEquals(StatusStep.Close, player.forward())
    }

    @Test fun backGoesToThePreviousAuthorsLastUpdateAndStaysOnTheVeryFirst() {
        val player = StatusPlayer.of(listOf(2, 0, 1), 2, 0)!!
        assertEquals(StatusStep.Author, player.back())
        assertEquals(0 to 1, player.author to player.item)
        assertEquals(StatusStep.Item, player.back())
        player.advance(1_000)
        assertEquals(StatusStep.Stay, player.back())
        assertEquals(0L, player.elapsedMs)
    }

    @Test fun holdingPausesTheTimer() {
        val player = StatusPlayer.of(listOf(1), 0, 0, durationMs = 1_000)!!
        player.paused = true
        assertEquals(StatusStep.Stay, player.advance(5_000))
        assertEquals(0f, player.fill(0))
        player.paused = false
        assertEquals(StatusStep.Close, player.advance(1_000))
    }

    @Test fun nothingToShowIsNoPlayer() {
        assertNull(StatusPlayer.of(listOf(0), 0, 0))
        assertNull(StatusPlayer.of(listOf(1), 1, 0))
        assertNull(StatusPlayer.of(listOf(1), 0, 1))
    }

    @Test fun seenPeopleMoveBelowTheUnseenOnes() {
        val a = "905550000001@s.whatsapp.net"
        val b = "905550000002@s.whatsapp.net"
        val list =
            StatusList(
                authors =
                    listOf(
                        StatusAuthor(a, "Ada", listOf(item("a1", a, 3_000))),
                        StatusAuthor(b, "Mert", listOf(item("b1", b, 1_000), item("b2", b, 2_000))),
                    ),
            )
        assertEquals(3, list.unviewed())

        val seen = list.markViewed(listOf("a1"))

        assertEquals(listOf(b, a), seen.authors.map { it.author })
        assertEquals(2, seen.unviewed())
        // Nothing new seen: the same list.
        assertSame(seen, seen.markViewed(listOf("a1", "zz")))
    }

    @Test fun onlyOtherPeoplesPhoneJidsCanBeMessaged() {
        assertEquals("905550000001@s.whatsapp.net", statusMessageTarget("905550000001@s.whatsapp.net", false))
        assertNull(statusMessageTarget("905550000001@s.whatsapp.net", true))
        assertNull(statusMessageTarget("123456@lid", false))
        assertNull(statusMessageTarget("@s.whatsapp.net", false))
    }

    @Test fun agesReadLikeWhatsApp() {
        val words =
            StatusWords("Status", "No new updates", { "$it new" }, "My status", "Recent", "Viewed", "Just now", {
                "$it min ago"
            }, { "clock" }, "none")
        val now = 10_000_000L
        assertEquals("Just now", words.ago(now, now - 20_000))
        assertEquals("12 min ago", words.ago(now, now - 12 * 60_000))
        assertEquals("clock", words.ago(now, now - 61 * 60_000))
        // A clock that runs ahead never shows a negative age.
        assertEquals("Just now", words.ago(now, now + 5_000))
    }
}
