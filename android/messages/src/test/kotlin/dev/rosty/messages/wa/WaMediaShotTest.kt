package dev.rosty.messages.wa

import android.os.Looper
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.Quoted
import dev.rosty.core.model.wa.Reaction
import dev.rosty.core.model.wa.WaSyncDueRequest
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationTags
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.MediaTags
import dev.rosty.messages.conversation.ReplyTags
import dev.rosty.messages.conversation.VoiceUi
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.testing.ShotTest
import dev.rosty.testing.gradientImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/**
 * Screenshots of E6.4b and of the sender card of E6.6 (M50): the reply swipe, the quoted composer, files waiting to go
 * out, voice notes, received pictures and files. Every state is built by the real view model over the in-memory store
 * (with the media rules of the Rust store), a scripted gateway and the words of `strings.xml`.
 */
@Config(sdk = [35])
class WaMediaShotTest : ShotTest() {
    @get:Rule val folder = TemporaryFolder()

    /** The media files live in a folder that changes from run to run: a picture is drawn by its file name alone. */
    override val imageProducer: (Any, Int, Int) -> android.graphics.Bitmap? =
        { data, width, height -> gradientImage((data as? File)?.name ?: data, width, height) }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val scope: CoroutineScope = MainScope()
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private var staged: (String) -> WaStageResult = { WaStageResult.Failed }
    private val engine = FakeVoiceEngine()

    @After fun stop() = scope.cancel()

