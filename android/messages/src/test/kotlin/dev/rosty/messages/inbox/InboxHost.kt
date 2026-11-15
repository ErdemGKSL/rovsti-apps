package dev.rosty.messages.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.transition.TabBarMode
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.ChatsTab
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.chromeFor
import dev.rosty.messages.messagesTabItems
import dev.rosty.shell.RostyShell

/** The inbox on the real shell (tab bar, overlay host) over a fixed state, for screenshots and UI tests. */
internal class InboxHarness(
    initial: InboxUiState = InboxSample.state,
) {
    var state by mutableStateOf(initial)
    val overlays = OverlayController()
    val actions = mutableListOf<InboxAction>()
    lateinit var listState: LazyListState
    lateinit var searchState: TextFieldState
    var muted: InboxRowUi? = null

    fun on(action: InboxAction) {
        actions += action
    }
}

@Composable
internal fun HostedInbox(harness: InboxHarness) {
    val navigator = remember { TabNavigator(MessagesTabs, MessagesRoots).also { it.select(ChatsTab) } }
    val strings = rememberInboxStrings()
    harness.listState = rememberLazyListState()
    harness.searchState = rememberTextFieldState(harness.state.query)
    val actions =
        remember(harness) {
            RowMenuActions(
                onAction = harness::on,
                onMute = { harness.muted = it },
                onAvatar = {},
                onCopyCode = {},
            )
        }
    RostyShell(
        navigator = navigator,
        tabs = messagesTabItems("Chats", "WhatsApp", "Settings"),
        chromeFor = { route ->
            chromeFor(route) { "" }.let {
                if (harness.state.select != null &&
                    route == MessagesRoute.Chats
                ) {
                    it.copy(tabBar = TabBarMode.Folded)
                } else {
                    it
                }
            }
        },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
        accessory =
            harness.state.select?.let { chosen ->
                { modifier, inline -> InboxSelectToolbar(chosen, strings, harness::on, modifier, inline) }
            },
    ) {
        entry<MessagesRoute.Chats> {
            InboxScreen(
                state = harness.state,
                strings = strings,
                listState = harness.listState,
                searchState = harness.searchState,
                actions = actions,
                onNewMessage = {},
            )
        }
        entry<MessagesRoute.WhatsApp> {}
        entry<MessagesRoute.Settings> {}
    }
}
