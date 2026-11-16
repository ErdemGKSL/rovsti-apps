package dev.rosty.messages.wa

import dev.rosty.core.model.chat.VoiceSpeed
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMarkPlayedRequest
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.RecordPhase
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.audio.FakeVoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.util.Locale

/** Voice notes in a WhatsApp thread (E6-WA R3): a recorded take goes out, a played one is receipted once, bars show. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaVoiceThreadTest {
    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")
    private val minute = 60_000L
    private val mic = FakeVoiceRecorder()
    private val engine = FakeVoiceEngine()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private fun TestScope.model(kit: WaTestKit): WaThreadViewModel {
        kit.offline.start()
        return WaThreadViewModel(
            WaThreadSources(
                offline = kit.offline,
                remote = kit.remote,
                drafts = MemoryDrafts(),
                names = PersonNames({ null }, MutableStateFlow(Unit)),
                now = { kit.now },
                voice = WaVoiceNotes(backgroundScope, FakeVoiceRules(), engine, { VoiceSpeed.X1 }, {}, tickMs = HOUR),
                recorder = mic,
                preview = FakeVoiceEngine(),
                voiceFile = { File(folder.root, "take-${mic.started.size}.ogg") },
                recordClock = { testScheduler.currentTime },
            ),
            ADA,
            EnglishWaWords,
            EnglishInboxWords,
            days,
        )
    }

    private fun bubbles(model: WaThreadViewModel): List<BubbleUi> =
        model.state.value.items
            .mapNotNull { (it as? ConversationItem.Bubble)?.bubble }

    private fun note(
        id: String,
        fromMe: Boolean = false,
        meta: MediaMeta = MediaMeta(seconds = 7, voiceNote = true),
        played: Boolean = false,
    ): WaSyncMessage {
        val file = folder.newFile("$id.ogg").also { it.writeBytes(ByteArray(8)) }
        return WaTestKit
            .media(ADA, id, NOW - 10 * minute, WaSyncKind.Audio, "audio/ogg; codecs=opus", 8, meta, fromMe = fromMe)
            .copy(mediaPath = file.absolutePath, played = played)
    }

    private fun WaTestKit.playedAsked() = ops.writes.filterIsInstance<WaSyncMarkPlayedRequest>().map { it.id }

    @Test fun playingAReceivedVoiceNoteSendsThePlayedReceiptOnce() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(note("v1"))
            kit.message(note("mine", fromMe = true))
            kit.message(note("song", meta = MediaMeta(seconds = 200)))
            kit.message(note("old", played = true))
            val model = model(kit)
            settle()

            model.toggleVoice("v1")
            settle()
            assertEquals(listOf("v1"), kit.playedAsked())
            assertEquals(
                WaSyncAction.MarkPlayed("v1"),
                kit.ops.queue
                    .single()
                    .action,
            )
            // Pause, play again, start over: no second receipt.
            model.toggleVoice("v1")
            settle()
            model.toggleVoice("v1")
            settle()
            assertEquals(listOf("v1"), kit.playedAsked())

            // My own note, an audio file that is not a voice note and a note played before send none.
            for (key in listOf("mine", "song", "old")) {
                model.toggleVoice(key)
                settle()
            }
            assertEquals(listOf("v1"), kit.playedAsked())
            assertEquals(1, kit.ops.queue.size)
            assertEquals(4, engine.starts)
        }

    @Test fun aRecordedTakeGoesOutAsAVoiceNoteAnsweringTheQuote() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            kit.message(WaTestKit.text(ADA, "m1", NOW - 5 * minute, "Can you say it out loud?"))
            val model = model(kit)
            settle()
            kit.connect()
            settle()
            model.startReply("m1")
            settle()
            val recording = model.recording!!
            mic.level = 12_000
            assertTrue(recording.start())
            assertEquals(RecordPhase.Holding, model.record.value?.phase)
            advanceTimeBy(2_200)
            runCurrent()
            recording.release()
            settle()

            val send =
                kit.ops.writes
                    .filterIsInstance<WaSyncEnqueueRequest>()
                    .single()
                    .action as WaSyncAction.SendMedia
            assertEquals(MediaKind.Audio, send.media)
            assertEquals(VoiceRecording.MIME, send.mime)
            assertEquals(mic.started.single().absolutePath, send.file)
            assertEquals(2, send.seconds)
            assertTrue(send.voiceNote)
            assertEquals("m1", send.quotedId)
            assertNotNull(MediaWaveform.decode(send.waveformB64))
            assertNull(model.state.value.reply)
            assertNull(model.record.value)
            // The other side heard "recording" while it lasted and "paused" after.
            assertEquals(listOf(ADA to TypingState.Recording, ADA to TypingState.Paused), kit.remote.typed)
        }

    @Test fun offlineNobodyIsToldAboutTheRecording() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val model = model(kit)
            settle()
            val recording = model.recording!!
            recording.start()
            advanceTimeBy(3_000)
            runCurrent()
            recording.release()
            settle()
            assertTrue(kit.remote.typed.isEmpty())
            // Offline the take still waits in the outbox.
            assertTrue(
                kit.ops.queue
                    .single()
                    .action is WaSyncAction.SendMedia,
            )
        }

    @Test fun theReceivedWaveformIsDrawnAndANoteWithoutOneKeepsThePlainBar() =
        scope.runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(ADA, name = "Ada")
            val samples = ByteArray(MediaWaveform.SAMPLES) { (it * 3 % 101).toByte() }
            kit.message(
                note(
                    "wave",
                    meta = MediaMeta(seconds = 9, voiceNote = true, waveformB64 = MediaWaveform.encode(samples)),
                ),
            )
            kit.message(note("plain"))
            kit.message(note("broken", meta = MediaMeta(seconds = 3, voiceNote = true, waveformB64 = "AAAA")))
            val model = model(kit)
            settle()
            val byKey = bubbles(model).associateBy { it.key }
            val wave =
                byKey
                    .getValue("wave")
                    .media
                    .single()
                    .waveform
            assertEquals(MediaWaveform.SAMPLES, wave.size)
            assertEquals(samples.map { it / 100f }, wave)
            assertTrue(
                byKey
                    .getValue("plain")
                    .media
                    .single()
                    .waveform
                    .isEmpty(),
            )
            assertTrue(
                byKey
                    .getValue("broken")
                    .media
                    .single()
                    .waveform
                    .isEmpty(),
            )
        }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
