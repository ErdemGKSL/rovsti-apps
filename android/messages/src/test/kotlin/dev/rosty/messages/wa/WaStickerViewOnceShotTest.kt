package dev.rosty.messages.wa

import android.os.Looper
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.Unavailable
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleMediaKind
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.StickerTags
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.media.MediaMeasure
import dev.rosty.testing.ShotTest
import dev.rosty.testing.gradientImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/**
 * Screenshots of E6-WA R3 steps 6 and 7: a sticker in a thread (no bubble, the time in a pill over its corner), the
 * view-once placeholder and a view-once send of mine, and the send preview of several files with a caption and view
 * once on. Threads are built by the real view model over the in-memory store, with the words of `strings.xml`.
 */
@Config(sdk = [35])
class WaStickerViewOnceShotTest : ShotTest() {
    @get:Rule val folder = TemporaryFolder()

    /** Files are drawn by name, previews by their bytes: the folder changes from run to run, the content does not. */
    override val imageProducer: (Any, Int, Int) -> android.graphics.Bitmap? = { data, width, height ->
        val seed =
            when (data) {
                is File -> data.name
                is ByteArray -> data.contentToString()
                else -> data
            }
        gradientImage(seed, width, height)
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val scope: CoroutineScope = MainScope()
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val minute = 60_000L

    @After fun stop() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun kit(): WaTestKit =
        WaTestKit
            .withMedia(
                CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined),
                graceMs = 0,
                mediaDir = File(folder.root, "wa-media"),
            ).first

