package dev.rosty.core

import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MediaOptions
import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaDecision
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The media round (E6-WA R3) through [WaSyncStore] over [FakeWaSyncOps]: the options of a media send, the played
 * receipt, view once and the gallery of a chat. `WaSyncMediaRoundNativeTest` makes the same calls against the real
 * library, so the fake answers what the store answers.
 */
class WaSyncMediaRoundFakeTest {
    private val ali = "100000000001@s.whatsapp.net"
    private val group = "120000000000000001@g.us"
    private val now = 1_000_000L
    private val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1))
    private val samples = ByteArray(MediaWaveform.SAMPLES) { (it % 101).toByte() }
    private val waveform = MediaWaveform.encode(samples)

    private suspend fun open(fake: FakeWaSyncOps): WaSyncStore =
        WaSyncStore.open("inst1", ops = fake, release = {}).getOrThrow()

    private fun invalid(outcome: Outcome<*>): Boolean = (outcome as? Outcome.Err)?.error is RustError.Invalid

    private fun rejected() = WaSyncOutcome.Error(WaSyncErrorKind.Rejected, "no")

    @Test
    fun aWaveformIsSixtyFourSamplesOfZeroToAHundred() {
        assertArrayEquals(samples, MediaMeta(waveformB64 = waveform).waveform())
        assertNull(MediaMeta().waveform())
        assertNull(MediaMeta(waveformB64 = "***").waveform())
        assertNull(MediaMeta(waveformB64 = "AAEC").waveform())
        assertNull(MediaWaveform.encode(ByteArray(63)))
        assertNull(MediaWaveform.encode(ByteArray(64) { 101 }))
        assertNull(MediaWaveform.encode(ByteArray(64) { -1 }))
    }

    @Test
    fun aMediaSendCarriesItsOptionsToTheGatewayCallAndKeepsThemOnTheMessage() =
        runTest {
            val store = open(FakeWaSyncOps())
            val voice =
                WaSyncAction.SendMedia(
                    file = "/o/v.ogg",
                    media = MediaKind.Audio,
                    mime = "audio/ogg",
                    size = 900,
                    seconds = 6,
                    voiceNote = true,
                    waveformB64 = waveform,
                )
            store.enqueue("c1", ali, voice, 1_000).getOrThrow()
            val expected = MediaMeta(seconds = 6, voiceNote = true, waveformB64 = waveform)
            val waiting =
                store
                    .thread(ali, 10)
                    .getOrThrow()
                    .items
                    .first()
            assertEquals(expected, waiting.media)
            assertEquals(WaSyncKind.Audio, waiting.kind)
            assertEquals(WaSyncRowState.Waiting, waiting.state)
            assertEquals("/o/v.ogg", waiting.mediaPath)

            val due =
                store
                    .due(2_000)
                    .getOrThrow()
                    .items
                    .single()
            assertEquals(
                MediaOptions(seconds = 6, voiceNote = true, waveformB64 = waveform),
                (due.action as WaSyncAction.SendMedia).options(),
            )
            store.settle("c1", WaSyncOutcome.Sent("S1", 2_100), 2_100).getOrThrow()
            val sent = store.message(ali, "S1").getOrThrow()
            assertEquals(expected, sent?.media)
            assertEquals("/o/v.ogg", sent?.mediaPath)

            // A photo's preview becomes the thumbnail of the sent message.
            val photo =
                WaSyncAction.SendMedia(
                    file = "/o/p.jpg",
                    media = MediaKind.Image,
                    mime = "image/jpeg",
                    size = 500,
                    thumbnailB64 = jpeg,
                    width = 640,
                    height = 480,
                )
            store.enqueue("c2", ali, photo, 3_000).getOrThrow()
            store.due(3_100).getOrThrow()
            store.settle("c2", WaSyncOutcome.Sent("S2", 3_200), 3_200).getOrThrow()
            val picture = store.message(ali, "S2").getOrThrow()
            assertTrue(picture?.hasThumbnail == true)
            assertEquals(640 to 480, picture?.media?.width to picture?.media?.height)
            assertEquals(jpeg, store.media(ali, "S2").getOrThrow().thumbnailB64)
        }

    @Test
    fun optionsTheGatewayWouldRefuseAreRefusedWhenQueued() =
        runTest {
            val fake = FakeWaSyncOps()
            val store = open(fake)
            val photo = WaSyncAction.SendMedia("/o/a.jpg", MediaKind.Image, "image/jpeg", 9)
            val png = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            assertTrue(invalid(store.enqueue("c1", ali, photo.copy(thumbnailB64 = png), now)))
            assertTrue(invalid(store.enqueue("c1", ali, photo.copy(waveformB64 = "AAEC"), now)))
            val document = WaSyncAction.SendMedia("/o/a.pdf", MediaKind.Document, "application/pdf", 9, viewOnce = true)
            assertTrue(invalid(store.enqueue("c1", ali, document, now)))
            // A played receipt has its own door.
            assertTrue(invalid(store.enqueue("c1", ali, WaSyncAction.MarkPlayed("V"), now)))
            assertTrue(fake.queue.isEmpty())
        }

    @Test
    fun aViewOnceSendDoesNotKeepItsFileAndCannotBeOpenedByItsSender() =
        runTest {
            val store = open(FakeWaSyncOps())
            val once = WaSyncAction.SendMedia("/o/once.jpg", MediaKind.Image, "image/jpeg", 9, viewOnce = true)
            store.enqueue("c1", ali, once, 1_000).getOrThrow()
            store.due(1_100).getOrThrow()
            store.settle("c1", WaSyncOutcome.Sent("S1", 1_200), 1_200).getOrThrow()
            val sent = store.message(ali, "S1").getOrThrow()
            assertTrue(sent?.media?.viewOnce == true)
            assertNull(sent?.mediaPath)
            val tapped = store.enqueueMedia(ali, "S1", now, tap = true).getOrThrow()
            assertFalse(tapped.queued)
            assertEquals(WaSyncMediaDecision.Never, tapped.decision)
        }

    @Test
    fun aPlayedReceiptIsQueuedOncePerReceivedVoiceNote() =
        runTest {
            val fake = FakeWaSyncOps()
            val note = MediaMeta(seconds = 4, voiceNote = true, waveformB64 = waveform)
            fake.putMedia(ali, "V", 1_000, WaSyncKind.Audio, note)
            fake.putMedia(ali, "SONG", 2_000, WaSyncKind.Audio)
            fake.putMedia(ali, "MINE", 3_000, WaSyncKind.Audio, note, fromMe = true)
            fake.putMedia(group, "G", 4_000, WaSyncKind.Audio, note, sender = ali)
            fake.put(ali, "T", 5_000, "hi")
            val store = open(fake)

            assertArrayEquals(
                samples,
                store
                    .message(ali, "V")
                    .getOrThrow()
                    ?.media
                    ?.waveform(),
            )
            val first = store.markPlayed("p1", ali, "V", now).getOrThrow()
            assertTrue(first.queued)
            assertEquals(WaSyncAction.MarkPlayed("V"), first.item?.action)
            assertTrue(store.message(ali, "V").getOrThrow()?.played == true)
            // The same call again answers the same row; another call for the message queues nothing.
            assertEquals(first.item, store.markPlayed("p1", ali, "V", now + 1).getOrThrow().item)
            val other = store.markPlayed("p2", ali, "V", now + 2).getOrThrow()
            assertFalse(other.queued)
            assertNull(other.item)
            assertEquals(1, store.outbox().getOrThrow().size)

            val due =
                store
                    .due(now + 3)
                    .getOrThrow()
                    .items
                    .single()
            assertEquals(WaSyncAction.MarkPlayed("V"), due.action)
            assertEquals(WaSyncSettled.DONE, store.settle("p1", WaSyncOutcome.Sent(), now + 4).getOrThrow().state)
            assertTrue(store.outbox().getOrThrow().isEmpty())
            assertFalse(store.markPlayed("p3", ali, "V", now + 5).getOrThrow().queued)
            // A receipt is no row of the thread.
            assertTrue(
                store
                    .thread(ali, 10)
                    .getOrThrow()
                    .items
                    .none { it.id == "p1" },
            )

            for (id in listOf("SONG", "MINE", "T")) assertTrue(id, invalid(store.markPlayed("x", ali, id, now)))
            assertTrue((store.markPlayed("x", ali, "NOPE", now) as Outcome.Err).error is RustError.NotFound)

            // In a group the receipt names the author; a refused one is dropped and not tried again.
            val inGroup = store.markPlayed("g1", group, "G", now).getOrThrow()
            assertEquals(WaSyncAction.MarkPlayed("G", sender = ali), inGroup.item?.action)
            store.due(now + 6).getOrThrow()
            assertEquals(WaSyncSettled.FAILED, store.settle("g1", rejected(), now + 7).getOrThrow().state)
            assertTrue(store.outbox().getOrThrow().isEmpty())
            assertTrue(store.message(group, "G").getOrThrow()?.played == true)
            assertFalse(store.markPlayed("g2", group, "G", now + 8).getOrThrow().queued)
        }

    @Test
    fun viewOnceIsFetchedByATapOnlyAndOpensOnce() =
        runTest {
            val fake = FakeWaSyncOps()
            fake.putMedia(ali, "ONCE", 1_000, WaSyncKind.Image, MediaMeta(viewOnce = true))
            fake.putMedia(ali, "PHOTO", 2_000, WaSyncKind.Image)
            val store = open(fake)
            store.setMedia(ali, "ONCE", now, thumbnailB64 = jpeg).getOrThrow()

            // Never by itself, whatever the network.
            val auto = store.enqueueMedia(ali, "ONCE", now, network = WaSyncNetwork.Wifi).getOrThrow()
            assertFalse(auto.queued)
            assertEquals(WaSyncMediaDecision.Never, auto.decision)
            assertEquals(WaSyncMediaDecision.Never, store.mediaPolicy(ali, "ONCE", WaSyncNetwork.Wifi).getOrThrow())
            // The tap that opens it fetches it.
            val tapped = store.enqueueMedia(ali, "ONCE", now, tap = true).getOrThrow()
            assertTrue(tapped.queued)
            assertEquals(WaSyncMediaDecision.OnTap, tapped.decision)
            assertEquals(
                1,
                store
                    .dueMedia(now + 1, network = WaSyncNetwork.Wifi)
                    .getOrThrow()
                    .items.size,
            )
            store.settleMedia(ali, "ONCE", WaSyncFetched.Ready("/m/once.jpg", 1_000), now + 2).getOrThrow()
            val before = store.message(ali, "ONCE").getOrThrow()
            assertEquals("/m/once.jpg", before?.mediaPath)
            assertTrue(before?.hasThumbnail == true && before.viewed.not())

            val viewed = store.markViewed(ali, "ONCE", now + 3).getOrThrow()
            assertTrue(viewed.first)
            assertEquals("/m/once.jpg", viewed.delete)
            assertEquals(WaSyncMediaState.Gone, viewed.media.state)
            assertNull(viewed.media.path)
            val after = store.message(ali, "ONCE").getOrThrow()
            assertTrue(after?.viewed == true)
            assertNull(after?.mediaPath)
            assertEquals(WaSyncMediaState.Gone, after?.mediaState)
            assertFalse(after?.hasThumbnail == true)

            // Never again: not by a tap, not by itself, and a second mark hands out nothing.
            for (tap in listOf(true, false)) {
                val asked =
                    store
                        .enqueueMedia(ali, "ONCE", now + 4, tap = tap, network = WaSyncNetwork.Wifi)
                        .getOrThrow()
                assertFalse(asked.queued)
                assertEquals(WaSyncMediaDecision.Never, asked.decision)
                assertEquals(WaSyncMediaState.Gone, asked.media.state)
            }
            assertTrue(
                store
                    .dueMedia(now + 5)
                    .getOrThrow()
                    .items
                    .isEmpty(),
            )
            val again = store.markViewed(ali, "ONCE", now + 6).getOrThrow()
            assertFalse(again.first)
            assertNull(again.delete)
            // A download that lands after the message was opened is handed back.
            val late = store.settleMedia(ali, "ONCE", WaSyncFetched.Ready("/m/late.jpg", 1_000), now + 7).getOrThrow()
            assertEquals("/m/late.jpg", late.replaced)
            assertEquals(WaSyncMediaState.Gone, late.media.state)

            assertTrue(invalid(store.markViewed(ali, "PHOTO", now)))
            assertTrue((store.markViewed(ali, "NOPE", now) as Outcome.Err).error is RustError.NotFound)
        }

    @Test
    fun theGalleryOfAChatPagesBothWays() =
        runTest {
            val fake = FakeWaSyncOps()
            for (i in 1..6) fake.putMedia(ali, "P$i", i * 1_000L, WaSyncKind.Image)
            fake.putMedia(ali, "VID", 3_500, WaSyncKind.Video)
            fake.putMedia(ali, "DOC", 4_500, WaSyncKind.Document)
            fake.putMedia(ali, "VOICE", 5_500, WaSyncKind.Audio, MediaMeta(voiceNote = true))
            fake.putMedia(ali, "STICKER", 5_600, WaSyncKind.Sticker)
            fake.put(ali, "TEXT", 5_700, "hi")
            fake.putMedia(ali, "ONCE", 5_900, WaSyncKind.Image, MediaMeta(viewOnce = true))
            fake.messages.getValue(ali).replaceAll { if (it.id == "P1") it.copy(deleted = true) else it }
            val store = open(fake)

            suspend fun ids(
                limit: Int,
                before: WaSyncCursor? = null,
                after: WaSyncCursor? = null,
                kinds: List<WaSyncKind> = emptyList(),
            ) = store.chatMedia(ali, limit, before, after, kinds).getOrThrow().let { page ->
                Triple(page.items.map { it.id }, page.older, page.newer)
            }

            // Photos and videos, newest first; no deleted, view-once, sticker or text row.
            assertEquals(Triple(listOf("P6", "P5", "P4", "VID", "P3", "P2"), null, null), ids(50))
            val all = listOf(WaSyncKind.Image, WaSyncKind.Video, WaSyncKind.Document, WaSyncKind.Audio)
            assertEquals(listOf("P6", "VOICE", "P5", "DOC", "P4", "VID", "P3", "P2"), ids(50, kinds = all).first)
            assertEquals(listOf("DOC"), ids(50, kinds = listOf(WaSyncKind.Document)).first)
            assertTrue(invalid(store.chatMedia(ali, 50, kinds = listOf(WaSyncKind.Sticker))))

            // Down from the newest, then back up.
            val first = ids(3)
            assertEquals(Triple(listOf("P6", "P5", "P4"), WaSyncCursor(4_000, "P4"), null), first)
            val second = ids(3, before = first.second)
            assertEquals(Triple(listOf("VID", "P3", "P2"), null, WaSyncCursor(3_500, "VID")), second)
            val up = ids(3, after = WaSyncCursor(2_000, "P2"))
            assertEquals(Triple(listOf("P4", "VID", "P3"), WaSyncCursor(3_000, "P3"), WaSyncCursor(4_000, "P4")), up)
            assertEquals(Triple(listOf("P6", "P5"), WaSyncCursor(5_000, "P5"), null), ids(3, after = up.third))

            // A viewer opened on the video asks for both sides of it.
            val opened = WaSyncCursor(3_500, "VID")
            assertEquals(listOf("P3", "P2"), ids(2, before = opened).first)
            assertEquals(listOf("P5", "P4"), ids(2, after = opened).first)
            // The old id of a merged chat reads the same gallery.
            fake.merge("777000000000001@lid", ali)
            assertEquals(
                6,
                store
                    .chatMedia("777000000000001@lid", 50)
                    .getOrThrow()
                    .items.size,
            )
        }
}
