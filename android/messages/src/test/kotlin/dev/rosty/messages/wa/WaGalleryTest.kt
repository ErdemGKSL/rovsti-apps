package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.WaSyncChatMediaRequest
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gallery the viewer pages (E6-WA R3): both ways from the opened message, a page at a time, through `chat_media`. */
class WaGalleryTest {
    private val minute = 60_000L
    private val ops = FakeWaSyncOps(WaTestKit.INSTANCE)
    private val asked = mutableListOf<Pair<WaSyncCursor?, WaSyncCursor?>>()

    /** The fake store's `chat_media`, counted. */
    private val source =
        WaGallerySource { before, after, limit ->
            asked += before to after
            (ops.chatMedia(WaSyncChatMediaRequest(1, ADA, limit, before, after)) as Outcome.Ok).value
        }

    /** Twenty photos and videos a minute apart, `m0` the oldest, a text and a view-once photo among them. */
    private fun seed(): List<WaSyncMessage> {
        val rows =
            (0 until 20).map { i ->
                WaTestKit.media(
                    ADA,
                    "m$i",
                    NOW - (20 - i) * minute,
                    if (i % 4 == 3) WaSyncKind.Video else WaSyncKind.Image,
                    if (i % 4 == 3) "video/mp4" else "image/jpeg",
                    1_000,
                )
            }
        rows.forEach { ops.messages.getOrPut(ADA) { mutableListOf() } += it }
        ops.messages.getValue(ADA) += WaTestKit.text(ADA, "t1", NOW - 5 * minute - 1, "not media")
        ops.messages.getValue(ADA) +=
            WaTestKit.media(
                ADA,
                "once",
                NOW - 5 * minute - 2,
                WaSyncKind.Image,
                "image/jpeg",
                1_000,
                MediaMeta(viewOnce = true),
            )
        return rows
    }

    private fun ids(gallery: WaGallery) = gallery.items.map { it.id }

    @Test fun opensOnTheMessageAndReadsAPageOnEachSide() =
        runBlocking {
            val rows = seed()
            val gallery = WaGallery(rows[10], source, pageSize = 4)
            // At once: the opened message alone, before any page is read.
            assertEquals(listOf("m10"), ids(gallery))
            assertEquals(0, gallery.prepended)

            gallery.start()
            // Oldest first; the four below and the four above, no text, no view once.
            assertEquals((6..14).map { "m$it" }, ids(gallery))
            assertEquals(4, gallery.prepended)
            assertEquals(4, gallery.indexOf("m10"))
            assertTrue(gallery.hasOlder)
            assertTrue(gallery.hasNewer)
        }

    @Test fun pagesFurtherWhenTheViewerComesNearAnEndAndStopsAtBothEnds() =
        runBlocking {
            val rows = seed()
            val gallery = WaGallery(rows[10], source, pageSize = 4)
            gallery.start()
            // In the middle nothing more is read.
            gallery.ensure(4)
            assertEquals(2, asked.size)

            // Near the start: the page before, put in front; the message on show keeps its photo.
            gallery.ensure(1)
            assertEquals((2..14).map { "m$it" }, ids(gallery))
            assertEquals(8, gallery.prepended)
            assertEquals(WaSyncCursor(rows[6].timestampMs, "m6"), asked.last().first)

            // Near the end: the page after.
            gallery.ensure(gallery.items.size - 1)
            assertEquals((2..18).map { "m$it" }, ids(gallery))

            // To both ends: the store says there is nothing more, and nobody asks again.
            while (gallery.hasOlder || gallery.hasNewer) {
                gallery.ensure(0)
                gallery.ensure(gallery.items.size - 1)
            }
            assertEquals((0..19).map { "m$it" }, ids(gallery))
            val calls = asked.size
            gallery.ensure(0)
            gallery.ensure(gallery.items.size - 1)
            assertEquals(calls, asked.size)
            assertEquals(10, gallery.prepended)
        }

    @Test fun aMessageTheGalleryLeavesOutIsShownAlone() =
        runBlocking {
            seed()
            val once = ops.messages.getValue(ADA).first { it.id == "once" }
            val queued =
                WaTestKit
                    .media(
                        ADA,
                        "q1",
                        NOW,
                        WaSyncKind.Image,
                        "image/jpeg",
                        1_000,
                    ).copy(state = WaSyncRowState.Waiting)
            val sticker = WaTestKit.media(ADA, "s1", NOW, WaSyncKind.Sticker, "image/webp", 1_000)
            for (row in listOf(once, queued, sticker)) {
                val gallery = WaGallery(row, source)
                gallery.start()
                gallery.ensure(0)
                assertEquals(listOf(row.id), ids(gallery))
                assertFalse(gallery.hasOlder || gallery.hasNewer)
            }
            assertTrue(asked.isEmpty())
        }

    @Test fun aStoreThatCannotAnswerNowIsAskedAgainLater() =
        runBlocking {
            val rows = seed()
            var down = true
            val flaky =
                WaGallerySource { before, after, limit ->
                    if (down) null else source.page(before, after, limit)
                }
            val gallery = WaGallery(rows[10], flaky, pageSize = 4)
            gallery.start()
            assertEquals(listOf("m10"), ids(gallery))
            assertTrue(gallery.hasOlder)
            down = false
            gallery.ensure(0)
            assertEquals((6..14).map { "m$it" }, ids(gallery))
        }

    @Test fun aDownloadedFileReplacesItsRowInPlace() =
        runBlocking {
            val rows = seed()
            val gallery = WaGallery(rows[10], source, pageSize = 2)
            gallery.start()
            val at = gallery.indexOf("m9")
            gallery.replace(rows[9].copy(mediaPath = "/files/m9.jpg"))
            assertEquals(at, gallery.indexOf("m9"))
            assertEquals("/files/m9.jpg", gallery.items[at].mediaPath)
        }
}
