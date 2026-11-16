package dev.rosty.messages.wa

import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.Unavailable
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleMediaKind
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationEffect
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.MediaAction
import dev.rosty.messages.conversation.stickerSize
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.inbox.waPreview
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.media.FakeMediaProbe
import dev.rosty.platform.media.MediaMeasure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * E6-WA R3 steps 6 and 7 on the view model: stickers, the view-once placeholder, a view-once send of mine, and the send
 * preview of several picked files through the outbox.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaViewOnceStickerTest {
    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")
    private val minute = 60_000L
    private var staged: (String) -> WaStageResult = { WaStageResult.Failed }
    private val probe = FakeMediaProbe()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private fun model(kit: WaTestKit): WaThreadViewModel {
        kit.offline.start()
        return WaThreadViewModel(
            WaThreadSources(
                offline = kit.offline,
                remote = kit.remote,
                drafts = MemoryDrafts(),
                names = PersonNames({ null }, MutableStateFlow(Unit)),
                now = { kit.now },
                media = WaMedia(kit.offline, kit.remote, checkNotNull(kit.downloads), { kit.now }, io = dispatcher),
                emoji = WaRecentEmoji(null, dispatcher),
                stage = { staged(it) },
                probe = probe,
            ),
            ADA,
            EnglishWaWords,
            EnglishInboxWords,
            days,
        )
    }

    private fun kit(backgroundScope: kotlinx.coroutines.CoroutineScope) =
        WaTestKit.withMedia(backgroundScope, mediaDir = File(folder.root, "wa-media"), io = dispatcher).first

    private fun bubble(
        model: WaThreadViewModel,
        key: String,
    ): BubbleUi =
        model.state.value.items
            .mapNotNull { (it as? ConversationItem.Bubble)?.bubble }
            .first { it.key == key }

    private fun TestScope.mediaEffectsOf(model: WaThreadViewModel): List<WaMediaEffect> {
        val effects = mutableListOf<WaMediaEffect>()
        backgroundScope.launch { model.mediaEffects.collect { effects += it } }
        return effects
    }

    private fun TestScope.effectsOf(model: WaThreadViewModel): List<ConversationEffect> {
        val effects = mutableListOf<ConversationEffect>()
        backgroundScope.launch { model.effects.collect { effects += it } }
        return effects
    }

    private fun placeholder(
        id: String,
        reason: Unavailable,
        fromMe: Boolean = false,
    ) = WaSyncMessage(
        id = id,
        chat = ADA,
        sender = if (fromMe) null else ADA,
        fromMe = fromMe,
        timestampMs = NOW - 5 * minute,
        kind = WaSyncKind.Unsupported,
        reactions = emptyList(),
        state = if (fromMe) WaSyncRowState.Sent else WaSyncRowState.Received,
        unavailable = reason,
    )

    private fun staged(
        name: String,
        mime: String,
        bytes: Long = 4,
    ): WaStagedFile {
        // Under the media folder, where the stager puts its copies (only those are deleted).
        val file = File(File(folder.root, "wa-media/staged-$name"), name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(4))
        return WaStagedFile(file, name, mime, bytes)
    }

    /** A preview that reads as a JPEG (the store checks the magic bytes), told apart by [n]. */
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte())

    // ---- stickers

    @Test fun aStickerKeepsItsAspectInsideTheSquare() {
        assertEquals(160f to 160f, stickerSize(512, 512, 160f).let { it.width to it.height })
        assertEquals(160f to 80f, stickerSize(512, 256, 160f).let { it.width to it.height })
        assertEquals(80f to 160f, stickerSize(256, 512, 160f).let { it.width to it.height })
        // Unknown or broken dimensions: the full square.
        assertEquals(160f to 160f, stickerSize(null, 512, 160f).let { it.width to it.height })
        assertEquals(160f to 160f, stickerSize(0, 0, 160f).let { it.width to it.height })
    }

    @Test fun aStickerIsDrawnAsOneAndATapOpensNothing() =
        scope.runTest {
            val kit = kit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(
                WaTestKit.media(
                    ADA,
                    "s1",
                    NOW - minute,
                    WaSyncKind.Sticker,
                    "image/webp",
                    20_000,
                    MediaMeta(width = 512, height = 512, animated = true),
                ),
            )
            val model = model(kit)
            val effects = mediaEffectsOf(model)
            settle()
            val media = bubble(model, "s1").media.single()
            assertEquals(BubbleMediaKind.Sticker, media.kind)
            assertEquals(512, media.width)
            // Not on the phone yet: nothing to animate.
            assertNull(media.animated)
            assertFalse(model.openMedia("s1"))
            settle()
            assertTrue(effects.isEmpty())
        }

    // ---- view once

    @Test fun aViewOncePlaceholderSaysToOpenItOnThePhoneAndOffersNothing() =
        scope.runTest {
            val kit = kit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(placeholder("vo", Unavailable.ViewOnce))
            kit.message(placeholder("mine", Unavailable.ViewOnce, fromMe = true))
            kit.message(placeholder("bot", Unavailable.Bot))
            val model = model(kit)
            val effects = mediaEffectsOf(model)
            settle()
            val vo = bubble(model, "vo")
            assertEquals("", vo.text)
            val row = vo.media.single()
            assertEquals(BubbleMediaKind.Unavailable, row.kind)
            assertEquals("View once message. Open it on your phone.", row.label)
            assertTrue(row.viewOnce)
            assertEquals("View once message", bubble(model, "mine").media.single().label)
            val bot = bubble(model, "bot").media.single()
            assertEquals("This message is not available on this device. Open it on your phone.", bot.label)
            assertFalse(bot.viewOnce)
            // No viewer, no download, no Save, Share or Forward.
            assertFalse(model.openMedia("vo"))
            model.mediaAction("vo", MediaAction.Save)
            settle()
            assertTrue(effects.isEmpty())
            assertTrue(kit.remote.mediaCalls.isEmpty())
            assertTrue(vo.canReply)
        }

    @Test fun aViewOnceSendOfMineCannotBeOpenedAgain() =
        scope.runTest {
            val kit = kit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(
                WaTestKit.media(
                    ADA,
                    "once",
                    NOW - minute,
                    WaSyncKind.Video,
                    "video/mp4",
                    3_000_000,
                    MediaMeta(viewOnce = true, seconds = 4),
                    caption = "look",
                    fromMe = true,
                ),
            )
            val model = model(kit)
            val effects = mediaEffectsOf(model)
            settle()
            val bubble = bubble(model, "once")
            assertEquals("View once video", bubble.media.single().label)
            assertEquals(BubbleMediaKind.Unavailable, bubble.media.single().kind)
            assertEquals("look", bubble.text)
            assertFalse(model.openMedia("once"))
            model.mediaAction("once", MediaAction.Forward)
            settle()
            assertTrue(effects.isEmpty())
        }

    @Test fun theInboxSaysViewOnceMessage() {
        val meta = MessageMeta(unavailable = Unavailable.ViewOnce)
        assertEquals("View once message", waPreview(MessageBody.Unsupported, meta, EnglishInboxWords))
        assertEquals(
            "Message not available here",
            waPreview(MessageBody.Unsupported, MessageMeta(unavailable = Unavailable.Hosted), EnglishInboxWords),
        )
        val photo = MessageBody.Media(MediaKind.Image, null, "image/jpeg", 10)
        assertEquals(
            "View once photo",
            waPreview(photo, MessageMeta(media = MediaMeta(viewOnce = true)), EnglishInboxWords),
        )
        assertEquals("Photo", waPreview(photo, MessageMeta(), EnglishInboxWords))
        // The chat list row carries the reason into the preview.
        assertEquals(Unavailable.ViewOnce, placeholder("vo", Unavailable.ViewOnce).toMeta().unavailable)
    }

    // ---- several files

    @Test fun pickedFilesOpenThePreviewAndGoOutInOrderWithTheirOptions() =
        scope.runTest {
            val kit = kit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 10 * minute, "Send the pictures"))
            val files =
                mapOf(
                    "content://p/1" to staged("harbour.jpg", "image/jpeg"),
                    "content://p/2" to staged("ferry.mp4", "video/mp4"),
                    "content://p/3" to staged("timetable.pdf", "application/pdf"),
                    "content://p/4" to staged("huge.mp4", "video/mp4", WaMedia.MAX_SEND_BYTES + 1),
                )
            staged = { uri -> files[uri]?.let { WaStageResult.Staged(it) } ?: WaStageResult.TooLarge }
            probe.answers["harbour.jpg"] = MediaMeasure(1_600, 1_200, null, jpeg(1))
            probe.answers["ferry.mp4"] = MediaMeasure(720, 1_280, 12, jpeg(2))
            val model = model(kit)
            val effects = effectsOf(model)
            settle()
            model.startReply("m1")
            model.pick(listOf("content://p/1", "content://p/2", "content://p/3", "content://p/4", "content://p/5"))
            settle()

            val batch = checkNotNull(model.batch.value)
            assertEquals(listOf("harbour.jpg", "ferry.mp4", "timetable.pdf"), batch.items.map { it.file.name })
            // A document is not measured; the two over the limit are left out with one pill.
            assertEquals(listOf("harbour.jpg", "ferry.mp4"), probe.measured)
            assertEquals(
                listOf(ConversationEffect.Toast(UiText.Plural(R.plurals.wa_batch_too_large, 2, listOf(16)))),
                effects,
            )
            assertFalse(files.getValue("content://p/4").file.exists())

            val video = batch.items[1].id
            model.batchCaption(video, "Our ferry")
            model.batchViewOnce(video)
            model.batchMove(batch.items[2].id, -2)
            model.batchSend()
            settle()

            assertNull(model.batch.value)
            val sent =
                kit.ops.writes
                    .filterIsInstance<WaSyncEnqueueRequest>()
                    .map { it.action }
                    .filterIsInstance<WaSyncAction.SendMedia>()
            assertEquals(listOf("timetable.pdf", "harbour.jpg", "ferry.mp4"), sent.map { it.fileName })
            assertEquals(listOf("m1", null, null), sent.map { it.quotedId })
            val clip = sent[2]
            assertEquals("Our ferry", clip.caption)
            assertTrue(clip.viewOnce)
            assertEquals(12, clip.seconds)
            assertEquals(720 to 1_280, clip.width to clip.height)
            assertEquals("/9j/Ag==", clip.thumbnailB64)
            assertEquals("/9j/AQ==", sent[1].thumbnailB64)
            // The quoted bar went with the first file.
            assertNull(model.state.value.reply)
        }

    @Test fun removingTheLastFileOrClosingDeletesTheCopies() =
        scope.runTest {
            val kit = kit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val one = staged("a.jpg", "image/jpeg")
            val two = staged("b.jpg", "image/jpeg")
            val files = mapOf("content://a" to one, "content://b" to two)
            staged = { uri -> WaStageResult.Staged(files.getValue(uri)) }
            val model = model(kit)
            settle()
            model.pick(listOf("content://a"))
            settle()
            model.batchRemove(checkNotNull(model.batch.value).items.single().id)
            settle()
            assertNull(model.batch.value)
            assertFalse(one.file.exists())

            model.pick(listOf("content://b"))
            settle()
            model.batchClose()
            settle()
            assertNull(model.batch.value)
            assertFalse(two.file.exists())
            assertTrue(
                kit.ops.writes
                    .filterIsInstance<WaSyncEnqueueRequest>()
                    .isEmpty(),
            )
        }
}
