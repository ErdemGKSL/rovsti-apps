package dev.rosty.messages.wa

import android.Manifest
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleMedia
import dev.rosty.messages.conversation.BubbleMediaKind
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ComposerMode
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.RecordActions
import dev.rosty.messages.conversation.RecordPhase
import dev.rosty.messages.conversation.RecordTags
import dev.rosty.messages.conversation.RecordUi
import dev.rosty.messages.conversation.VoiceUi
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.PlatformPrefs
import dev.rosty.platform.audio.FakeVoiceRecorder
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.PermissionController
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import kotlin.math.sin

/**
 * E6-WA R3 step 8: the voice note recorder of the WhatsApp composer (holding, the cancel hint, locked, the take in
 * review) and the player drawing a received waveform, in light, dark, Turkish and at font scale 2; and the microphone
 * permission path on the real gate, recorder and view model.
 */
@Config(sdk = [35])
class WaVoiceRecordShotTest : ShotTest() {
    @get:Rule val folder = TemporaryFolder()

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val app get() = RuntimeEnvironment.getApplication()
    private val scope: CoroutineScope = MainScope()
    private val minute = 60_000L

    @After fun stop() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ---- the screens, from fixed states

    private val noAction: RecordActions =
        RecordActions(
            onStart = { true },
            onRelease = {},
            onCancel = {},
            onLock = {},
            onStop = {},
            onPreview = {},
            onDelete = {},
            onSend = {},
        )

    private fun bars(seed: Int): List<Float> =
        List(64) { i ->
            val voice = (sin(i * 0.45 + seed) + 1) * 0.35 + (i * 37 % 23) / 100.0
            voice.toFloat().coerceIn(0.02f, 1f)
        }

    private fun text(
        key: String,
        body: String,
        outgoing: Boolean,
        atMs: Long,
    ) = BubbleUi(
        key = key,
        outgoing = outgoing,
        text = body,
        meta = "14:0${(atMs / minute % 10)}",
        state = if (outgoing) BubbleState.Read else BubbleState.None,
        atMs = atMs,
    )

    private fun voice(
        key: String,
        outgoing: Boolean,
        seconds: Int,
        waveform: List<Float>,
        atMs: Long,
    ) = BubbleUi(
        key = key,
        outgoing = outgoing,
        text = "",
        meta = "14:1${(atMs / minute % 10)}",
        state = if (outgoing) BubbleState.Played else BubbleState.None,
        atMs = atMs,
        media =
            persistentListOf(
                BubbleMedia(
                    model = null,
                    label = "Voice message (0:%02d)".format(seconds),
                    isImage = false,
                    kind = BubbleMediaKind.Voice,
                    detail = "0:%02d".format(seconds),
                    local = true,
                    waveform = waveform.toImmutableList(),
                ),
            ),
    )

    @Composable
    private fun chat(withVoice: Boolean): ConversationUi {
        val bubbles =
            buildList {
                if (withVoice) {
                    add(voice("v3", outgoing = false, seconds = 4, waveform = emptyList(), atMs = NOW - minute))
                    add(voice("v2", outgoing = true, seconds = 21, waveform = bars(2), atMs = NOW - 2 * minute))
                    add(voice("v1", outgoing = false, seconds = 9, waveform = bars(0), atMs = NOW - 3 * minute))
                }
                add(text("m3", "Easier to say it than to type it", false, NOW - 5 * minute))
                add(text("m2", "The 9:40 one works for me", true, NOW - 7 * minute))
                add(text("m1", "Which ferry are we taking on Saturday?", false, NOW - 9 * minute))
            }
        return ConversationUi(
            title = "Ada Lovelace",
            subtitle = "+90 555 000 00 01",
            avatar = RowAvatar(waJid = ADA, colorKey = ADA),
            items = bubbles.map { ConversationItem.Bubble(it) }.toImmutableList(),
            loaded = true,
            composer = ComposerMode.Ready(stringResource(R.string.wa_thread_placeholder)),
            voiceSpeed = "1×",
        )
    }

