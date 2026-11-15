package dev.rosty.messages.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.chat.CardField
import dev.rosty.core.model.chat.SharedCard
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.messages.viewer.presentContactCard
import dev.rosty.testing.ShotTest
import dev.rosty.testing.gradientImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** E6.5 screenshots: the bubble menu (below and above), reactions, pills, select mode, select text, info. */
@Config(sdk = [35])
class E65ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    override val imageProducer: (Any, Int, Int) -> Bitmap? = { data, w, h ->
        if (data is File && data.exists()) BitmapFactory.decodeFile(data.path) else gradientImage(data, w, h)
    }

    private val picture by lazy { ConversationSample.picture(RuntimeEnvironment.getApplication().cacheDir) }

    private val menu =
        persistentListOf(
            MenuItem.Action("Reply", RostyIcons.Back) {},
            MenuItem.Action("Copy", RostyIcons.Copy) {},
            MenuItem.Action("Select Text", RostyIcons.Pencil) {},
            MenuItem.Action("Forward", RostyIcons.Forward) {},
            MenuItem.Action("Select", RostyIcons.CircleCheck) {},
            MenuItem.Action("Info", RostyIcons.Info) {},
            MenuItem.Separator,
            MenuItem.Action("Delete", RostyIcons.Trash, destructive = true) {},
        )

    private val pills =
        mapOf(
            "sms:1" to
                listOf(
                    PillUi(PillKind.Calendar, "Add to Calendar", "tomorrow"),
                ),
            "sms:5" to
                listOf(
                    PillUi(PillKind.Call, "Call 0555 000 00 07", "05550000007"),
                    PillUi(PillKind.Message, "Message", "05550000007"),
                    PillUi(PillKind.Map, "Open in Maps", "geo:41.0,29.0"),
                ),
        )

    @Composable
    private fun Thread(
        overlays: OverlayController,
        state: ConversationUi = ConversationSample.state(picture),
        tools: ToolsUi = ToolsUi(),
        reactions: List<String> = emptyList(),
        pills: Map<String, List<PillUi>> = this.pills,
        attach: Boolean = false,
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
                            menuFor = { menu },
                            reactionsFor = { reactions },
                            pillsFor = { b -> pills[b.key].orEmpty() },
                            onAttach = if (attach) ({}) else null,
                        )
                    },
                sendLabel = "Send",
                olderLabel = "Load Earlier",
                tools = tools,
                selectLabels = SelectLabels({ "$it selected" }, "Copy", "Forward", "Delete", "Cancel"),
            )
        }
    }

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        content: @Composable (OverlayController) -> Unit,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { content(overlays) }
            advance(600)
            after(overlays)
            repeat(STEPS) { advance(STEP_MS) }
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun press(key: String) {
        compose
            .onNodeWithTag(
                ConversationTags.bubble(key),
            ).performTouchInput { longClick(center, durationMillis = 700) }
    }

    @Test fun menuBelow() = snap("e6.5-bubble-menu-below", content = { Thread(it) }, after = { press("sms:2") })

    @Test fun menuAbove() = snap("e6.5-bubble-menu-above", content = { Thread(it) }, after = { press("local:7") })

    @Test fun reactionPicker() =
        snap("e6.5-reaction-picker", content = {
            Thread(it, reactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏"))
        }, after = { press("sms:5") })

    @Test fun detectionPills() = snap("e6.5-detection-pills", content = { Thread(it) })

    @Test fun otpCopyCode() {
        val bubbles =
            ConversationSample.bubbles(picture).take(3) +
                BubbleUi(
                    "sms:9",
                    false,
                    "Your code is 482913. Do not share it.",
                    "14:02",
                    atMs =
                        ConversationSample.NOW - 60_000,
                )
        val labels = DayLabels(java.time.ZoneId.of("UTC"), java.util.Locale.US, true, "Today", "Yesterday")
        val state =
            ConversationSample
                .state(
                    picture,
                ).copy(items = withDays(bubbles, labels, ConversationSample.NOW).toImmutableList())
        snap("e6.5-otp-copy-code", content = {
            Thread(
                it,
                state = state,
                pills =
                    mapOf("sms:9" to listOf(PillUi(PillKind.CopyCode, "Copy 482913", "482913"))),
            )
        })
    }

    @Test fun mmsStaged() =
        snap("e6.3-mms-staged", content = {
            val base = ConversationSample.state(picture)
            Thread(
                it,
                state = base.copy(staged = BubbleMedia(picture, "Photo", isImage = true)),
                pills = emptyMap(),
                attach = true,
            )
        })

    @Test fun contactCardSheet() =
        snap("e6.7-contact-card-sheet", content = { Thread(it) }, after = {
            compose.runOnUiThread {
                it.presentContactCard(
                    RuntimeEnvironment.getApplication(),
                    SharedCard(
                        name = "Deniz Aydın",
                        org = "Kadıköy Library",
                        numbers =
                            listOf(
                                CardField("+90 555 000 00 03", "Mobile"),
                                CardField("+90 555 000 00 04", "Work"),
                            ),
                        emails = listOf(CardField("deniz@example.org", "Home")),
                        note = "",
                    ),
                ) {}
            }
        })

    @Test fun selectModeBlock() =
        snap("e6.5-select-mode-block", content = {
            Thread(it, tools = ToolsUi(selected = persistentListOf("sms:2", "sms:3", "sms:5")))
        })

    @Test fun selectTextSheet() =
        snap("e6.5-select-text-sheet", content = { Thread(it) }, after = {
            compose.runOnUiThread {
                it.presentSelectText(
                    RuntimeEnvironment.getApplication(),
                    "Running ten minutes late, sorry. Meet me at the north exit.",
                )
            }
        })

    @Test fun messageInfo() =
        snap("e6.5-message-info", content = { Thread(it) }, after = {
            compose.runOnUiThread {
                it.presentInfo(
                    RuntimeEnvironment.getApplication(),
                    listOf("Sent" to "21 Sep 2026, 18:05", "Status" to "Delivered", "Sent with" to "SMS"),
                )
            }
        })

    @Test fun menuFontScale2() =
        snap("e6.5-bubble-menu-below-fs2", fontScale = 2f, content = { Thread(it) }, after = { press("sms:5") })

    private companion object {
        const val STEPS = 15
        const val STEP_MS = 100L
    }
}
