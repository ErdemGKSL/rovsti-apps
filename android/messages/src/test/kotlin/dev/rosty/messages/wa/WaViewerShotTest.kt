package dev.rosty.messages.wa

import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.wa.MediaMeta
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.design.media.PhotoViewerTags
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.theme.RostyTheme
import dev.rosty.messages.R
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationTags
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.viewer.LocalVideoPlayers
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.media.VideoPlayer
import dev.rosty.platform.media.VideoPlayers
import dev.rosty.platform.media.VideoState
import dev.rosty.testing.ShotTest
import dev.rosty.testing.gradientImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** A video that is open, paused at [positionMs] of [durationMs]. */
private class FakeVideoPlayer(
    durationMs: Int,
    private val positionMs: Int,
) : VideoPlayer {
    private val mutable = MutableStateFlow(VideoState(prepared = true, durationMs = durationMs))
    override val state: StateFlow<VideoState> = mutable

    override fun positionMs(): Int = positionMs

    override fun setSurface(surface: Surface?) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(ms: Int) = Unit

    override fun setMuted(muted: Boolean) {
        mutable.value = mutable.value.copy(muted = muted)
    }

    override fun release() = Unit
}

/**
 * Screenshots of E6-WA R3 steps 3 to 5: the viewer's chrome over a photo (with its caption and the sender and time
 * line), a video with its controls, a photo that is downloading, and the menu of a photo with Save, Share and Forward.
 * The viewer is drawn over black in light and dark alike; both are recorded as the other screens are.
 */
@Config(sdk = [35])
class WaViewerShotTest : ShotTest() {
    @get:Rule val folder = TemporaryFolder()

    /** A 4:3 photo drawn by its file name, so the viewer letterboxes it as it would a real one. */
    override val imageProducer: (Any, Int, Int) -> android.graphics.Bitmap? =
        { data, width, _ -> gradientImage((data as? File)?.name ?: data, width, width * 3 / 4) }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val scope: CoroutineScope = MainScope()
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val minute = 60_000L

    @After fun stop() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun file(name: String): File = File(folder.root, name).apply { writeBytes(ByteArray(4)) }

    private fun photo(
        id: String = "p1",
        local: Boolean = true,
    ): WaSyncMessage =
        WaTestKit
            .media(
                ADA,
                id,
                NOW - 30 * minute,
                WaSyncKind.Image,
                "image/jpeg",
                348_160,
                MediaMeta(width = 1600, height = 1200, thumbnail = true),
                caption = "The harbour this morning, before the first ferry left",
            ).copy(mediaPath = if (local) file("$id.jpg").absolutePath else null)

    private fun video(): WaSyncMessage =
        WaTestKit
            .media(
                ADA,
                "v1",
                NOW - 20 * minute,
                WaSyncKind.Video,
                "video/mp4",
                4_404_019,
                MediaMeta(seconds = 42, width = 1280, height = 720, thumbnail = true),
            ).copy(mediaPath = file("v1.mp4").absolutePath)

    private fun session(
        row: WaSyncMessage,
        fetch: suspend (WaSyncMessage) -> File? = { null },
    ): Pair<WaViewerSession, WaViewerLabels> {
        val labels = WaViewerLabels.of(res)
        val session =
            WaViewerSession(
                row,
                WaViewerSources(
                    thumb = { File(folder.root, "thumb-${it.id}.jpg") },
                    ensureThumb = { false },
                    fetch = fetch,
                    gone = { false },
                    gallery = null,
                    sender = { "Ada Lovelace" },
                    savedUri = { null },
                    zone = ZoneId.of("UTC"),
                ),
                labels,
                scope,
            )
        return session to labels
    }