    private fun kit(): Pair<WaTestKit, MediaStoreOps> =
        WaTestKit.withMedia(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), graceMs = 0)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun model(
        kit: WaTestKit,
        jid: String,
    ): WaThreadViewModel {
        kit.offline.start()
        val locale = res.configuration.locales[0]
        val days =
            DayLabels(
                ZoneId.of("UTC"),
                locale,
                true,
                res.getString(R.string.conversation_today),
                res.getString(R.string.inbox_yesterday),
            )
        val book = mapOf("+905550000001" to "Ada Lovelace", "+905550000002" to "Mert Kaya")
        val unconfined = CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined)
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    drafts = MemoryDrafts(),
                    names = PersonNames({ book[it] }, MutableStateFlow(Unit)),
                    now = { kit.now },
                    media =
                        WaMedia(
                            kit.offline,
                            kit.remote,
                            File(folder.root, "wa-media"),
                            { kit.now },
                            io = Dispatchers.Unconfined,
                        ),
                    voice = WaVoiceNotes(unconfined, FakeVoiceRules(), engine, { SAVED_SPEED }, {}, tickMs = HOUR_MS),
                    emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                    stage = { staged(it) },
                    readable = { it.replace(Regex("^\\+90(\\d{3})(\\d{3})(\\d{2})(\\d{2})$"), "+90 $1 $2 $3 $4") },
                ),
                jid,
                ResourceWaWords(res),
                ResourceInboxWords(res),
                days,
            )
        idle()
        return model
    }

    private fun WaThreadViewModel.settled(): ConversationUi {
        idle()
        return state.value
    }

    @Composable
    private fun Thread(
        overlays: OverlayController,
        state: ConversationUi,
        text: String = "",
        voice: VoiceUi? = null,
        onSwipeReply: (BubbleUi) -> Unit = {},
        senderCard: (@Composable (BubbleUi) -> Unit)? = null,
    ) {
        val attach: ImmutableList<MenuItem> =
            remember { persistentListOf(MenuItem.Action("Photo", RostyIcons.Photo, onClick = {})) }
        OverlayHost(overlays) {
            ConversationScreen(
                state = state,
                composer = rememberTextFieldState(text),
                listState = rememberLazyListState(),
                actions =
                    remember(onSwipeReply, senderCard) {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { persistentListOf() },
                            onSwipeReply = onSwipeReply,
                            attachMenu = attach,
                            senderCard = senderCard,
                        )
                    },
                sendLabel = stringResource(R.string.conversation_send),
                olderLabel = stringResource(R.string.conversation_load_earlier),
                labels = rememberToolLabels(),
                voice = voice,
            )
        }
    }

    private fun snap(
        name: String,
        ime: Int = 0,
        fontScale: Float = 1f,
        content: @Composable (OverlayController) -> Unit,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { content(overlays) }
            if (ime > 0) setSimulatedIme(ime)
            advance(600)
            after(overlays)
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            if (ime > 0) setSimulatedIme(0)
        }
    }

    private fun file(
        name: String,
        bytes: Int = 4,
    ): File {
        val file = File(folder.newFolder(), name)
        file.writeBytes(ByteArray(bytes))
        return file
    }

    /** A short one-to-one chat about a ferry, with a reaction and a reply so the new parts sit among the E6.4a ones. */
    private fun adaChat(kit: WaTestKit) {
        kit.chat(ADA, name = "Ada", pushName = "Ada")
        kit.message(WaTestKit.text(ADA, "m1", NOW - day - 60 * minute, "Are we still on for Saturday?"))
        kit.message(
            WaTestKit.text(ADA, "m2", NOW - day - 58 * minute, "Yes! Ten at the harbour.", true, WaSyncRowState.Read),
        )
        kit.message(WaTestKit.text(ADA, "m4", NOW - 200 * minute, "The ferry timetable for the weekend"))
        kit.message(
            WaTestKit
                .text(ADA, "m6", NOW - 120 * minute, "The 9:40 one works for me", true, WaSyncRowState.Delivered)
                .copy(
                    quoted = Quoted("m4", ADA, "The ferry timetable for the weekend"),
                    reactions = listOf(Reaction(ADA, false, "👍")),
                ),
        )
        kit.message(WaTestKit.text(ADA, "m7", NOW - 30 * minute, "Shall I book the tickets tonight?"))
        kit.message(WaTestKit.text(ADA, "m8", NOW - 5 * minute, "Can't wait", true, WaSyncRowState.Sent))
    }

    private fun online(kit: WaTestKit) {
        kit.offline.start()
        kit.connect()
    }

    // ---- M31: swipe to reply, the quoted composer

    /** The finger is still down 80 dp to the right of where it touched the bubble: the reply arrow is fully in. */
    @Test fun replySwipe() {
        val (kit, _) = kit()
        adaChat(kit)
        online(kit)
        val state = model(kit, ADA).settled()
        assertTrue(state.items.filterIsInstance<ConversationItem.Bubble>().all { it.bubble.canReply })
        val asked = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            asked.clear()
            val overlays = OverlayController()
            composeFrame(dark = dark) { Thread(overlays, state, onSwipeReply = { b -> asked += b.key }) }
            advance(600)
            compose.onNodeWithTag(ConversationTags.bubble("m7")).performTouchInput {
                down(Offset(width * 0.2f, centerY))
                repeat(SWIPE_STEPS) { moveBy(Offset(SWIPE_STEP_DP * density, 0f)) }
            }
            advance(600)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e6.4-wa-reply-swipe-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            // Nothing is answered before the finger lifts; lifting it past the threshold answers that bubble, once.
            assertTrue(asked.isEmpty())
            compose.onNodeWithTag(ConversationTags.bubble("m7")).performTouchInput { up() }
            advance(600)
            assertEquals(listOf("m7"), asked)
        }
    }

    /** A short swipe is not a reply, and a bubble that has not gone out does not swipe at all. */
    @Test fun aShortSwipeAnswersNothing() {
        val (kit, _) = kit()
        adaChat(kit)
        val model = model(kit, ADA)
        model.send("Still typing this one")
        val state = model.settled()
        val queued =
            state.items
                .filterIsInstance<ConversationItem.Bubble>()
                .first()
                .bubble
        assertEquals(false, queued.canReply)
        val asked = mutableListOf<String>()
        composeFrame { Thread(OverlayController(), state, onSwipeReply = { asked += it.key }) }
        for (key in listOf("m7", queued.key)) {
            compose.onNodeWithTag(ConversationTags.bubble(key)).performTouchInput {
                down(Offset(width * 0.2f, centerY))
                repeat(SWIPE_STEPS) {
                    moveBy(
                        Offset(
                            (
                                if (key ==
                                    "m7"
                                ) {
                                    SHORT_STEP_DP
                                } else {
                                    SWIPE_STEP_DP
                                }
                            ) * density,
                            0f,
                        ),
                    )
                }
                up()
            }
            advance(600)
        }
        assertTrue(asked.isEmpty())
    }

    private fun quotedState(): ConversationUi {
        val (kit, _) = kit()
        adaChat(kit)
        online(kit)
        val model = model(kit, ADA)
        model.startReply("m7")
        val state = model.settled()
        assertNotNull(state.reply)
        return state
    }

    @Test fun quotedComposer() {
        val state = quotedState()
        assertEquals("Ada Lovelace", state.reply?.author)
        snap("e6.4-wa-quoted-composer", ime = 300, content = { Thread(it, state, text = "Yes, the two of us") })
        compose.onNodeWithTag(ReplyTags.Bar).assertIsDisplayed()
        compose.onNodeWithTag(ReplyTags.Cancel).assertIsDisplayed()
        // The newest bubble stays above the bar and the composer with the keyboard open.
        compose.onNodeWithTag(ConversationTags.bubble("m8")).assertIsDisplayed()
    }

    @Test fun quotedComposerFontScale2() {
        val state = quotedState()
        snap(
            "e6.4-wa-quoted-composer-fs2",
            fontScale = 2f,
            content = { Thread(it, state, text = "Yes, the two of us") },
        )
    }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun quotedComposerTurkish() {
        val (kit, _) = kit()
        adaChat(kit)
        online(kit)
        val model = model(kit, ADA)
        // My own message is quoted as "You".
        model.startReply("m8")
        val state = model.settled()
        assertEquals("Siz", state.reply?.author)
        snap("e6.4-wa-quoted-composer-tr", content = { Thread(it, state, text = "Sabırsızlanıyorum") })
    }

    // ---- files waiting to go out

    /**
     * No connection: a picture that was refused, a picture and a document waiting with a clock, and the next picture
     * above the composer with its own Send.
     */
    private fun pendingState(): ConversationUi {
        val (kit, _) = kit()
        adaChat(kit)
        val model = model(kit, ADA)
        runBlocking {
            kit.now = NOW - 3 * minute
            staged =
                { WaStageResult.Staged(WaStagedFile(file("tickets.jpg", 2_048), "tickets.jpg", "image/jpeg", 2_048)) }
            model.attach("content://picked/1")
            idle()
            model.send("")
            idle()
            // What the store does with a request the gateway refused.
            kit.ops.outboxDue(WaSyncDueRequest(1, kit.now))
            kit.ops.outboxSettle(
                WaSyncSettleRequest(1, kit.now, "c1", WaSyncOutcome.Error(WaSyncErrorKind.Rejected, "refused")),
            )
            kit.now = NOW - 2 * minute
            staged = {
                WaStageResult.Staged(WaStagedFile(file("harbour.jpg", 4_096), "harbour.jpg", "image/jpeg", 348_160))
            }
            model.attach("content://picked/2")
            idle()
            model.send("The harbour this morning")
            idle()
            kit.now = NOW - minute
            staged = {
                WaStageResult.Staged(
                    WaStagedFile(file("Ferry timetable.pdf"), "Ferry timetable.pdf", "application/pdf", 1_258_291),
                )
            }
            model.attach("content://picked/3")
            idle()
            model.send("")
            idle()
            kit.now = NOW
            staged = { WaStageResult.Staged(WaStagedFile(file("pier.jpg"), "pier.jpg", "image/jpeg", 2_411_724)) }
            model.attach("content://picked/4")
            idle()
            kit.offline.noteChanged(setOf(ADA))
        }
        return model.settled()
    }

    @Test fun mediaPending() {
        val state = pendingState()
        val bubbles = state.items.filterIsInstance<ConversationItem.Bubble>().map { it.bubble }
        assertEquals(
            listOf("Waiting", "Waiting", "Failed"),
            bubbles.take(3).map { it.state.name },
        )
        assertEquals("pier.jpg", state.attachment?.name)
        snap("e6.4-wa-media-pending", content = { Thread(it, state) })
        compose.onNodeWithTag(ReplyTags.Attachment).assertIsDisplayed()
        compose.onNodeWithTag(ReplyTags.AttachmentSend).assertIsDisplayed()
        compose.onNodeWithTag(ReplyTags.Attach).assertIsDisplayed()
    }

    // ---- voice notes (M17)

    @Test fun voiceNote() {
        val (kit, _) = kit()
        kit.chat(ADA, name = "Ada", pushName = "Ada")
        kit.message(WaTestKit.text(ADA, "m1", NOW - 40 * minute, "Did you hear the announcement?"))
        val note = file("note.ogg")
        kit.message(
            WaTestKit
                .media(ADA, "v1", NOW - 30 * minute, WaSyncKind.Audio, "audio/ogg", 52_000, VOICE_42)
                .copy(mediaPath = note.absolutePath),
        )
        kit.message(
            WaTestKit
                .media(ADA, "v2", NOW - 20 * minute, WaSyncKind.Audio, "audio/ogg", 9_000, VOICE_7, fromMe = true)
                .copy(mediaPath = file("reply.ogg").absolutePath),
        )
        kit.message(
            WaTestKit.media(ADA, "v3", NOW - 10 * minute, WaSyncKind.Audio, "audio/ogg", 130_000, VOICE_95),
        )
        online(kit)
        val model = model(kit, ADA)
        // Ada's first note is playing at twelve of its 42 seconds, at 1.5×.
        model.toggleVoice("v1")
        idle()
        model.seekVoice("v1", TWELVE_OF_42)
        val state = model.settled()
        val voice = model.voice.value
        assertEquals(VoiceUi("v1", true, TWELVE_OF_42, "0:12 / 0:42"), voice)
        assertEquals("1.5×", state.voiceSpeed)
        assertEquals(note.absolutePath, engine.path)
        snap("e6.4-wa-voice-note", content = { Thread(it, state, voice = voice) })
        compose.onNodeWithTag(MediaTags.voicePlay("v1")).assertIsDisplayed()
        compose.onNodeWithTag(MediaTags.VoiceSpeed).assertIsDisplayed()
        compose.onNodeWithTag(MediaTags.voiceBar("v2"), useUnmergedTree = true).assertExists()
    }

    // ---- received pictures and files

    @Test fun receivedMedia() {
        val (kit, _) = kit()
        kit.chat(ADA, name = "Ada", pushName = "Ada")
        val wide = MediaMeta(fileName = "harbour.jpg", width = 1_600, height = 1_000, thumbnail = true)
        kit.message(
            WaTestKit.media(
                ADA,
                "p1",
                NOW - 50 * minute,
                WaSyncKind.Image,
                "image/jpeg",
                348_160,
                wide,
                "Morning at the harbour",
            ),
        )
        kit.message(
            WaTestKit.media(
                ADA,
                "d1",
                NOW - 40 * minute,
                WaSyncKind.Document,
                "application/pdf",
                1_258_291,
                MediaMeta(fileName = "Ferry timetable weekend.pdf", pages = 2),
            ),
        )
        kit.message(
            WaTestKit.media(
                ADA,
                "c1",
                NOW - 30 * minute,
                WaSyncKind.Video,
                "video/mp4",
                3_565_158,
                MediaMeta(seconds = 42, width = 1_280, height = 720, thumbnail = true),
            ),
        )
        kit.message(
            WaTestKit.media(
                ADA,
                "p2",
                NOW - 20 * minute,
                WaSyncKind.Image,
                "image/jpeg",
                120_000,
                MediaMeta(fileName = "tickets.jpg", width = 1_200, height = 900, thumbnail = true),
                fromMe = true,
            ),
        )
        for (id in listOf("p1", "c1", "p2")) kit.remote.thumbnails[id] = BytesAnswer(3, dataB64 = "VEhN")
        // The first picture is on the phone in full; the second only has its thumbnail yet.
        kit.remote.files["p1"] = BytesAnswer(3, dataB64 = "SU1H")
        online(kit)
        val state = model(kit, ADA).settled()
        val byKey =
            state.items.filterIsInstance<ConversationItem.Bubble>().associate {
                it.key to
                    it.bubble.media.single()
            }
        assertTrue(byKey.getValue("p1").local)
        assertEquals("harbour.jpg", (byKey.getValue("p1").model as File).name)
        assertEquals(false, byKey.getValue("p2").local)
        assertNotNull(byKey.getValue("p2").model)
        assertEquals("0:42 · 3.4 MB", byKey.getValue("c1").detail)
        assertEquals("PDF · 1.2 MB", byKey.getValue("d1").detail)
        snap("e6.4-wa-received-media", content = { Thread(it, state) })
        compose.onNodeWithTag(MediaTags.save("d1")).assertIsDisplayed()
        compose.onNodeWithTag(MediaTags.share("d1")).assertIsDisplayed()
    }

    // ---- M50: the sender card

    private fun groupChat(kit: WaTestKit) {
        kit.chat(GROUP, name = "Weekend hike", group = true)
        kit.message(WaTestKit.text(GROUP, "g1", NOW - 90 * minute, "Who brings water?", sender = ADA))
        kit.message(WaTestKit.text(GROUP, "g2", NOW - 89 * minute, "And snacks, please", sender = ADA))
        kit.message(
            WaTestKit.text(GROUP, "g4", NOW - 70 * minute, "I have the map", sender = CEM, senderName = "Cem"),
        )
        kit.message(WaTestKit.text(GROUP, "g5", NOW - 60 * minute, "Great, thank you all", true, WaSyncRowState.Read))
        kit.message(WaTestKit.text(GROUP, "g6", NOW - 20 * minute, "Start at eight?", sender = MERT))
        kit.message(WaTestKit.text(GROUP, "g7", NOW - 10 * minute, "Eight it is", true, WaSyncRowState.Delivered))
    }

    private fun cardShot(
        name: String,
        sender: String,
        bubble: String,
        saved: Boolean,
    ) {
        val (kit, _) = kit()
        groupChat(kit)
        online(kit)
        val model = model(kit, GROUP)
        val state = model.settled()
        val card = runBlocking { model.senderCard(sender) }
        assertEquals(saved, card.saved)
        val tapped = mutableListOf<String>()
        val actions =
            SenderCardActions(
                onMessage = { tapped += "message ${it.jid}" },
                onCall = { tapped += "call ${it.number}" },
                onContact = { tapped += "contact" },
            )
        snap(name, content = { overlays ->
            Thread(overlays, state, senderCard = { SenderMiniCard(card, actions) })
        }, after = { compose.onNodeWithTag(ConversationTags.sender(bubble)).performClick() })
        compose.onNodeWithTag(SenderCardTags.Root).assertIsDisplayed()
        compose.onNodeWithTag(SenderCardTags.Message).performClick()
        compose.onNodeWithTag(SenderCardTags.Call).performClick()
        assertEquals(listOf("message $sender", "call ${card.number}"), tapped)
    }

    /** Ada is in the address book: her name, her number as people read it, and "Open contact". */
    @Test fun senderMiniCard() = cardShot("e6.6-sender-mini-card", ADA, "g1", saved = true)

    /** Cem is not: the name WhatsApp carries, and "Add to contacts" (in Turkish). */
    @Config(qualifiers = "+tr-rTR")
    @Test
    fun senderMiniCardTurkish() = cardShot("e6.6-sender-mini-card-tr", CEM, "g4", saved = false)

    private companion object {
        const val CEM = "905550000003@s.whatsapp.net"
        const val SWIPE_STEPS = 8
        const val SWIPE_STEP_DP = 12f
        const val SHORT_STEP_DP = 5f
        const val HOUR_MS = 3_600_000L
        const val TWELVE_OF_42 = 12f / 42f
        val SAVED_SPEED = dev.rosty.core.model.chat.VoiceSpeed.X1_5
        val VOICE_42 = MediaMeta(seconds = 42, voiceNote = true)
        val VOICE_7 = MediaMeta(seconds = 7, voiceNote = true)
        val VOICE_95 = MediaMeta(seconds = 95, voiceNote = true)
    }
}
