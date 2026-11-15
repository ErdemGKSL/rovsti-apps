package dev.rosty.messages.wa

import dev.rosty.core.model.chat.SpeedOption
import dev.rosty.core.model.chat.VoicePlayerState
import dev.rosty.core.model.chat.VoiceSpeed
import dev.rosty.core.model.chat.VoiceToggleKind
import dev.rosty.messages.conversation.ReplySwipe
import dev.rosty.messages.conversation.VoiceUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The rules of `voice_player.*` as the Rust core applies them (its fixtures), for tests without the native library. */
class FakeVoiceRules : VoiceRules {
    private val cycle =
        listOf(
            SpeedOption(VoiceSpeed.X1, 1.0, "1×"),
            SpeedOption(VoiceSpeed.X1_5, 1.5, "1.5×"),
            SpeedOption(VoiceSpeed.X2, 2.0, "2×"),
        )

    override suspend fun toggle(
        state: VoicePlayerState,
        key: String,
    ): Pair<VoiceToggleKind, Long?> =
        when {
            state.key != key || state.ended -> VoiceToggleKind.Start to 0L
            state.playing -> VoiceToggleKind.Pause to null
            else -> VoiceToggleKind.Resume to state.positionMs
        }

    override suspend fun view(
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        scrub: Double?,
    ): Triple<Float, String, Long?> {
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        val label =
            if (playing || positionMs > 0) "${clock(positionMs)} / ${clock(durationMs)}" else clock(durationMs)
        return Triple(progress, label, scrub?.let { (it * durationMs).toLong() })
    }

    private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)

    override suspend fun speeds(current: VoiceSpeed): Pair<SpeedOption, SpeedOption> {
        val at = cycle.indexOfFirst { it.speed == current }
        return cycle[at] to cycle[(at + 1) % cycle.size]
    }
}

/** A player that plays nothing: its position moves when a test says so. */
class FakeVoiceEngine : VoiceEngine {
    var path: String? = null
    var playing = false
    var position = 0L
    var length = 42_000L
    var rate = 1f
    var starts = 0
    var releases = 0
    var playable = true
    private var end: (() -> Unit)? = null

    override fun start(
        path: String,
        fromMs: Long,
        speed: Float,
        onEnd: () -> Unit,
    ): Boolean {
        if (!playable) return false
        this.path = path
        rate = speed
        position = fromMs
        playing = true
        starts++
        end = onEnd
        return true
    }

    override fun pause() {
        playing = false
    }

    override fun resume() {
        playing = true
    }

    override fun seekTo(ms: Long) {
        position = ms
    }

    override fun setSpeed(speed: Float) {
        rate = speed
    }

    override fun positionMs() = position

    override fun durationMs() = length

    override fun release() {
        if (path != null) releases++
        path = null
        playing = false
    }

    /** The note played to its end. */
    fun finish() {
        playing = false
        position = length
        end?.invoke()
    }
}

