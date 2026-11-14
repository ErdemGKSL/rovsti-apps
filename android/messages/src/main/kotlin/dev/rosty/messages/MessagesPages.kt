package dev.rosty.messages

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rosty.design.chrome.ScreenPage
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.transition.TabNavigator

// Placeholders of the pages E6.2-E6.8 build; each is replaced by its story.

@Composable
fun WhatsAppPage(
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
) {
    ScreenPage(title = stringResource(R.string.tab_whatsapp)) {
        EmptyState(
            RostyIcons.WhatsApp,
            stringResource(R.string.inbox_empty_title),
            stringResource(R.string.inbox_empty_detail),
        )
    }
}

@Composable
fun WaThreadPage(
    container: MessagesContainer,
    route: MessagesRoute.WaThread,
    navigator: TabNavigator<MessagesRoute>,
    onBack: () -> Unit,
) {
    ScreenPage(title = route.jid, onBack = onBack) {}
}
