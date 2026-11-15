package dev.rosty.messages.spam

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.messages.ThreadId
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.SettingsTab
import dev.rosty.messages.chromeFor
import dev.rosty.messages.inbox.HostedInbox
import dev.rosty.messages.inbox.InboxFolder
import dev.rosty.messages.inbox.InboxHarness
import dev.rosty.messages.inbox.InboxRowUi
import dev.rosty.messages.inbox.InboxTags
import dev.rosty.messages.inbox.InboxUiState
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.inbox.SmsAccess
import dev.rosty.messages.messagesTabItems
import dev.rosty.shell.RostyShell
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config

/** E6.11 screenshots: the Spam folder (and its row menu), the Spam filter page and the rule editor with its field. */
@Config(sdk = [35])
class E611ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    // ---- the Spam folder

    private fun spamRow(
        key: String,
        title: String,
        snippet: String,
        time: String,
        sender: String,
        unread: Int = 0,
    ) = InboxRowUi(
        key = key,
        title = title,
        snippet = snippet,
        draft = false,
        time = time,
        atMs = 0,
        unread = unread,
        markedUnread = false,
        pinned = false,
        muted = false,
        archived = false,
        latest = Channel.Sms,
        channels = persistentListOf(Channel.Sms),
        avatar = RowAvatar(colorKey = key),
        threads = persistentListOf(ThreadId.Sms(key.length.toLong())),
        smsThread = key.length.toLong(),
        smsSender = sender,
        waChat = null,
        group = false,
        tick = null,
        inSpam = true,
        blocked = key == "blocked",
    )

    private val spamState =
        InboxUiState(
            loaded = true,
            access = SmsAccess.Ready,
            folder = InboxFolder.Spam,
            rows =
                listOf(
                    spamRow(
                        "promo",
                        "PROMO-SHOP",
                        "Last day! 70% off everything: shop.example/sale",
                        "14:05",
                        "PROMO-SHOP",
                        2,
                    ),
                    spamRow(
                        "prize",
                        "+90 555 000 00 01",
                        "You have won a prize, claim it at prize.example",
                        "11:20",
                        "+905550000001",
                        1,
                    ),
                    spamRow(
                        "blocked",
                        "+90 555 000 00 03",
                        "Call me back about your parcel",
                        "Yesterday",
                        "+905550000003",
                    ),
                    spamRow("bet", "BETKING", "Your free bonus is waiting: bet.example", "9 Nov", "BETKING"),
                ).toImmutableList(),
            anyRows = true,
        )

    private fun folder(
        name: String,
        fontScale: Float = 1f,
        after: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark, fontScale = fontScale) { HostedInbox(InboxHarness(spamState)) }
            advance(500)
            after()
            advance(1_400)
            capture(name, dark)
        }
    }

    @Test fun spamFolder() = folder("e6.11-spam-folder")

    @Test fun spamFolderMenu() =
        folder("e6.11-spam-folder-menu") {
            compose.onNodeWithTag(InboxTags.row("prize")).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
        }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun spamFolderTurkish() = folder("e6.11-spam-folder-tr")

    // ---- the Spam filter page

    private val rulesUi =
        SpamSettingsUi(
            loaded = true,
            enabled = true,
            rules =
                persistentListOf(
                    RuleRow(0, RuleKind.UnknownLinks, ""),
                    RuleRow(1, RuleKind.Sender, "PROMO*"),
                    RuleRow(2, RuleKind.Keyword, "kazandınız"),
                    RuleRow(3, RuleKind.ShortCodes, ""),
                ),
            allow = persistentListOf("BANK", "+90 555 000 00 04"),
            blocked = persistentListOf("+90 555 000 00 03"),
            canBlock = true,
            deleteCodes = true,
        )

    @Composable
    private fun SpamPageHost(
        overlays: OverlayController,
        ui: SpamSettingsUi = rulesUi,
    ) {
        val navigator =
            remember {
                TabNavigator(MessagesTabs, MessagesRoots).also {
                    it.select(SettingsTab)
                    it.push(MessagesRoute.SpamSettings)
                }
            }
        val context = LocalContext.current
        RostyShell(
            navigator = navigator,
            tabs =
                messagesTabItems(
                    stringResource(R.string.tab_chats),
                    stringResource(R.string.tab_whatsapp),
                    stringResource(R.string.tab_settings),
                ),
            chromeFor = { route -> chromeFor(route) { it.resolve(context) } },
            overlays = overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<MessagesRoute.Chats> {}
            entry<MessagesRoute.WhatsApp> {}
            entry<MessagesRoute.Settings> {}
            entry<MessagesRoute.SpamSettings> {
                SpamSettingsPage(ui, SpamPageLinks({}, {}, {}, {}, {}), onBack = {})
            }
        }
    }

    private fun page(
        name: String,
        fontScale: Float = 1f,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { SpamPageHost(overlays) }
            advance(600)
            after(overlays)
            capture(name, dark)
        }
    }

    @Test fun spamRules() = page("e6.11-spam-rules")

    @Test fun spamRulesFontScale2() = page("e6.11-spam-rules-fs2", fontScale = 2f)

    @Test fun spamRulesEnd() =
        page("e6.11-spam-rules-end") {
            repeat(3) {
                compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = top, durationMillis = 120) }
                advance(900)
            }
        }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun spamRulesTurkish() = page("e6.11-spam-rules-tr")

    private fun editor(name: String) =
        page(name) { overlays ->
            compose.runOnUiThread {
                overlays.present(
                    SheetEntry("rule", persistentListOf(Detent.Large)) { _ ->
                        RuleEditorContent(
                            initial = RuleRow(1, RuleKind.Sender, "PROMO*"),
                            onSave = { _, _, _ -> },
                            onCancel = {},
                            onDelete = {},
                            field = remember { TextFieldState("PROMO*") },
                        )
                    },
                )
            }
            advance(1_400)
        }

    @Test fun ruleEditorField() = editor("e6.11-rule-editor-field")

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun ruleEditorFieldTurkish() = editor("e6.11-rule-editor-field-tr")
}