/** M17: one voice note at a time, play/pause, scrubbing and the remembered speed, on scripted rules and player. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaVoiceNotesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = TestScope(StandardTestDispatcher())
    private val engine = FakeVoiceEngine()
    private var saved = VoiceSpeed.X1

    private fun TestScope.notes() =
        WaVoiceNotes(backgroundScope, FakeVoiceRules(), engine, savedSpeed = { saved }, saveSpeed = { saved = it })

    @Test fun aNotePlaysPausesAndResumesWhereItWas() =
        scope.runTest {
            val notes = notes()
            assertNull(notes.state.value)

            assertTrue(notes.toggle("v1", "/media/v1.ogg"))
            assertEquals(VoiceUi("v1", true, 0f, "0:00 / 0:42"), notes.state.value)
            assertEquals("/media/v1.ogg", engine.path)

            // The bar follows the player while it plays.
            engine.position = 21_000
            advanceTimeBy(WaVoiceNotes.TICK_MS + 1)
            runCurrent()
            assertEquals(VoiceUi("v1", true, 0.5f, "0:21 / 0:42"), notes.state.value)

            assertTrue(notes.toggle("v1", "/media/v1.ogg"))
            assertFalse(engine.playing)
            assertEquals(VoiceUi("v1", false, 0.5f, "0:21 / 0:42"), notes.state.value)
            // Paused: nothing reads the position any more.
            engine.position = 30_000
            advanceTimeBy(5 * WaVoiceNotes.TICK_MS)
            runCurrent()
            assertEquals(0.5f, notes.state.value?.progress)

            engine.position = 21_000
            assertTrue(notes.toggle("v1", "/media/v1.ogg"))
            assertTrue(engine.playing)
            assertEquals(21_000, engine.position)
            assertEquals(1, engine.starts)
        }

    @Test fun onlyOneNotePlaysAtATime() =
        scope.runTest {
            val notes = notes()
            notes.toggle("v1", "/media/v1.ogg")
            engine.position = 9_000

            notes.toggle("v2", "/media/v2.ogg")
            // The first note was let go; the second starts from its beginning.
            assertEquals(1, engine.releases)
            assertEquals("/media/v2.ogg", engine.path)
            assertEquals(VoiceUi("v2", true, 0f, "0:00 / 0:42"), notes.state.value)
            assertEquals(2, engine.starts)
        }

    @Test fun theBarScrubsTheLoadedNoteOnly() =
        scope.runTest {
            val notes = notes()
            notes.toggle("v1", "/media/v1.ogg")

            notes.seek("v1", 0.25f)
            assertEquals(10_500, engine.position)
            assertEquals(0.25f, notes.state.value?.progress)
            notes.seek("other", 0.9f)
            assertEquals(10_500, engine.position)
        }

    @Test fun theSpeedCyclesIsAppliedAndRemembered() =
        scope.runTest {
            saved = VoiceSpeed.X1_5
            val notes = notes()
            notes.prepare()
            assertEquals("1.5×", notes.speedLabel.value)

            notes.toggle("v1", "/media/v1.ogg")
            assertEquals(1.5f, engine.rate)
            notes.nextSpeed()
            assertEquals("2×", notes.speedLabel.value)
            assertEquals(2f, engine.rate)
            assertEquals(VoiceSpeed.X2, saved)
            notes.nextSpeed()
            assertEquals("1×", notes.speedLabel.value)
            assertEquals(VoiceSpeed.X1, saved)
        }

    @Test fun aNoteThatEndedOrCannotBePlayedLeavesThePlayerEmpty() =
        scope.runTest {
            val notes = notes()
            notes.toggle("v1", "/media/v1.ogg")
            engine.finish()
            runCurrent()
            assertNull(notes.state.value)
            // Tapping it again plays it from the start.
            notes.toggle("v1", "/media/v1.ogg")
            assertEquals(2, engine.starts)
            assertEquals(0L, engine.position)

            engine.playable = false
            assertFalse(notes.toggle("v2", "/media/broken.ogg"))
            assertNull(notes.state.value)

            engine.playable = true
            notes.toggle("v3", "/media/v3.ogg")
            notes.stop()
            assertNull(notes.state.value)
            assertNull(engine.path)
        }

    // ---- the reply swipe and the recent emoji: pure rules

    @Test fun theReplySwipeFollowsTheFingerThenResistsAndNeverPassesItsEnd() {
        val commit = 64f
        val max = 96f
        assertEquals(0f, ReplySwipe.offset(-20f, commit, max))
        assertEquals(30f, ReplySwipe.offset(30f, commit, max))
        assertEquals(64f, ReplySwipe.offset(64f, commit, max))
        val far = ReplySwipe.offset(400f, commit, max)
        assertTrue(far > 64f && far < 96f)
        assertTrue(ReplySwipe.offset(4_000f, commit, max) < 96f)
        assertTrue(ReplySwipe.offset(200f, commit, max) < far)
        assertFalse(ReplySwipe.commits(63f, commit))
        assertTrue(ReplySwipe.commits(64f, commit))
        assertEquals(0.5f, ReplySwipe.progress(32f, commit))
        assertEquals(1f, ReplySwipe.progress(90f, commit))
    }

    @Test fun recentEmojiAreKeptAcrossRunsAndTheStandardOnesFillTheRest() =
        scope.runTest {
            val file = File(folder.root, "recent-reactions.json")
            val dispatcher = StandardTestDispatcher(testScheduler)
            val first = WaRecentEmoji(file, dispatcher)
            assertEquals(WaRecentEmoji.STANDARD, first.recent.value)
            first.note("🎉")
            first.note("👍")
            first.note("🎉")
            assertEquals(listOf("🎉", "👍", "❤️", "😂", "😮", "😢"), first.recent.value)

            val second = WaRecentEmoji(file, dispatcher)
            second.load()
            assertEquals(first.recent.value, second.recent.value)
            // A damaged file is the standard row again.
            file.writeText("{not json")
            val third = WaRecentEmoji(file, dispatcher)
            third.load()
            assertEquals(WaRecentEmoji.STANDARD, third.recent.value)
        }
}
