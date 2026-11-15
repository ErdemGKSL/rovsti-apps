package dev.rosty.messages.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.messages.ChatTextSize
import dev.rosty.core.model.messages.Strength
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.SettingsTab
import dev.rosty.messages.TestMessagesApp
import dev.rosty.messages.backup.BackupBusy
import dev.rosty.messages.backup.BackupFields
import dev.rosty.messages.backup.BackupPhase
import dev.rosty.messages.backup.BackupSheetContent
import dev.rosty.messages.backup.BackupUi
import dev.rosty.messages.backup.ExportIssue
import dev.rosty.messages.backup.RestorePreview
import dev.rosty.messages.chromeFor
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationSample
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.messagesTabItems
import dev.rosty.messages.settings.replies.QuickRepliesPage
import dev.rosty.messages.settings.replies.QuickRepliesUi
import dev.rosty.messages.settings.replies.ReplyChip
import dev.rosty.messages.settings.textsize.ChatTextScale
import dev.rosty.messages.settings.textsize.TextSizePage
import dev.rosty.shell.RostyShell
import dev.rosty.shell.permissions.LocalPermissionController
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.shell.settings.FakeCommonSettings
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E6.12 screenshots: the Settings root, Quick replies (and their chips above a composer), Text size (and bubbles at
 * Larger), the backup passphrase sheet and the restore preview; light and dark, Turkish where the words matter.
 */
@Config(sdk = [35], application = TestMessagesApp::class)
class E612ShotTest : ShotTest() {
    private val container get() = (RuntimeEnvironment.getApplication() as TestMessagesApp).container

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun page(
        name: String,
        fontScale: Float = 1f,
        after: (OverlayController) -> Unit = {},
        content: @Composable (OverlayController) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { content(overlays) }
            advance(600)
            after(overlays)
            advance(1_400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    /** The app's shell on the Settings tab with [route] on top, so the large title and the tab bar are the real ones. */
    @Composable
    private fun Host(
        overlays: OverlayController,
        route: MessagesRoute,
        body: @Composable () -> Unit,
    ) {
        val navigator =
            remember {
                TabNavigator(MessagesTabs, MessagesRoots).also {
                    it.select(SettingsTab)
                    if (route != MessagesRoute.Settings) it.push(route)
                }
            }
        val context = LocalContext.current
        CompositionLocalProvider(
            LocalPermissionController provides container.permissions,
            LocalSpecialAccess provides container.specialAccess,
        ) {
            RostyShell(
                navigator = navigator,
                tabs =
                    messagesTabItems(
                        stringResource(R.string.tab_chats),
                        stringResource(R.string.tab_whatsapp),
                        stringResource(R.string.tab_settings),
                    ),
                chromeFor = { r -> chromeFor(r) { it.resolve(context) } },
                overlays = overlays,
                modifier = Modifier.fillMaxSize(),
            ) {
                entry<MessagesRoute.Chats> {}
                entry<MessagesRoute.WhatsApp> {}
                entry<MessagesRoute.Settings> { if (route == MessagesRoute.Settings) body() }
                entry<MessagesRoute.QuickReplies> { body() }
                entry<MessagesRoute.TextSize> { body() }
                entry<MessagesRoute.Backup> { body() }
            }
        }
    }

    // ---- the Settings root

    @Composable
    private fun Root(overlays: OverlayController) =
        Host(overlays, MessagesRoute.Settings) {
            MessagesSettingsPage(
                smsRoleHeld = true,
                settings = FakeCommonSettings(),
                links = SettingsLinks(openWhatsApp = {}, openCommon = {}),
                onRoleResult = {},
                summary = SettingsSummary(notifications = true, quickReplies = 5, textSize = ChatTextSize.Large),
            )
        }

    @Test fun settings() = page("e6.12-settings") { Root(it) }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun settingsTurkish() = page("e6.12-settings-tr") { Root(it) }

    @Test fun settingsFontScale2() = page("e6.12-settings-fs2", fontScale = 2f) { Root(it) }

    // ---- Quick replies

    private val replies =
        QuickRepliesUi(
            loaded = true,
            replies =
                persistentListOf(
                    "On my way.",
                    "Can I call you later?",
                    "I'm driving, I'll reply soon.",
                    "Thanks!",
                    "Running 10 minutes late, sorry. Start without me and I will catch up as soon as I can.",
                ),
            max = 20,
            maxChars = 500,
        )

    @Test fun quickReplies() =
        page("e6.12-quick-replies") { overlays ->
            Host(overlays, MessagesRoute.QuickReplies) { QuickRepliesPage(replies, {}, {}, onBack = {}) }
        }

    /** The chips above an empty composer, and the bubbles at Larger (M34 applied). */
    @Test fun composerChips() =
        page("e6.12-composer-chips") { overlays ->
            OverlayHost(overlays) {
                ConversationScreen(
                    state = ConversationSample.state(null),
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
                    sendLabel = "Send",
                    olderLabel = "Load Earlier",
                    textScale = ChatTextScale.factor(ChatTextSize.Larger),
                    quickReplies =
                        replies.replies
                            .map { ReplyChip(if (it.length > 28) it.take(28) + "…" else it, it) }
                            .toImmutableList(),
                )
            }
        }

    // ---- Text size

    @Composable
    private fun TextSize(overlays: OverlayController) =
        Host(overlays, MessagesRoute.TextSize) { TextSizePage(ChatTextSize.Large, {}, onBack = {}) }

    @Test fun textSize() = page("e6.12-text-size") { TextSize(it) }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun textSizeTurkish() = page("e6.12-text-size-tr") { TextSize(it) }

    // ---- Backup sheets

    private fun sheet(
        name: String,
        ui: BackupUi,
        fields: () -> BackupFields = { BackupFields() },
    ) = page(name, after = { overlays ->
        compose.runOnUiThread {
            overlays.present(
                SheetEntry("backup", persistentListOf(Detent.Large)) { _ ->
                    BackupSheetContent(ui, remember { fields() }, {}, {})
                },
            )
        }
    }) { overlays ->
        Host(overlays, MessagesRoute.Backup) {
            dev.rosty.messages.backup
                .BackupPage(BackupUi(), {}, {}, onBack = {})
        }
    }

    private fun typed() = BackupFields(TextFieldState("abc12"), TextFieldState("abc12"))

    private val passphraseUi = BackupUi(phase = BackupPhase.Export(ExportIssue.Weak), strength = Strength.Weak)

    @Test fun backupPassphrase() = sheet("e6.12-backup-passphrase", passphraseUi, ::typed)

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun backupPassphraseTurkish() = sheet("e6.12-backup-passphrase-tr", passphraseUi, ::typed)

    @Test fun backupBusy() = sheet("e6.12-backup-busy", BackupUi(phase = BackupPhase.Busy(BackupBusy.Restoring)))

    private val previewUi =
        BackupUi(
            phase = BackupPhase.Preview(RestorePreview(insert = 1_248, skipped = 312, scheduled = 2, spamRules = 5)),
            fileName = "rosty-messages-20261115.rmb",
            roleHeld = true,
        )

    @Test fun restorePreview() = sheet("e6.12-restore-preview", previewUi)

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun restorePreviewTurkish() = sheet("e6.12-restore-preview-tr", previewUi.copy(roleHeld = false))
}