    private fun state(kit: WaTestKit): ConversationUi {
        kit.offline.start()
        kit.connect()
        val days =
            DayLabels(
                ZoneId.of("UTC"),
                res.configuration.locales[0],
                true,
                res.getString(R.string.conversation_today),
                res.getString(R.string.inbox_yesterday),
            )
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    drafts = MemoryDrafts(),
                    names = PersonNames({ mapOf("+905550000001" to "Ada Lovelace")[it] }, MutableStateFlow(Unit)),
                    now = { kit.now },
                    media =
                        WaMedia(
                            kit.offline,
                            kit.remote,
                            checkNotNull(kit.downloads),
                            { kit.now },
                            io = Dispatchers.Unconfined,
                        ),
                    emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                ),
                ADA,
                ResourceWaWords(res),
                ResourceInboxWords(res),
                days,
            )
        idle()
        return model.state.value
    }

    @Composable
    private fun Thread(
        overlays: OverlayController,
        state: ConversationUi,
    ) {
        OverlayHost(overlays) {
            ConversationScreen(
                state = state,
                composer = rememberTextFieldState(),
                listState = rememberLazyListState(),
                actions =
                    remember {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { persistentListOf() },
                        )
                    },
                sendLabel = stringResource(R.string.conversation_send),
                olderLabel = stringResource(R.string.conversation_load_earlier),
                labels = rememberToolLabels(),
            )
        }
    }

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        content: @Composable (OverlayController) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { content(overlays) }
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    // ---- a sticker

    private fun sticker(
        id: String,
        atMs: Long,
        fromMe: Boolean,
        width: Int,
        height: Int,
    ) = WaTestKit
        .media(
            ADA,
            id,
            atMs,
            WaSyncKind.Sticker,
            "image/webp",
            24_000,
            MediaMeta(width = width, height = height, thumbnail = true),
            fromMe = fromMe,
        )

    @Test fun stickerInAThread() {
        val kit = kit()
        kit.chat(ADA, name = "Ada", pushName = "Ada")
        kit.message(WaTestKit.text(ADA, "m1", NOW - 40 * minute, "We got the tickets!"))
        kit.message(sticker("s1", NOW - 38 * minute, fromMe = false, width = 512, height = 512))
        kit.message(
            WaTestKit.text(ADA, "m2", NOW - 30 * minute, "Brilliant, see you at ten", true, WaSyncRowState.Read),
        )
        kit.message(sticker("s2", NOW - 29 * minute, fromMe = true, width = 512, height = 384))
        for (id in listOf("s1", "s2")) kit.remote.thumbnails[id] = BytesAnswer(3, dataB64 = "U1RL")
        val state = state(kit)
        val kinds =
            state.items.filterIsInstance<ConversationItem.Bubble>().associate {
                it.key to
                    it.bubble.media
                        .firstOrNull()
                        ?.kind
            }
        assertEquals(BubbleMediaKind.Sticker, kinds["s1"])
        snap("e6wa-r3-sticker") { Thread(it, state) }
        compose.onNodeWithTag(StickerTags.stickerMeta("s2"), useUnmergedTree = true).assertIsDisplayed()
    }

    // ---- view once

    private fun viewOnceState(): ConversationUi {
        val kit = kit()
        kit.chat(ADA, name = "Ada", pushName = "Ada")
        kit.message(WaTestKit.text(ADA, "m1", NOW - 50 * minute, "Look what I found on the beach"))
        kit.message(
            WaSyncMessage(
                id = "vo1",
                chat = ADA,
                sender = ADA,
                fromMe = false,
                timestampMs = NOW - 48 * minute,
                kind = WaSyncKind.Unsupported,
                reactions = emptyList(),
                state = WaSyncRowState.Received,
                unavailable = Unavailable.ViewOnce,
            ),
        )
        kit.message(
            WaTestKit.media(
                ADA,
                "vo2",
                NOW - 20 * minute,
                WaSyncKind.Image,
                "image/jpeg",
                250_000,
                MediaMeta(width = 1_200, height = 900, viewOnce = true),
                fromMe = true,
            ),
        )
        return state(kit)
    }

    @Test fun viewOncePlaceholder() {
        val state = viewOnceState()
        snap("e6wa-r3-view-once") { Thread(it, state) }
        compose.onNodeWithTag(StickerTags.unavailable("vo1"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(StickerTags.unavailable("vo2"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun viewOncePlaceholderTurkish() {
        val state = viewOnceState()
        snap("e6wa-r3-view-once-tr") { Thread(it, state) }
    }

    // ---- several files

    private fun batch(): WaSendBatch {
        fun item(
            id: String,
            name: String,
            mime: String,
            size: Long,
            measure: MediaMeasure? = null,
        ) = WaBatchItem(id, WaStagedFile(File(folder.root, name), name, mime, size), measure = measure)
        return WaSendBatch()
            .add(
                listOf(
                    item("a", "harbour.jpg", "image/jpeg", 348_160, MediaMeasure(1_600, 1_200, null, byteArrayOf(1))),
                    item("b", "ferry.mp4", "video/mp4", 3_565_158, MediaMeasure(720, 1_280, 42, byteArrayOf(2, 2))),
                    item(
                        "c",
                        "lighthouse.jpg",
                        "image/jpeg",
                        220_000,
                        MediaMeasure(1_200, 1_600, null, byteArrayOf(3)),
                    ),
                    item("d", "Ferry timetable weekend.pdf", "application/pdf", 1_258_291),
                ),
            ).caption("a", "Morning at the harbour")
            .toggleViewOnce("a")
            .select(0)
    }

    @Composable
    private fun Batch(batch: WaSendBatch) {
        WaBatchScreen(batch, WaBatchActions({}, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}), ::plainSize)
    }

    @Test fun sendPreview() {
        val batch = batch()
        snap("e6wa-r3-batch") { Batch(batch) }
        compose.onNodeWithTag(WaBatchTags.Send, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(WaBatchTags.ViewOnce, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(WaBatchTags.Later, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun sendPreviewFontScale2() {
        val batch = batch()
        snap("e6wa-r3-batch-fs2", fontScale = 2f) { Batch(batch) }
        compose.onNodeWithTag(WaBatchTags.Send, useUnmergedTree = true).assertIsDisplayed()
    }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun sendPreviewTurkish() {
        val batch = batch()
        snap("e6wa-r3-batch-tr") { Batch(batch) }
    }
}
