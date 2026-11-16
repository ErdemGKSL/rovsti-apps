package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MediaOptions
import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaDecision
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.ops.NativeWaSyncOps
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

/**
 * WA-R3 against the real host library: the options of a media send through the outbox, the played receipt, view
 * once and the gallery of a chat. The same calls as `WaSyncMediaRoundFakeTest`, so the fake and the store agree.
 */
class WaSyncMediaRoundNativeTest {
    private val ops = NativeWaSyncOps(TestRust.core)
    private val ali = "100000000001@s.whatsapp.net"
    private val aliLid = "777000000000001@lid"
    private val group = "120000000000000001@g.us"
    private val now = 1_000_000L
    private val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1))
    private val samples = ByteArray(MediaWaveform.SAMPLES) { (it % 101).toByte() }
    private val waveform = MediaWaveform.encode(samples)

    @Before
    fun load() = TestRust.ensure()

    private fun <T> Outcome<T>.getOrOk(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> error("failed: $this")
        }

    private fun invalid(outcome: Outcome<*>): Boolean = (outcome as? Outcome.Err)?.error is RustError.Invalid

    private fun rejected() = WaSyncOutcome.Error(WaSyncErrorKind.Rejected, "no")

    private suspend fun open(): WaSyncStore {
        val dir =
            Files
                .createTempDirectory("rosty-wa-round")
                .toFile()
                .apply { deleteOnExit() }
                .path
        return WaSyncStore.open("inst1", dir = dir, ops = ops).getOrOk()
    }

    private fun media(
        chat: String,
        id: String,
        ts: Long,
        kind: MediaKind,
        fromMe: Boolean = false,
        sender: String? = if (fromMe) null else chat,
    ) = GatewayEvent.Message(
        id,
        chat,
        sender,
        fromMe,
        ts,
        MessageBody.Media(kind, null, "application/octet-stream", 1_000),
        emptyList(),
    )

    private fun text(
        chat: String,
        id: String,
        ts: Long,
    ) = GatewayEvent.Message(id, chat, chat, false, ts, MessageBody.Text("hi"), emptyList())

    private fun flags(
        chat: String,
        id: String,
        meta: MessageMeta,
    ) = GatewayEvent.MessageMetaChanged(chat, id, null, meta)

    private suspend fun WaSyncStore.apply(
        from: Long,
        vararg events: GatewayEvent,
    ) = applyEvents(events.mapIndexed { index, event -> WaSyncEvent(from + index, 1_000, event) }).getOrOk()

    @Test
    fun aMediaSendCarriesItsOptionsToTheGatewayCallAndKeepsThemOnTheMessage() =
        runBlocking {
            open().use { store ->
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
                store.enqueue("c1", ali, voice, 1_000).getOrOk()
                val expected = MediaMeta(seconds = 6, voiceNote = true, waveformB64 = waveform)
                val waiting =
                    store
                        .thread(ali, 10)
                        .getOrOk()
                        .items
                        .first()
                assertEquals(expected, waiting.media)
                assertEquals(WaSyncKind.Audio, waiting.kind)
                assertEquals(WaSyncRowState.Waiting, waiting.state)
                assertEquals("/o/v.ogg", waiting.mediaPath)

                val due =
                    store
                        .due(2_000)
                        .getOrOk()
                        .items
                        .single()
                assertEquals(
                    MediaOptions(seconds = 6, voiceNote = true, waveformB64 = waveform),
                    (due.action as WaSyncAction.SendMedia).options(),
                )
                store.settle("c1", WaSyncOutcome.Sent("S1", 2_100), 2_100).getOrOk()
                val sent = store.message(ali, "S1").getOrOk()
                assertEquals(expected, sent?.media)
                assertEquals("/o/v.ogg", sent?.mediaPath)

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
                store.enqueue("c2", ali, photo, 3_000).getOrOk()
                store.due(3_100).getOrOk()
                store.settle("c2", WaSyncOutcome.Sent("S2", 3_200), 3_200).getOrOk()
                val picture = store.message(ali, "S2").getOrOk()
                assertTrue(picture?.hasThumbnail == true)
                assertEquals(640 to 480, picture?.media?.width to picture?.media?.height)
                assertEquals(jpeg, store.media(ali, "S2").getOrOk().thumbnailB64)

                // What the gateway would refuse is refused when queued.
                val bare = WaSyncAction.SendMedia("/o/a.jpg", MediaKind.Image, "image/jpeg", 9)
                val png = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                assertTrue(invalid(store.enqueue("c3", ali, bare.copy(thumbnailB64 = png), now)))
                assertTrue(invalid(store.enqueue("c3", ali, bare.copy(waveformB64 = "AAEC"), now)))
                val document =
                    WaSyncAction.SendMedia("/o/a.pdf", MediaKind.Document, "application/pdf", 9, viewOnce = true)
                assertTrue(invalid(store.enqueue("c3", ali, document, now)))
                assertTrue(invalid(store.enqueue("c3", ali, WaSyncAction.MarkPlayed("V"), now)))
                assertTrue(store.outbox().getOrOk().isEmpty())

                // A view-once send does not keep its file, and its sender cannot open it.
                store.enqueue("c4", ali, bare.copy(file = "/o/once.jpg", viewOnce = true), 4_000).getOrOk()
                store.due(4_100).getOrOk()
                store.settle("c4", WaSyncOutcome.Sent("S4", 4_200), 4_200).getOrOk()
                val once = store.message(ali, "S4").getOrOk()
                assertTrue(once?.media?.viewOnce == true)
                assertNull(once?.mediaPath)
                val tapped = store.enqueueMedia(ali, "S4", now, tap = true).getOrOk()
                assertFalse(tapped.queued)
                assertEquals(WaSyncMediaDecision.Never, tapped.decision)
                assertEquals(listOf("/o/once.jpg"), store.reconcileMedia(listOf("/o/once.jpg")).getOrOk().delete)
            }
        }

    @Test
    fun aPlayedReceiptIsQueuedOncePerReceivedVoiceNote() =
        runBlocking {
            open().use { store ->
                val note = MessageMeta(media = MediaMeta(seconds = 4, voiceNote = true, waveformB64 = waveform))
                store.apply(
                    1,
                    media(ali, "V", 1_000, MediaKind.Audio),
                    flags(ali, "V", note),
                    media(ali, "SONG", 2_000, MediaKind.Audio),
                    media(ali, "MINE", 3_000, MediaKind.Audio, fromMe = true),
                    flags(ali, "MINE", note),
                    media(group, "G", 4_000, MediaKind.Audio, sender = ali),
                    flags(group, "G", note),
                    text(ali, "T", 5_000),
                )

                assertArrayEquals(
                    samples,
                    store
                        .message(ali, "V")
                        .getOrOk()
                        ?.media
                        ?.waveform(),
                )
                val first = store.markPlayed("p1", ali, "V", now).getOrOk()
                assertTrue(first.queued)
                assertEquals(WaSyncAction.MarkPlayed("V"), first.item?.action)
                assertTrue(store.message(ali, "V").getOrOk()?.played == true)
                assertEquals(first.item, store.markPlayed("p1", ali, "V", now + 1).getOrOk().item)
                val other = store.markPlayed("p2", ali, "V", now + 2).getOrOk()
                assertFalse(other.queued)
                assertNull(other.item)
                assertEquals(1, store.outbox().getOrOk().size)

                val due =
                    store
                        .due(now + 3)
                        .getOrOk()
                        .items
                        .single { it.chat == ali }
                assertEquals(WaSyncAction.MarkPlayed("V"), due.action)
                assertEquals(WaSyncSettled.DONE, store.settle("p1", WaSyncOutcome.Sent(), now + 4).getOrOk().state)
                assertTrue(store.outbox().getOrOk().isEmpty())
                assertFalse(store.markPlayed("p3", ali, "V", now + 5).getOrOk().queued)
                assertTrue(
                    store
                        .thread(ali, 10)
                        .getOrOk()
                        .items
                        .none { it.id == "p1" },
                )

                for (id in listOf("SONG", "MINE", "T")) assertTrue(id, invalid(store.markPlayed("x", ali, id, now)))
                assertTrue((store.markPlayed("x", ali, "NOPE", now) as Outcome.Err).error is RustError.NotFound)

                val inGroup = store.markPlayed("g1", group, "G", now).getOrOk()
                assertEquals(WaSyncAction.MarkPlayed("G", sender = ali), inGroup.item?.action)
                store.due(now + 6).getOrOk()
                assertEquals(WaSyncSettled.FAILED, store.settle("g1", rejected(), now + 7).getOrOk().state)
                assertTrue(store.outbox().getOrOk().isEmpty())
                assertTrue(store.message(group, "G").getOrOk()?.played == true)
                assertFalse(store.markPlayed("g2", group, "G", now + 8).getOrOk().queued)
            }
        }

    @Test
    fun viewOnceIsFetchedByATapOnlyAndOpensOnce() =
        runBlocking {
            open().use { store ->
                store.apply(
                    1,
                    media(ali, "ONCE", 1_000, MediaKind.Image),
                    flags(ali, "ONCE", MessageMeta(media = MediaMeta(viewOnce = true))),
                    media(ali, "PHOTO", 2_000, MediaKind.Image),
                )
                store.setMedia(ali, "ONCE", now, thumbnailB64 = jpeg).getOrOk()

                val auto = store.enqueueMedia(ali, "ONCE", now, network = WaSyncNetwork.Wifi).getOrOk()
                assertFalse(auto.queued)
                assertEquals(WaSyncMediaDecision.Never, auto.decision)
                assertEquals(WaSyncMediaDecision.Never, store.mediaPolicy(ali, "ONCE", WaSyncNetwork.Wifi).getOrOk())
                val tapped = store.enqueueMedia(ali, "ONCE", now, tap = true).getOrOk()
                assertTrue(tapped.queued)
                assertEquals(WaSyncMediaDecision.OnTap, tapped.decision)
                assertEquals(
                    1,
                    store
                        .dueMedia(now + 1, network = WaSyncNetwork.Wifi)
                        .getOrOk()
                        .items.size,
                )
                store.settleMedia(ali, "ONCE", WaSyncFetched.Ready("/m/once.jpg", 1_000), now + 2).getOrOk()
                val before = store.message(ali, "ONCE").getOrOk()
                assertEquals("/m/once.jpg", before?.mediaPath)
                assertTrue(before?.hasThumbnail == true && before.viewed.not())

                val viewed = store.markViewed(ali, "ONCE", now + 3).getOrOk()
                assertTrue(viewed.first)
                assertEquals("/m/once.jpg", viewed.delete)
                assertEquals(WaSyncMediaState.Gone, viewed.media.state)
                assertNull(viewed.media.path)
                val after = store.message(ali, "ONCE").getOrOk()
                assertTrue(after?.viewed == true)
                assertNull(after?.mediaPath)
                assertEquals(WaSyncMediaState.Gone, after?.mediaState)
                assertFalse(after?.hasThumbnail == true)

                for (tap in listOf(true, false)) {
                    val asked =
                        store
                            .enqueueMedia(ali, "ONCE", now + 4, tap = tap, network = WaSyncNetwork.Wifi)
                            .getOrOk()
                    assertFalse(asked.queued)
                    assertEquals(WaSyncMediaDecision.Never, asked.decision)
                    assertEquals(WaSyncMediaState.Gone, asked.media.state)
                }
                assertTrue(
                    store
                        .dueMedia(now + 5)
                        .getOrOk()
                        .items
                        .isEmpty(),
                )
                val again = store.markViewed(ali, "ONCE", now + 6).getOrOk()
                assertFalse(again.first)
                assertNull(again.delete)
                val late = store.settleMedia(ali, "ONCE", WaSyncFetched.Ready("/m/late.jpg", 1_000), now + 7).getOrOk()
                assertEquals("/m/late.jpg", late.replaced)
                assertEquals(WaSyncMediaState.Gone, late.media.state)

                assertTrue(invalid(store.markViewed(ali, "PHOTO", now)))
                assertTrue((store.markViewed(ali, "NOPE", now) as Outcome.Err).error is RustError.NotFound)
            }
        }

    @Test
    fun theGalleryOfAChatPagesBothWays() =
        runBlocking {
            open().use { store ->
                val photos = (1..6).map { media(ali, "P$it", it * 1_000L, MediaKind.Image) }
                store.apply(
                    1,
                    *photos.toTypedArray(),
                    media(ali, "VID", 3_500, MediaKind.Video),
                    media(ali, "DOC", 4_500, MediaKind.Document),
                    media(ali, "VOICE", 5_500, MediaKind.Audio),
                    media(ali, "STICKER", 5_600, MediaKind.Sticker),
                    text(ali, "TEXT", 5_700),
                    media(ali, "ONCE", 5_900, MediaKind.Image),
                    flags(ali, "ONCE", MessageMeta(media = MediaMeta(viewOnce = true))),
                    flags(ali, "P1", MessageMeta(deleted = true)),
                    // Under the LID of the same person, before the store knows whose it is.
                    media(aliLid, "L1", 500, MediaKind.Image),
                )

                suspend fun ids(
                    limit: Int,
                    before: WaSyncCursor? = null,
                    after: WaSyncCursor? = null,
                    kinds: List<WaSyncKind> = emptyList(),
                ) = store.chatMedia(ali, limit, before, after, kinds).getOrOk().let { page ->
                    Triple(page.items.map { it.id }, page.older, page.newer)
                }

                assertEquals(Triple(listOf("P6", "P5", "P4", "VID", "P3", "P2"), null, null), ids(50))
                val all = listOf(WaSyncKind.Image, WaSyncKind.Video, WaSyncKind.Document, WaSyncKind.Audio)
                assertEquals(listOf("P6", "VOICE", "P5", "DOC", "P4", "VID", "P3", "P2"), ids(50, kinds = all).first)
                assertEquals(listOf("DOC"), ids(50, kinds = listOf(WaSyncKind.Document)).first)
                assertTrue(invalid(store.chatMedia(ali, 50, kinds = listOf(WaSyncKind.Sticker))))

                val first = ids(3)
                assertEquals(Triple(listOf("P6", "P5", "P4"), WaSyncCursor(4_000, "P4"), null), first)
                val second = ids(3, before = first.second)
                assertEquals(Triple(listOf("VID", "P3", "P2"), null, WaSyncCursor(3_500, "VID")), second)
                val up = ids(3, after = WaSyncCursor(2_000, "P2"))
                assertEquals(
                    Triple(listOf("P4", "VID", "P3"), WaSyncCursor(3_000, "P3"), WaSyncCursor(4_000, "P4")),
                    up,
                )
                assertEquals(Triple(listOf("P6", "P5"), WaSyncCursor(5_000, "P5"), null), ids(3, after = up.third))

                val opened = WaSyncCursor(3_500, "VID")
                assertEquals(listOf("P3", "P2"), ids(2, before = opened).first)
                assertEquals(listOf("P5", "P4"), ids(2, after = opened).first)

                // The LID chat merges into the number: both ids read one gallery, the LID's photo included.
                store.apply(100, GatewayEvent.IdentityMapped(aliLid, ali, "usync", 1_000))
                val merged = store.chatMedia(aliLid, 50).getOrOk().items
                assertEquals(listOf("P6", "P5", "P4", "VID", "P3", "P2", "L1"), merged.map { it.id })
                assertTrue(merged.all { it.chat == ali })
            }
        }
}