    private fun snap(
        name: String,
        row: WaSyncMessage,
        fontScale: Float = 1f,
        fetch: suspend (WaSyncMessage) -> File? = { null },
        players: VideoPlayers? = null,
        check: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) {
                CompositionLocalProvider(LocalVideoPlayers provides players) {
                    OverlayHost(overlays) { Box(Modifier.fillMaxSize().background(RostyTheme.colors.page)) }
                }
            }
            val (session, labels) = session(row, fetch)
            val actions = WaViewerActions(save = { true }, share = {}, forward = {})
            overlays.showWaViewer(session, labels, actions, scope, anchors = null)
            idle()
            advance(1_200)
            check()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            overlays.dismissAll()
            advance(600)
        }
    }

    @Test fun viewerChromeOverAPhoto() =
        snap("e6wa-r3-viewer-photo", photo()) {
            compose.onNodeWithTag(WaViewerTags.Caption).assertIsDisplayed()
            compose.onNodeWithTag(WaViewerTags.Byline).assertIsDisplayed()
            compose.onNodeWithTag(PhotoViewerTags.action("forward")).assertIsDisplayed()
        }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun viewerChromeOverAPhotoTurkish() =
        snap("e6wa-r3-viewer-photo-tr", photo()) {
            compose.onNodeWithContentDescription("Kaydet").assertIsDisplayed()
            compose.onNodeWithContentDescription("İlet").assertIsDisplayed()
        }

    @Test fun viewerChromeOverAPhotoFontScale2() =
        snap("e6wa-r3-viewer-photo-fs2", photo(), fontScale = 2f) {
            compose.onNodeWithTag(PhotoViewerTags.Toolbar).assertIsDisplayed()
        }

    @Test fun aVideoWithItsControls() {
        val players = VideoPlayers { _, _, _, _, _ -> FakeVideoPlayer(durationMs = 42_000, positionMs = 12_400) }
        snap("e6wa-r3-viewer-video", video(), players = players) {
            compose.onNodeWithTag(WaViewerTags.Controls).assertIsDisplayed()
            compose.onNodeWithText("0:12").assertIsDisplayed()
            compose.onNodeWithText("0:42").assertIsDisplayed()
        }
    }

    @Test fun aPhotoThatIsDownloading() =
        snap("e6wa-r3-viewer-downloading", photo(local = false), fetch = { awaitCancellation() }) {
            compose.onNodeWithText(res.getString(R.string.wa_viewer_downloading) + " · 340 KB").assertIsDisplayed()
        }

    // ---- the menu of a photo

    private fun menuState(): Pair<WaThreadViewModel, ConversationUi> {
        val unconfined = CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined)
        val (kit, _) = WaTestKit.withMedia(unconfined, graceMs = 0, mediaDir = File(folder.root, "wa-media"))
        kit.chat(ADA, name = "Ada", pushName = "Ada")
        kit.message(WaTestKit.text(ADA, "m1", NOW - 40 * minute, "Are we still on for Saturday?"))
        kit.message(photo())
        kit.message(WaTestKit.text(ADA, "m3", NOW - 5 * minute, "Look at the light", true))
        kit.offline.start()
        val days = DayLabels(ZoneId.of("UTC"), res.configuration.locales[0], true, "Today", "Yesterday")
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    drafts = MemoryDrafts(),
                    names = PersonNames({ mapOf("+905550000001" to "Ada Lovelace")[it] }, MutableStateFlow(Unit)),
                    now = { kit.now },
                    emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                ),
                ADA,
                ResourceWaWords(res),
                ResourceInboxWords(res),
                days,
            )
        idle()
        return model to model.state.value
    }

    @Composable
    private fun Thread(
        overlays: OverlayController,
        model: WaThreadViewModel,
        state: ConversationUi,
    ) {
        OverlayHost(overlays) {
            ConversationScreen(
                state = state,
                composer = rememberTextFieldState(),
                listState = rememberLazyListState(),
                actions =
                    remember(model) {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { waBubbleMenu(it, res, model) },
                        )
                    },
                sendLabel = stringResource(R.string.conversation_send),
                olderLabel = stringResource(R.string.conversation_load_earlier),
                labels = rememberToolLabels(),
            )
        }
    }

    @Test fun aPhotoMenuOffersSaveShareAndForward() {
        val (model, state) = menuState()
        val labels =
            waBubbleMenu(bubbleOf(state, "p1"), res, model).map {
                (it as? dev.rosty.design.overlay.MenuItem.Action)?.label
            }
        assertEquals(true, labels.containsAll(listOf("Save", "Share", "Forward")))
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark) { Thread(overlays, model, state) }
            advance(600)
            compose.onNodeWithTag(ConversationTags.bubble("p1")).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
            compose.onNodeWithText("Forward").assertIsDisplayed()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e6wa-r3-media-menu-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            overlays.dismissAll()
            advance(600)
        }
    }

    private fun bubbleOf(
        state: ConversationUi,
        key: String,
    ) = state.items
        .mapNotNull { (it as? dev.rosty.messages.conversation.ConversationItem.Bubble)?.bubble }
        .first { it.key == key }
}
