package dev.rosty.messages.wa

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.WaSyncChatMediaRequest
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.design.media.ViewerPhoto
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

/** What one open viewer keeps per item (E6-WA R3): the page, the downloads, the byline, the saved state, positions. */
class WaViewerSessionTest {
    private val minute = 60_000L
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val ops = FakeWaSyncOps(WaTestKit.INSTANCE)
    private val fetched = mutableListOf<String>()
    private var fetchAnswer: suspend (WaSyncMessage) -> File? = { File("/files/${it.id}.jpg") }
    private var goneAnswer = false
    private val savedUris = mutableMapOf<String, String>()

    @After fun stop() = scope.cancel()

    private val labels =
        WaViewerLabels(
            save = "Save",
            saved = "Saved",
            share = "Share",
            forward = "Forward",
            play = "Play",
            pause = "Pause",
            mute = "Mute",
            unmute = "Unmute",
            position = "Playback position",
            download = "Download",
            downloadSize = { "Download · $it" },
            downloading = "Downloading…",
            retry = "Try Again",
            cantDownload = "Couldn't download the file",
            gone = "No longer available",
            cantPlay = "This video can't be played",
            you = "You",
            byline = { who, at -> "$who · $at" },
            locale = Locale.US,
            sizeOf = ::plainSize,
        )

    private fun session(opened: WaSyncMessage): WaViewerSession =
        WaViewerSession(
            opened,
            WaViewerSources(
                thumb = { null },
                ensureThumb = { false },
                fetch = { row ->
                    fetched += row.id
                    fetchAnswer(row)
                },
                gone = { goneAnswer },
                gallery =
                    WaGallerySource { before, after, limit ->
                        (ops.chatMedia(WaSyncChatMediaRequest(1, ADA, limit, before, after)) as Outcome.Ok).value
                    },
                sender = { "Ada Lovelace" },
                savedUri = { savedUris[it.id] },
                zone = ZoneId.of("UTC"),
            ),
            labels,
            scope,
        )

    private fun seed(): List<WaSyncMessage> {
        val rows =
            listOf(
                WaTestKit.media(ADA, "p0", NOW - 5 * minute, WaSyncKind.Image, "image/jpeg", 1_000),
                WaTestKit.media(ADA, "v1", NOW - 4 * minute, WaSyncKind.Video, "video/mp4", 9_000),
                WaTestKit.media(ADA, "p2", NOW - 3 * minute, WaSyncKind.Image, "image/jpeg", 1_000, fromMe = true),
                WaTestKit
                    .media(ADA, "p3", NOW - 2 * minute, WaSyncKind.Image, "image/jpeg", 1_000)
                    .copy(mediaPath = "/files/p3.jpg"),
            )
        ops.messages[ADA] = rows.toMutableList()
        return rows
    }

    @Test fun theOpenedPhotoKeepsItsPlaceWhileOlderOnesArePutInFrontAndItIsDownloadedAsATap() {
        val rows = seed()
        val session = session(rows[2])
        session.start()
        // p0 and v1 came in front of the opened p2, p3 after it: the opened photo is at index 2 now.
        assertEquals(listOf("p0", "v1", "p2", "p3"), session.gallery.items.map { it.id })
        assertEquals(2, session.prepended)
        assertEquals(4, session.count)
        // The opened photo was not on the phone: it was asked for as a tap, once, and shows the file now.
        assertEquals(listOf("p2"), fetched)
        assertEquals("/files/p2.jpg", (session.model(2) as ViewerPhoto).full.toString())
        assertNull(session.loadOf(session.gallery.items[2]))

        assertEquals(2, session.page)

        // Paging to the video next to it does not download it by itself; its button does.
        session.onPage(1)
        assertEquals(1, session.page)
        assertEquals(listOf("p2"), fetched)
        session.download(session.gallery.items[1])
        assertEquals(listOf("p2", "v1"), fetched)
        // A photo comes by itself when it is on show; one already on the phone is not asked for.
        session.onPage(0)
        assertEquals(listOf("p2", "v1", "p0"), fetched)
        session.onPage(3)
        assertTrue("p3" !in fetched)
    }

    @Test fun aFileThatDoesNotComeSaysWhyAndOneThatIsLoadingWaits() {
        val rows = seed()
        fetchAnswer = { null }
        goneAnswer = true
        val gone = session(rows[0])
        gone.start()
        assertEquals(WaViewerLoad.Gone, gone.loadOf(gone.gallery.items[0]))

        goneAnswer = false
        val failed = session(rows[0])
        failed.start()
        assertEquals(WaViewerLoad.Failed, failed.loadOf(failed.gallery.items[0]))

        fetchAnswer = { awaitCancellation() }
        val loading = session(rows[0])
        loading.start()
        val row = loading.gallery.items[0]
        assertEquals(WaViewerLoad.Loading, loading.loadOf(row))
        // A second tap while it comes asks nothing more.
        val asked = fetched.size
        loading.download(row)
        assertEquals(asked, fetched.size)

        // What the store already says is gone shows as gone before anything is asked.
        val stale = rows[1].copy(mediaState = WaSyncMediaState.Gone)
        assertEquals(WaViewerLoad.Gone, session(stale).loadOf(stale))
    }

    @Test fun theBylineNamesTheSenderOrYouWithTheTime() {
        val rows = seed()
        val session = session(rows[2])
        session.start()
        assertTrue(session.byline(rows[2])!!.startsWith("You · "))
        session.onPage(1)
        val line = session.byline(rows[1])!!
        assertTrue(line, line.startsWith("Ada Lovelace · "))
        assertTrue(line, line.contains("2026"))
    }

    @Test fun saveReadsSavedAfterwardsAndAViewOnceMessageHasNoActions() {
        val rows = seed()
        savedUris["p3"] = "content://media/external/images/media/7"
        val session = session(rows[2])
        session.start()
        var saves = 0
        val actions =
            WaViewerActions(
                save = {
                    saves++
                    true
                },
                share = {},
                forward = {},
            )
        val p2 = session.gallery.items[2]
        var shown = actionsOf(p2, session, labels, actions, scope)
        assertEquals(listOf("save", "share", "forward"), shown.map { it.id })
        assertEquals("Save", shown[0].label)
        shown[0].onClick(2)
        assertEquals(1, saves)
        shown = actionsOf(p2, session, labels, actions, scope)
        assertEquals("Saved", shown[0].label)
        // Saved before this viewer opened: the store's saved copy.
        session.onPage(3)
        assertEquals("Saved", actionsOf(session.gallery.items[3], session, labels, actions, scope)[0].label)

        val once = rows[0].copy(id = "o1", media = MediaMeta(viewOnce = true))
        assertTrue(actionsOf(once, session, labels, actions, scope).isEmpty())
        // A view-once message is not in the gallery: the viewer shows it alone.
        val alone = session(once)
        alone.start()
        assertEquals(1, alone.count)
    }

    @Test fun aVideoStartsWhereItWasLeftUnlessItPlayedToTheEnd() {
        val rows = seed()
        val session = session(rows[1])
        assertEquals(0, session.positionOf("v1"))
        session.keep("v1", 12_000, ended = false)
        assertEquals(12_000, session.positionOf("v1"))
        session.keep("v1", 40_000, ended = true)
        assertEquals(0, session.positionOf("v1"))
    }

    @Test fun theClockOfTheControls() {
        assertEquals("0:00", clock(0))
        assertEquals("0:07", clock(7_400))
        assertEquals("12:40", clock(760_000))
        assertEquals("1:02:03", clock(3_723_000))
    }
}
