package dev.rosty.messages.wa

import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.Reaction
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncEvicted
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleMediaKind
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationEffect
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.MediaAction
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
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
 * E6.4b on the view model: replies, reactions, the files that go out and the media that came in, all through the
 * offline store and its outbox (the fakes of [WaTestKit], with the media rules of the Rust store in [MediaStoreOps]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaThreadMediaTest {
    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")
    private val minute = 60_000L
    private val emoji = WaRecentEmoji(null, dispatcher)

    /** What the page's stager answers for a picked URI. */
    private var staged: (String) -> WaStageResult = { WaStageResult.Failed }

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private fun mediaOf(kit: WaTestKit) =
        WaMedia(kit.offline, kit.remote, File(folder.root, "wa-media"), { kit.now }, io = dispatcher)

    private fun model(
        kit: WaTestKit,
        jid: String = ADA,
        media: WaMedia? = mediaOf(kit),
        voice: WaVoiceNotes? = null,
    ): WaThreadViewModel {
        kit.offline.start()
        return WaThreadViewModel(
            WaThreadSources(
                offline = kit.offline,
                remote = kit.remote,
                drafts = MemoryDrafts(),
                nameOf = { mapOf("+905550000001" to "Ada Lovelace")[it] },
                bookChanges = MutableStateFlow(Unit),
                now = { kit.now },
                media = media,
                voice = voice,
                emoji = emoji,
                stage = { staged(it) },
                readable = { it.replace("+90555", "+90 555 ") },
            ),
            jid,
            EnglishWaWords,
            EnglishInboxWords,
            days,
        )
    }

    private fun bubbles(state: ConversationUi): List<BubbleUi> =
        state.items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }

    private fun bubble(
        model: WaThreadViewModel,
        key: String,
    ) = bubbles(model.state.value).first { it.key == key }

    private fun TestScope.effectsOf(model: WaThreadViewModel): List<ConversationEffect> {
        val effects = mutableListOf<ConversationEffect>()
        backgroundScope.launch { model.effects.collect { effects += it } }
        return effects
    }

    private fun TestScope.mediaEffectsOf(model: WaThreadViewModel): List<WaMediaEffect> {
        val effects = mutableListOf<WaMediaEffect>()
        backgroundScope.launch { model.mediaEffects.collect { effects += it } }
        return effects
    }

    private fun sends(kit: WaTestKit) =
        kit.ops.writes
            .filterIsInstance<WaSyncEnqueueRequest>()
            .map { it.action }

    private fun picked(
        name: String,
        mime: String,
        bytes: Int = 4,
    ): WaStagedFile {
        val file = File(folder.newFolder(), name)
        file.writeBytes(ByteArray(bytes))
        return WaStagedFile(file, name, mime, bytes.toLong())
    }

    // ---- replies (M31)

    @Test fun theHeaderShowsTheNumberAsPeopleReadIt() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 10 * minute, "Hi"))
            val model = model(kit)
            settle()
            assertEquals("Ada Lovelace", model.state.value.title)
            assertEquals("+90 555 0000001", model.state.value.subtitle)
        }

    @Test fun aSwipeQuotesTheMessageAndTheReplyCarriesIt() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 10 * minute, "Shall we take the 9:40 ferry?"))
            kit.message(WaTestKit.text(ADA, "m2", NOW - 9 * minute, "I can", fromMe = true))
            val model = model(kit)
            settle()
            assertTrue(bubble(model, "m1").canReply)

            model.startReply("m1")
            settle()
            val reply = model.state.value.reply
            assertEquals("m1", reply?.key)
            assertEquals("Ada Lovelace", reply?.author)
            assertEquals("Shall we take the 9:40 ferry?", reply?.text)

            model.send("Yes, the 9:40")
            settle()
            assertEquals(WaSyncAction.SendText("Yes, the 9:40", quotedId = "m1"), sends(kit).last())
            // The bar is gone and the queued reply already shows whom and what it answers.
            assertNull(model.state.value.reply)
            val queued = bubbles(model.state.value).first { it.text == "Yes, the 9:40" }
            assertEquals(BubbleState.Waiting, queued.state)
            assertEquals("Ada Lovelace", queued.quote?.author)
            assertEquals("Shall we take the 9:40 ferry?", queued.quote?.text)
            assertEquals("m1", queued.quote?.targetKey)
            // A message that has not gone out cannot be answered.
            assertFalse(queued.canReply)
        }

    @Test fun theQuotedBarCanBeClosedAndMyOwnMessageIsQuotedAsYou() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 10 * minute, "On my way", fromMe = true))
            val model = model(kit)
            settle()

            model.startReply("m1")
            settle()
            assertEquals(
                "You",
                model.state.value.reply
                    ?.author,
            )
            model.cancelReply()
            settle()
            assertNull(model.state.value.reply)

            model.send("plain")
            settle()
            assertEquals(WaSyncAction.SendText("plain"), sends(kit).last())
        }

    // ---- reactions (M32, M49)

    @Test fun aDoubleTapReactsWithTheDefaultEmojiAndASecondOneTakesItBack() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 10 * minute, "Done!"))
            kit.message(
                WaTestKit
                    .text(
                        ADA,
                        "m2",
                        NOW - 9 * minute,
                        "Thanks",
                    ).copy(reactions = listOf(Reaction(null, true, "👍"))),
            )
            val model = model(kit)
            settle()

            // One tap is a tap; two taps too far apart are two taps.
            assertFalse(model.tap("m1"))
            kit.now += WaThreadViewModel.DOUBLE_TAP_MS + 1
            assertFalse(model.tap("m1"))
            kit.now += 120
            assertTrue(model.tap("m1"))
            settle()
            assertEquals(WaSyncAction.React("m1", false, ADA, "👍"), sends(kit).last())

            // On a message I already reacted to with it, the double tap removes the reaction.
            assertFalse(model.tap("m2"))
            kit.now += 100
            assertTrue(model.tap("m2"))
            settle()
            assertEquals(WaSyncAction.React("m2", false, ADA, ""), sends(kit).last())
        }

    @Test fun theEmojiUsedLastLeadTheReactionBar() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 10 * minute, "We won"))
            val model = model(kit)
            settle()
            assertEquals(WaRecentEmoji.STANDARD, model.recentEmoji.value)

            model.react("m1", "🎉")
            settle()
            model.react("m1", "😂")
            settle()
            assertEquals(listOf("😂", "🎉", "👍", "❤️", "😮", "😢"), model.recentEmoji.value)
            // Taking a reaction back uses no emoji.
            model.react("m1", "")
            settle()
            assertEquals("😂", model.recentEmoji.value.first())
        }

    // ---- files that go out

    @Test fun aPickedPictureWaitsAboveTheComposerAndGoesOutWithItsCaption() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 10 * minute, "Send me the view"))
            val model = model(kit)
            settle()
            val photo = picked("harbour.jpg", "image/jpeg", bytes = 2_048)
            staged = { WaStageResult.Staged(photo) }

            model.attach("content://picked/1")
            settle()
            val waiting = model.state.value.attachment
            assertEquals("harbour.jpg", waiting?.name)
            assertEquals("2 KB", waiting?.detail)
            assertEquals(photo.file, waiting?.model)

            model.startReply("m1")
            model.send("From the pier")
            settle()
            assertEquals(
                WaSyncAction.SendMedia(
                    file = photo.file.absolutePath,
                    media = MediaKind.Image,
                    mime = "image/jpeg",
                    size = 2_048,
                    caption = "From the pier",
                    quotedId = "m1",
                    fileName = "harbour.jpg",
                ),
                sends(kit).last(),
            )
            assertNull(model.state.value.attachment)
            // The bubble is there at once, with the picture itself and a clock.
            val queued = bubbles(model.state.value).first { it.text == "From the pier" }
            assertEquals(BubbleState.Waiting, queued.state)
            assertEquals("Waiting for connection", queued.meta)
            val media = queued.media.single()
            assertEquals(BubbleMediaKind.Picture, media.kind)
            assertEquals(photo.file, media.model)
            assertTrue(media.local)
        }

    @Test fun aFileGoesOutWithoutAnyText() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val model = model(kit)
            settle()
            val pdf = picked("timetable.pdf", "application/pdf", bytes = 3 * 1024 * 1024)
            staged = { WaStageResult.Staged(pdf) }
            model.attach("content://picked/2")
            settle()

            model.send("")
            settle()
            val action = sends(kit).last() as WaSyncAction.SendMedia
            assertEquals(MediaKind.Document, action.media)
            assertNull(action.caption)
            // An outbox row carries no file name: the row reads it from the staged file.
            val media = bubbles(model.state.value).single().media.single()
            assertEquals(BubbleMediaKind.File, media.kind)
            assertEquals("timetable.pdf", media.label)
            assertEquals("PDF · 3.0 MB", media.detail)
        }

    @Test fun aFileOverSixteenMegabytesIsRefusedWithAPill() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val model = model(kit)
            settle()
            val effects = effectsOf(model)
            val tooLarge = ConversationEffect.Toast(UiText.Res(R.string.wa_media_too_large, listOf(16)))

            // The copy stopped at the limit.
            staged = { WaStageResult.TooLarge }
            model.attach("content://picked/huge")
            settle()
            assertEquals(tooLarge, effects.last())
            assertNull(model.state.value.attachment)

            // A stager that copied it anyway: the size is checked again and the copy deleted.
            val big = picked("film.mp4", "video/mp4").copy(size = WaMedia.MAX_SEND_BYTES + 1)
            val inStore = File(File(folder.root, "wa-media"), "film.mp4").also { it.parentFile?.mkdirs() }
            big.file.copyTo(inStore)
            staged = { WaStageResult.Staged(big.copy(file = inStore)) }
            model.attach("content://picked/huge2")
            settle()
            assertEquals(2, effects.count { it == tooLarge })
            assertNull(model.state.value.attachment)
            assertFalse(inStore.exists())
            // Exactly the limit is fine.
            staged = { WaStageResult.Staged(big.copy(size = WaMedia.MAX_SEND_BYTES)) }
            model.attach("content://picked/edge")
            settle()
            assertEquals(
                "film.mp4",
                model.state.value.attachment
                    ?.name,
            )
            assertTrue(sends(kit).isEmpty())
        }

    @Test fun aRefusedFileSaysNotSentAndGoesOutAgainOnATap() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val model = model(kit)
            settle()
            kit.connect()
            settle()
            kit.remote.answer = { _, _ ->
                dev.rosty.core.Outcome
                    .Err(
                        dev.rosty.core.RustError
                            .Invalid("unsupported media"),
                    )
            }
            val photo = picked("view.jpg", "image/jpeg")
            staged = { WaStageResult.Staged(photo) }
            model.attach("content://picked/3")
            settle()
            model.send("")
            settle()

            val failed = bubbles(model.state.value).single()
            assertEquals(BubbleState.Failed, failed.state)
            assertEquals("Not sent · Tap to retry", failed.meta)
            assertEquals(photo.file, failed.media.single().model)

            kit.remote.answer = { _, _ ->
                dev.rosty.core.Outcome.Ok(
                    dev.rosty.core.model.wa.WaSyncOutcome
                        .Sent("sent-1", NOW),
                )
            }
            model.retry(failed.key)
            settle()
            assertEquals(BubbleState.Sent, bubbles(model.state.value).single().state)
            assertEquals(2, kit.remote.performed.size)
        }

    @Test fun editingAQueuedFilePutsItBackAboveTheComposer() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val model = model(kit)
            settle()
            val effects = effectsOf(model)
            val photo = picked("view.jpg", "image/jpeg")
            staged = { WaStageResult.Staged(photo) }
            model.attach("content://picked/4")
            settle()
            model.send("The veiw")
            settle()
            val queued = bubbles(model.state.value).single()

            model.cancel(queued.key, restore = true)
            settle()
            assertTrue(bubbles(model.state.value).isEmpty())
            assertEquals(ConversationEffect.Restore("The veiw"), effects.last())
            assertEquals(
                "view.jpg",
                model.state.value.attachment
                    ?.name,
            )
            assertTrue(photo.file.exists())
        }

    // ---- media that came in

    private val pictureMeta = MediaMeta(width = 640, height = 480, thumbnail = true)

    @Test fun aReceivedPictureShowsItsThumbnailThenThePictureThroughTheStore() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(
                WaTestKit.media(
                    ADA,
                    "p1",
                    NOW - 5 * minute,
                    WaSyncKind.Image,
                    "image/jpeg",
                    2_048,
                    pictureMeta,
                    "Look",
                ),
            )
            kit.remote.thumbnails["p1"] = BytesAnswer(3, dataB64 = "VEhN")
            kit.remote.files["p1"] = BytesAnswer(3, dataB64 = "SU1H")
            val model = model(kit)
            settle()

            // Without a connection the bubble still says what it is and keeps the picture's shape.
            var media = bubble(model, "p1").media.single()
            assertEquals(BubbleMediaKind.Picture, media.kind)
            assertEquals("Photo", media.label)
            assertNull(media.model)
            assertFalse(media.local)
            assertEquals(640f / 480f, media.aspect)
            assertEquals("Look", bubble(model, "p1").text)
            assertTrue(kit.remote.mediaCalls.isEmpty())

            kit.connect()
            settle()
            // The thumbnail went into the store as base64 and the download was named to it with its size.
            val sets = kit.ops.writes.filterIsInstance<WaSyncMediaSetRequest>()
            assertEquals("VEhN", sets.first { it.thumbnailB64 != null }.thumbnailB64)
            val stored = sets.first { it.path != null }
            assertEquals(3L, stored.bytes)
            assertEquals("IMG", File(stored.path!!).readText())
            assertTrue(File(stored.path!!).startsWith(File(folder.root, "wa-media")))
            media = bubble(model, "p1").media.single()
            assertTrue(media.local)
            assertEquals(File(stored.path!!), media.model)
            assertFalse(media.busy)
            // Once each, however often the page is read again.
            kit.offline.noteChanged(setOf(ADA))
            settle()
            assertEquals(listOf("p1"), kit.remote.thumbnailCalls)
            assertEquals(listOf("p1"), kit.remote.mediaCalls)
        }

    @Test fun aTapOnAPictureOpensTheViewerAndMarksItUsed() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(
                WaTestKit.media(ADA, "p1", NOW - 5 * minute, WaSyncKind.Image, "image/jpeg", 2_048, pictureMeta),
            )
            kit.remote.files["p1"] = BytesAnswer(3, dataB64 = "SU1H")
            kit.remote.down = true
            val model = model(kit)
            settle()
            val opened = mediaEffectsOf(model)
            val effects = effectsOf(model)

            // No connection and nothing cached: the tap says so.
            assertTrue(model.openMedia("p1"))
            settle()
            assertTrue(opened.isEmpty())
            assertEquals(ConversationEffect.Toast(UiText.Res(R.string.wa_media_cant_download)), effects.last())

            kit.remote.down = false
            kit.connect()
            settle()
            val downloads = kit.remote.mediaCalls.size
            kit.now += minute
            assertTrue(model.openMedia("p1"))
            settle()
            assertEquals(listOf<WaMediaEffect>(WaMediaEffect.ShowPictures("p1")), opened)
            // The cached file was used, not downloaded again, and the viewer pages over it.
            assertEquals(downloads, kit.remote.mediaCalls.size)
            assertEquals(listOf("p1"), waPicturesOf(model.state.value.items).map { it.first })
            // A text message has nothing to open.
            assertFalse(model.openMedia("nope"))
        }

    @Test fun aReceivedFileIsDownloadedForSaveAndShareAndTheCacheIsBroughtUnderItsCap() =
        scope.runTest {
            val (kit, store) = WaTestKit.withMedia(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(
                WaTestKit.media(
                    ADA,
                    "d1",
                    NOW - 5 * minute,
                    WaSyncKind.Document,
                    "application/pdf",
                    1_258_291,
                    MediaMeta(fileName = "Ferry timetable.pdf", pages = 2),
                ),
            )
            kit.remote.files["d1"] = BytesAnswer(4, dataB64 = "UERGIQ==")
            val model = model(kit)
            settle()
            kit.connect()
            settle()
            val row = bubble(model, "d1").media.single()
            assertEquals(BubbleMediaKind.File, row.kind)
            assertEquals("Ferry timetable.pdf", row.label)
            assertEquals("PDF · 1.2 MB", row.detail)
            // Documents are not downloaded on their own.
            assertFalse(row.local)
            assertTrue(kit.remote.mediaCalls.isEmpty())

            // The store lets an older file go when this one comes in.
            val old = File(File(folder.root, "wa-media"), "old.jpg").also { it.parentFile?.mkdirs() }
            old.writeText("old")
            store.evictNext += WaSyncEvicted(ADA, "p0", old.absolutePath, 3)
            val asked = mediaEffectsOf(model)
            model.mediaAction("d1", MediaAction.Save)
            settle()
            val save = asked.single() as WaMediaEffect.Save
            assertEquals("Ferry timetable.pdf", save.name)
            assertEquals("application/pdf", save.mime)
            assertFalse(save.picture)
            assertEquals("PDF!", save.file.readText())
            assertEquals("Ferry timetable.pdf", save.file.name)
            assertFalse(old.exists())
            assertTrue(bubble(model, "d1").media.single().local)

            model.mediaAction("d1", MediaAction.Share)
            settle()
            assertEquals(WaMediaEffect.Share(save.file, "application/pdf", "Ferry timetable.pdf"), asked.last())
            assertEquals(listOf("d1"), kit.remote.mediaCalls)
            // Opening it marked it used in the store's LRU.
            assertTrue(
                (kit.ops.media(WaSyncMediaRequest(1, ADA, "d1")) as dev.rosty.core.Outcome.Ok).value.path != null,
            )
        }

    // ---- the sender card (M50)

    @Test fun aGroupBubbleNamesItsSenderForTheCard() =
        scope.runTest {
            val (kit, _) = WaTestKit.withMedia(backgroundScope)
            kit.chat(GROUP, name = "Weekend hike", group = true)
            kit.message(WaTestKit.text(GROUP, "g1", NOW - 3 * minute, "Who brings water?", sender = ADA))
            kit.message(
                WaTestKit.text(GROUP, "g2", NOW - 2 * minute, "I can", sender = MERT, senderName = "Mert K."),
            )
            kit.message(WaTestKit.text(GROUP, "g3", NOW - minute, "Thanks", fromMe = true))
            val model = model(kit, GROUP)
            settle()

            assertEquals(ADA, bubble(model, "g1").senderId)
            assertNull(bubble(model, "g3").senderId)
            val ada = model.senderCard(ADA)
            assertEquals("Ada Lovelace", ada.name)
            assertEquals("+905550000001", ada.number)
            assertEquals("+90 555 0000001", ada.numberLabel)
            assertTrue(ada.saved)
            assertEquals(ADA, ada.avatar.waJid)
            // Not in the address book: the name WhatsApp carries, and the card offers to add them.
            val mert = model.senderCard(MERT)
            assertEquals("Mert K.", mert.name)
            assertFalse(mert.saved)
        }
}
