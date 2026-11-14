package dev.rosty.messages.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.chat.GalleryAnswer
import dev.rosty.core.model.chat.GalleryMedia
import dev.rosty.core.model.chat.GallerySection
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.overlay.showUndo
import dev.rosty.messages.viewer.ViewerPicture
import dev.rosty.messages.viewer.showConversationPictures
import dev.rosty.platform.files.FakeFilesRepository
import dev.rosty.testing.ShotTest
import dev.rosty.testing.gradientImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.MainScope
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** E6.3 and E6.6 screenshots: the SMS thread, its keyboard, the undo pill, the failed menu, the tools. */
@Config(sdk = [35])
class E63ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    /** Files are drawn as they are on disk: the fake loader's hue follows the path, which changes run to run. */
    override val imageProducer: (Any, Int, Int) -> Bitmap? = { data, w, h ->
        if (data is File && data.exists()) {
            BitmapFactory.decodeFile(data.path)
        } else {
            gradientImage(data, w, h)
        }
    }

    private val picture by lazy { ConversationSample.picture(RuntimeEnvironment.getApplication().cacheDir) }

    @Composable
    private fun Thread(
        overlays: OverlayController,
        state: ConversationUi = ConversationSample.state(picture),
        tools: ToolsUi = ToolsUi(),
        text: String = "",
        menu: Boolean = false,
    ) {
        val labels =
            ToolLabels(
                "More",
                "Search in Chat",
                "Older",
                "Newer",
                "Done",
                "Jump to the latest message",
                { a, b -> "$a of $b" },
                "No matches",
            )
        OverlayHost(overlays) {
            ConversationScreen(
                state = state,
                composer = rememberTextFieldState(text),
                listState = rememberLazyListState(),
                actions =
                    remember {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { b ->
                                if (b.state == BubbleState.Failed) {
                                    persistentListOf(
                                        MenuItem.Action("Retry", RostyIcons.Rotate) {},
                                        MenuItem.Action("Copy", RostyIcons.Copy) {},
                                        MenuItem.Separator,
                                        MenuItem.Action("Delete", RostyIcons.Trash, destructive = true) {},
                                    )
                                } else {
                                    persistentListOf(MenuItem.Action("Copy", RostyIcons.Copy) {})
                                }
                            },
                            headerMenu =
                                if (menu) {
                                    persistentListOf(
                                        MenuItem.Action("Search in Chat", RostyIcons.Search) {},
                                        MenuItem.Action("Jump to Date", RostyIcons.CalendarPlus) {},
                                        MenuItem.Action("Media, Links and Docs", RostyIcons.Photo) {},
                                        MenuItem.Separator,
                                        MenuItem.Action("Export Chat", RostyIcons.Download) {},
                                        MenuItem.Action("Share as Text", RostyIcons.Share) {},
                                    )
                                } else {
                                    persistentListOf()
                                },
                        )
                    },
                sendLabel = "Send",
                olderLabel = "Load Earlier",
                tools = tools,
                labels = labels,
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

    @Test fun smsThread() = snap("e6.3-sms-thread", content = { Thread(it) })

    @Test fun keyboardOpen() =
        snap(
            "e6.3-sms-keyboard-open",
            ime = 300,
            content = { Thread(it, text = "Almost there") },
        )

    @Test fun undoSendPill() =
        snap("e6.3-undo-send-pill", content = {
            Thread(it)
        }, after = { it.showUndo("Sending…", {}, "Undo", durationMs = 60_000) })

    @Test fun failedMessageMenu() =
        snap("e6.3-failed-message-menu", content = { Thread(it) }, after = {
            compose
                .onNodeWithTag(
                    ConversationTags.bubble("local:6"),
                ).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
        })

    @Test fun dualSimChooser() =
        snap("e6.3-dual-sim-chooser", content = { Thread(it, state = ConversationSample.state(picture, sim = "Work")) })

    @Test fun findInChat() =
        snap(
            "e6.6-find-in-chat",
            content = { Thread(it, tools = ToolsUi(find = FindUi("late", 0, 1, false), highlight = "sms:5")) },
        )

    @Test fun headerMenu() =
        snap("e6.6-header-menu", content = { Thread(it, menu = true) }, after = {
            compose.onNodeWithTag(ConversationTags.Menu).performTouchInput {
                down(center)
                up()
            }
            advance(600)
        })

    @Test fun fontScale2() = snap("e6.3-sms-thread-fs2", fontScale = 2f, content = { Thread(it) })

    @Test fun unreadDivider() =
        snap("e6.6-unread-divider", content = {
            Thread(it, state = ConversationSample.state(picture, divider = "mms:4" to "2 unread messages"))
        })

    @Test fun jumpToDatePopover() =
        snap("e6.6-jump-to-date-popover", content = { Thread(it) }, after = {
            compose.runOnUiThread {
                it.presentJumpToDate(RuntimeEnvironment.getApplication(), LocalDate.of(2026, 9, 21)) {}
            }
            // The sheet's spring needs frames: one long advance runs only its first.
            repeat(15) { advance(100) }
        })

    @Test fun galleryGrid() {
        val app = RuntimeEnvironment.getApplication()
        val skies = listOf(Color.rgb(90, 160, 220), Color.rgb(230, 140, 90), Color.rgb(120, 90, 200))
        val pictures =
            (0 until 7).associate {
                "m$it" to
                    ConversationSample.picture(app.cacheDir, "g$it.png", skies[it % 3])
            }
        val answer =
            GalleryAnswer(
                media =
                    pictures.keys.mapIndexed {
                        i,
                        id,
                        ->
                        GalleryMedia(id, ConversationSample.NOW - i * 86_400_000L)
                    },
                links = emptyList(),
                docs = emptyList(),
                mediaSections = listOf(GallerySection("September 2026", 0, 4), GallerySection("August 2026", 4, 7)),
                linkSections = emptyList(),
                docSections = emptyList(),
            )
        snap("e6.6-gallery-grid", content = { Thread(it) }, after = {
            compose.runOnUiThread { it.presentGallery(app, answer, pictures::get) {} }
            repeat(15) { advance(100) }
        })
    }

    @Test fun imageViewerPaging() {
        val app = RuntimeEnvironment.getApplication()
        val second = ConversationSample.picture(app.cacheDir, "mms-part-2.png", Color.rgb(230, 140, 90))
        val pictures =
            listOf(
                "mms:4" to ViewerPicture(picture, ConversationSample.NOW - 3_600_000),
                "mms:9" to ViewerPicture(second, ConversationSample.NOW - 60_000),
            )
        snap("e6.7-image-viewer-paging", content = { Thread(it) }, after = {
            compose.runOnUiThread {
                it.showConversationPictures(app, pictures, "mms:4", "Mert Kaya", FakeFilesRepository(), MainScope())
            }
            advance(800)
            compose.onRoot().performTouchInput {
                swipeLeft(startX = right * 0.8f, endX = right * 0.2f, durationMillis = 300)
            }
        })
    }
}
