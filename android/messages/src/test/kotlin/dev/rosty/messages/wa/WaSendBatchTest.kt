package dev.rosty.messages.wa

import dev.rosty.core.model.wa.MediaKind
import dev.rosty.platform.media.MediaMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** The model of the send preview (E6-WA R3): add, remove, reorder, captions, view once, the order and options sent. */
class WaSendBatchTest {
    private fun item(
        id: String,
        mime: String,
        measure: MediaMeasure? = null,
    ) = WaBatchItem(id, WaStagedFile(File("/outbox/$id"), "$id.bin", mime, 1_000), measure = measure)

    private val photo = item("p", "image/jpeg", MediaMeasure(1_600, 1_200, null, byteArrayOf(1, 2, 3)))
    private val video = item("v", "video/mp4", MediaMeasure(720, 1_280, 42, byteArrayOf(4, 5)))
    private val doc = item("d", "application/pdf")

    private fun ids(batch: WaSendBatch) = batch.items.map { it.id }

    @Test fun addedFilesGoAtTheEndAndTheFirstOfThemIsShown() {
        val batch = WaSendBatch().add(listOf(photo, video))
        assertEquals(listOf("p", "v"), ids(batch))
        assertEquals(0, batch.current)
        val more = batch.select(1).add(listOf(doc))
        assertEquals(listOf("p", "v", "d"), ids(more))
        assertEquals(2, more.current)
        assertSame(more, more.add(emptyList()))
    }

    @Test fun removingKeepsANeighbourOnScreen() {
        val batch = WaSendBatch().add(listOf(photo, video, doc))
        // The one on screen goes: the next one comes.
        assertEquals(
            "v",
            batch
                .select(0)
                .remove("p")
                .shown
                ?.id,
        )
        // The last one goes: the one before it comes.
        assertEquals(
            "v",
            batch
                .select(2)
                .remove("d")
                .shown
                ?.id,
        )
        // One before the shown one goes: the shown one stays.
        assertEquals(
            "d",
            batch
                .select(2)
                .remove("p")
                .shown
                ?.id,
        )
        assertTrue(WaSendBatch().add(listOf(photo)).remove("p").isEmpty)
        assertSame(batch, batch.remove("nope"))
    }

    @Test fun movingKeepsTheMovedFileOnScreenWithinTheList() {
        val batch = WaSendBatch().add(listOf(photo, video, doc))
        val later = batch.move("p", 1)
        assertEquals(listOf("v", "p", "d"), ids(later))
        assertEquals(1, later.current)
        val earlier = later.move("d", -2)
        assertEquals(listOf("d", "v", "p"), ids(earlier))
        assertEquals(0, earlier.current)
        // Past either end nothing moves.
        assertSame(earlier, earlier.move("d", -1))
        assertSame(earlier, earlier.move("p", 1))
    }

    @Test fun captionsAndViewOnceBelongToTheirFile() {
        val batch =
            WaSendBatch()
                .add(listOf(photo, video, doc))
                .caption("v", " the ferry ")
                .toggleViewOnce("p")
                .toggleViewOnce("d")
        assertEquals(" the ferry ", batch.items[1].caption)
        assertTrue(batch.items[0].viewOnce)
        // A document cannot be view once.
        assertFalse(batch.items[2].viewOnce)
        assertFalse(batch.items[2].canViewOnce)
        assertFalse(batch.toggleViewOnce("p").items[0].viewOnce)
    }

    @Test fun eachFileIsItsOwnMessageInOrderWithItsOptions() {
        val batch =
            WaSendBatch()
                .add(listOf(photo, video, doc))
                .caption("v", " the ferry ")
                .toggleViewOnce("v")
                .move("d", -2)
        val actions = batch.actions(quoted = "m1")
        assertEquals(listOf("/outbox/d", "/outbox/p", "/outbox/v"), actions.map { it.file })
        assertEquals(listOf(MediaKind.Document, MediaKind.Image, MediaKind.Video), actions.map { it.media })
        // Only the first answers the quoted message.
        assertEquals(listOf("m1", null, null), actions.map { it.quotedId })

        val (document, picture, clip) = actions
        assertNull(document.thumbnailB64)
        assertNull(document.width)
        assertFalse(document.viewOnce)

        assertEquals(1_600 to 1_200, picture.width to picture.height)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), picture.thumbnailB64)
        assertNull(picture.seconds)
        assertNull(picture.caption)
        assertFalse(picture.viewOnce)

        assertEquals("the ferry", clip.caption)
        assertEquals(42, clip.seconds)
        assertEquals(720 to 1_280, clip.width to clip.height)
        assertTrue(clip.viewOnce)
        assertFalse(clip.animated)
        assertEquals("v.bin", clip.fileName)
    }

    @Test fun aGifGoesOutAsThePictureItIs() {
        val gif = item("g", "image/gif", MediaMeasure(320, 240, null, byteArrayOf(9)))
        val action = gif.action(null)
        assertEquals(MediaKind.Image, action.media)
        assertFalse(action.animated)
        assertEquals(320, action.width)
    }

    @Test fun aFileThatDidNotMeasureGoesOutWithoutOptions() {
        val action = item("x", "video/mp4").action(null)
        assertNull(action.thumbnailB64)
        assertNull(action.width)
        assertNull(action.seconds)
    }
}
