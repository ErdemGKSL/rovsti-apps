package dev.rovsti.contacts.cleanup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.design.overlay.LocalOverlay

/** The destination of `ContactsRoute.Cleanup`: the page over its model, with the effects wired to the overlays. */
@Composable
fun CleanupPage(
    container: ContactsContainer,
    onBack: () -> Unit,
) {
    val model =
        remember(container) {
            CleanupViewModel(
                book = container.book,
                contacts = container.contacts,
                ops = container.ops,
                trash = container.trash,
                clipboard = container.clipboard,
                scope = container.scope,
            )
        }
    val state by model.state.collectAsState()
    CleanupEffects(model.effects, LocalOverlay.current, model::onAction)
    CleanupScreen(state = state, onAction = model::onAction, onBack = onBack)
}
