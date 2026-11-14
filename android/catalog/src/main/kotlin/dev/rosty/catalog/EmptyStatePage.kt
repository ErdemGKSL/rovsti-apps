package dev.rosty.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons

/** Two empty states: one with an action, one without. */
@Composable
internal fun EmptyStatePage() {
    Column(Modifier.fillMaxSize().padding(insetsOnly())) {
        PageHeader("Empty states", "Glyph, title, one line, optional action.")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = RostyIcons.Message,
                title = "No conversations yet",
                message = "Start one from a contact, or link WhatsApp to see your chats here.",
                actionLabel = "New message",
                onAction = {},
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = RostyIcons.Search,
                title = "No results",
                message = "Nothing matches your search.",
            )
        }
    }
}