    @Composable
    private fun Screen(
        record: RecordUi?,
        withVoice: Boolean = false,
        voiceUi: VoiceUi? = null,
        actions: RecordActions = noAction,
    ) {
        val overlays = remember { OverlayController() }
        OverlayHost(overlays) {
            ConversationScreen(
                state = chat(withVoice),
                composer = rememberTextFieldState(),
                listState = rememberLazyListState(),
                actions =
                    remember(actions) {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { persistentListOf() },
                            record = actions,
                        )
                    },
                sendLabel = stringResource(R.string.conversation_send),
                olderLabel = stringResource(R.string.conversation_load_earlier),
                labels = rememberToolLabels(),
                voice = voiceUi,
                record = record,
            )
        }
    }

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        during: () -> Unit = {},
        after: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, fontScale = fontScale) { content() }
            advance(600)
            during()
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            after()
            advance(600)
        }
    }

    private val holding = RecordUi(RecordPhase.Holding, "0:07", level = 0.7f)
    private val locked = RecordUi(RecordPhase.Locked, "0:12", level = 0.55f)
    private val review =
        RecordUi(RecordPhase.Review, "0:12", bars = bars(1).toImmutableList(), playing = true, progress = 0.4f)

    private fun press() = compose.onNodeWithTag(RecordTags.Mic).performTouchInput { down(center) }

    private fun slide(dp: Float) =
        compose.onNodeWithTag(RecordTags.Mic).performTouchInput {
            repeat(SLIDE_STEPS) { moveBy(Offset(-dp / SLIDE_STEPS * density, 0f)) }
        }

    private fun lift() = compose.onNodeWithTag(RecordTags.Mic).performTouchInput { up() }

    /** All four recorder states and the player, under one name suffix. */
    private fun recorderShots(
        suffix: String,
        fontScale: Float = 1f,
    ) {
        snap("e6wa-r3-record-holding$suffix", fontScale, { Screen(holding) }, during = ::press, after = ::lift)
        snap(
            "e6wa-r3-record-cancel-hint$suffix",
            fontScale,
            { Screen(holding) },
            during = {
                press()
                slide(CANCEL_SLIDE_DP)
            },
            after = ::lift,
        )
        snap("e6wa-r3-record-locked$suffix", fontScale, { Screen(locked) })
        snap("e6wa-r3-record-review$suffix", fontScale, { Screen(review) })
        snap(
            "e6wa-r3-voice-waveform$suffix",
            fontScale,
            { Screen(null, withVoice = true, voiceUi = VoiceUi("v1", true, 0.35f, "0:03 / 0:09")) },
        )
    }

    @Test fun recorderAndWaveform() {
        recorderShots("")
        compose.onNodeWithTag(RecordTags.Mic).assertIsDisplayed()
    }

    @Test fun recorderAndWaveformAtFontScaleTwo() = recorderShots("-fs2", fontScale = 2f)

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun recorderAndWaveformInTurkish() = recorderShots("-tr")

    /** The panel's buttons and the held microphone say what they do. */
    @Test fun theRecorderSpeaksItsButtons() {
        composeFrame { Screen(locked) }
        for (tag in listOf(RecordTags.Delete, RecordTags.Stop, RecordTags.Send, RecordTags.Elapsed)) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        composeFrame { Screen(review) }
        compose.onNodeWithTag(RecordTags.Preview).assertIsDisplayed()
        composeFrame { Screen(holding) }
        compose.onNodeWithTag(RecordTags.Holding).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.wa_record_cancel_hint)).assertIsDisplayed()
    }

    // ---- the permission path, on the real gate, recorder and view model

    private val mic = FakeVoiceRecorder()
    private var clockMs = 0L
    private val special = FakeSpecialAccess()
    private val prefs get() = app.getSharedPreferences(PlatformPrefs.BRIDGE, Context.MODE_PRIVATE)

    private fun controller(): PermissionController =
        PermissionController(app, AppPermissions.messages, special, prefs, executor = { it.run() })

    private fun model(kit: WaTestKit): WaThreadViewModel {
        kit.offline.start()
        val res = app.resources
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                    drafts = MemoryDrafts(),
                    names = PersonNames({ null }, MutableStateFlow(Unit)),
                    now = { kit.now },
                    recorder = mic,
                    preview = FakeVoiceEngine(),
                    voiceFile = { File(folder.root, "take-${mic.started.size}.ogg") },
                    recordClock = { clockMs },
                ),
                ADA,
                ResourceWaWords(res),
                ResourceInboxWords(res),
                DayLabels(ZoneId.of("UTC"), res.configuration.locales[0], true, "Today", "Yesterday"),
            )
        idle()
        return model
    }

    @Composable
    private fun Live(
        model: WaThreadViewModel,
        controller: PermissionController,
    ) {
        val overlays = remember { OverlayController() }
        OverlayHost(overlays) {
            val state by model.state.collectAsState()
            val record by model.record.collectAsState()
            val actions = rememberRecordActions(model, controller, special, overlays)
            ConversationScreen(
                state = state,
                composer = rememberTextFieldState(),
                listState = rememberLazyListState(),
                actions =
                    remember(actions) {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { persistentListOf() },
                            record = actions,
                        )
                    },
                sendLabel = stringResource(R.string.conversation_send),
                olderLabel = stringResource(R.string.conversation_load_earlier),
                record = record,
            )
        }
    }

    private fun kit(): WaTestKit {
        val kit = WaTestKit(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), graceMs = 0)
        kit.chat(ADA, name = "Ada")
        kit.message(WaTestKit.text(ADA, "m1", NOW - 5 * minute, "Send me a voice note"))
        return kit
    }

    private fun tap() =
        compose.onNodeWithTag(RecordTags.Mic).performTouchInput {
            down(center)
            up()
        }

    @Test fun neverAskedTheFirstHoldExplainsAndAsksAndRecordsNothing() {
        prefs.edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = controller()
        controller.refreshNow { false }
        val model = model(kit())
        composeFrame { Live(model, controller) }
        tap()
        idle()
        advance(1_000)
        val title = app.getString(dev.rosty.shell.R.string.permission_rationale_microphone_title)
        assertTrue(compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        assertTrue(mic.started.isEmpty())
        assertNull(model.record.value)
    }

    @Test fun deniedForGoodTheHoldLeadsToSettings() {
        prefs
            .edit()
            .clear()
            .putBoolean(PermissionGroup.askedKey(PermissionGroup.MICROPHONE), true)
            .commit()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = controller()
        controller.refreshNow { false }
        val model = model(kit())
        composeFrame { Live(model, controller) }
        tap()
        idle()
        advance(1_000)
        compose.onAllNodesWithText(app.getString(R.string.wa_record_denied_title)).onFirst().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.wa_record_settings)).performClick()
        idle()
        advance(600)
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals("fake.settings.AppDetails", started?.action)
        assertTrue(mic.started.isEmpty())
    }

    @Test fun grantedHoldRecordsSlideCancelsLockStopsAndSendGoesOut() {
        prefs.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = controller()
        controller.refreshNow { false }
        val kit = kit()
        val model = model(kit)
        composeFrame { Live(model, controller) }

        // Hold, then slide past the cancel line: nothing goes out.
        press()
        idle()
        advance(300)
        assertEquals(RecordPhase.Holding, model.record.value?.phase)
        compose.onNodeWithTag(RecordTags.Holding).assertIsDisplayed()
        slide(CANCEL_DP)
        idle()
        advance(600)
        assertNull(model.record.value)
        lift()
        idle()
        assertEquals(1, mic.stops)

        // Hold, slide up past the lock line, let go: it keeps recording; Stop shows the take, Send sends it.
        press()
        idle()
        compose.onNodeWithTag(RecordTags.Mic).performTouchInput {
            repeat(SLIDE_STEPS) { moveBy(Offset(0f, -LOCK_DP / SLIDE_STEPS * density)) }
        }
        idle()
        advance(600)
        assertEquals(RecordPhase.Locked, model.record.value?.phase)
        // The microphone gave way to the panel; the finger lifts somewhere above it.
        compose.onRoot().performTouchInput { up() }
        idle()
        clockMs += 4_000
        compose.onNodeWithTag(RecordTags.Stop).performClick()
        idle()
        advance(600)
        assertEquals(RecordPhase.Review, model.record.value?.phase)
        compose.onNodeWithTag(RecordTags.Send).performClick()
        idle()
        advance(600)
        assertNull(model.record.value)
        val sent =
            kit.ops.writes
                .filterIsInstance<WaSyncEnqueueRequest>()
                .single()
                .action
        assertTrue(sent is WaSyncAction.SendMedia && sent.voiceNote && sent.seconds == 4)
        assertNotNull((sent as WaSyncAction.SendMedia).waveformB64)
    }

    private companion object {
        const val SLIDE_STEPS = 6
        const val CANCEL_SLIDE_DP = 60f

        /** Past the 120 dp cancel line and the 72 dp lock line. */
        const val CANCEL_DP = 140f
        const val LOCK_DP = 90f
    }
}
