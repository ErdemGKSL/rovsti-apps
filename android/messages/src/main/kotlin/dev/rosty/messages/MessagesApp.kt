package dev.rosty.messages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabBarMode
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.compose.NewConversationPage
import dev.rosty.messages.conversation.SmsThreadPage
import dev.rosty.messages.inbox.ChatsPage
import dev.rosty.messages.inbox.InboxSelectToolbar
import dev.rosty.messages.inbox.rememberInboxStrings
import dev.rosty.messages.inbox.rememberInboxViewModel
import dev.rosty.messages.settings.AboutEntry
import dev.rosty.messages.settings.AppearanceEntry
import dev.rosty.messages.settings.HapticsEntry
import dev.rosty.messages.settings.LicenceDetailEntry
import dev.rosty.messages.settings.LicencesEntry
import dev.rosty.messages.settings.LogsEntry
import dev.rosty.messages.settings.MessagesSettingsPage
import dev.rosty.messages.settings.SettingsLinks
import dev.rosty.messages.settings.WhatsAppSettingsEntry
import dev.rosty.messages.spam.SpamSettingsEntry
import dev.rosty.messages.starred.StarredEntry
import dev.rosty.messages.wa.WaThreadPage
import dev.rosty.messages.wa.WhatsAppPage
import dev.rosty.messages.wa.rememberWaInboxViewModel
import dev.rosty.shell.RostyShell
import dev.rosty.shell.settings.CommonSettingsPage

/** The screens of Rosty Messages: one [RostyShell] over the activity's navigator. */
@Composable
fun MessagesApp(
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
    overlays: OverlayController = remember { OverlayController() },
) {
    val tabs =
        messagesTabItems(
            chats = stringResource(R.string.tab_chats),
            whatsApp = stringResource(R.string.tab_whatsapp),
            settings = stringResource(R.string.tab_settings),
        )
    val pop: () -> Unit =
        remember(navigator) {
            {
                navigator.pop()
                Unit
            }
        }
    // A long press on a back button jumps to the root of the tab: selecting the current tab does that.
    val popToRoot: () -> Unit = remember(navigator) { { navigator.select(navigator.currentTab) } }
    val context = LocalContext.current
    // Select mode of the inbox folds the tab bar and puts its toolbar beside the circle (E6.2).
    val inbox = rememberInboxViewModel(container)
    val inboxState by inbox.state.collectAsState()
    // The WhatsApp tab is the same inbox over the WhatsApp chats only, with its own selection.
    val waInbox = rememberWaInboxViewModel(container)
    val waInboxState by waInbox.state.collectAsState()
    val onWhatsApp = navigator.currentTab == WhatsAppTab
    val select = if (onWhatsApp) waInboxState.select else inboxState.select
    val selecting = if (onWhatsApp) waInbox else inbox
    val strings = rememberInboxStrings()
    RostyShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { route ->
            chromeFor(route) { it.resolve(context) }.let {
                val root = route == MessagesRoute.Chats || route == MessagesRoute.WhatsApp
                if (select != null && root) it.copy(tabBar = TabBarMode.Folded) else it
            }
        },
        overlays = overlays,
        modifier = Modifier.fillMaxSize(),
        accessory =
            select?.takeIf { it.actions.isNotEmpty() }?.let { chosen ->
                { modifier, inline -> InboxSelectToolbar(chosen, strings, selecting::onAction, modifier, inline) }
            },
    ) {
        entry<MessagesRoute.Chats> { ChatsPage(container, navigator) }
        entry<MessagesRoute.WhatsApp> { WhatsAppPage(container, navigator) }
        entry<MessagesRoute.Settings> {
            val held by container.smsRole.collectAsState()
            MessagesSettingsPage(
                smsRoleHeld = held,
                settings = container.commonSettings,
                links =
                    SettingsLinks(
                        openWhatsApp = { navigator.push(MessagesRoute.WhatsAppSettings) },
                        openSpam = { navigator.push(MessagesRoute.SpamSettings) },
                        openCommon = { page -> navigator.push(page.route()) },
                    ),
                onRoleResult = { container.refreshSmsRole() },
            )
        }
        entry<MessagesRoute.SmsThread> { route -> SmsThreadPage(container, route, pop) { navigator.push(it) } }
        entry<MessagesRoute.WaThread> { route -> WaThreadPage(container, route, navigator, pop) }
        entry<MessagesRoute.NewConversation> { route -> NewConversationPage(container, route, navigator, pop) }
        entry<MessagesRoute.Starred> { StarredEntry(container, navigator) }
        entry<MessagesRoute.WhatsAppSettings> { WhatsAppSettingsEntry(container) }
        entry<MessagesRoute.SpamSettings> { SpamSettingsEntry(container, pop, popToRoot) }
        entry<MessagesRoute.Appearance> { AppearanceEntry(container.commonSettings, pop, popToRoot) }
        entry<MessagesRoute.Haptics> { HapticsEntry(container.commonSettings, pop, popToRoot) }
        entry<MessagesRoute.About> {
            AboutEntry(container, onOpenLicences = { navigator.push(MessagesRoute.Licences) }, pop, popToRoot)
        }
        entry<MessagesRoute.Licences> {
            LicencesEntry(onOpen = { navigator.push(MessagesRoute.LicenceDetail(it)) }, pop, popToRoot)
        }
        entry<MessagesRoute.LicenceDetail> { route -> LicenceDetailEntry(route.id, pop, popToRoot) }
        entry<MessagesRoute.Logs> { route -> LogsEntry(container, route.tag, pop, popToRoot) }
    }
}

/** The back-stack key of a common settings page. */
internal fun CommonSettingsPage.route(): MessagesRoute =
    when (this) {
        CommonSettingsPage.Appearance -> MessagesRoute.Appearance
        CommonSettingsPage.Haptics -> MessagesRoute.Haptics
        CommonSettingsPage.About -> MessagesRoute.About
        CommonSettingsPage.Licences -> MessagesRoute.Licences
        CommonSettingsPage.Logs -> MessagesRoute.Logs()
    }
