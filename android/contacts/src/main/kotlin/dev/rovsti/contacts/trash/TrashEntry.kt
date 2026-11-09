package dev.rovsti.contacts.trash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.design.overlay.LocalOverlay

/** The destination of `ContactsRoute.Trash`: the page over its model, with the effects wired to the overlays. */
@Composable
fun TrashPage(
    container: ContactsContainer,
    onBack: () -> Unit,
) {
    val model = remember(container) { TrashViewModel(container.trash, container.store, container.scope) }
    val state by model.state.collectAsState()
    TrashEffects(model.effects, LocalOverlay.current, model::onAction)
    TrashScreen(state = state, onAction = model::onAction, onBack = onBack)
}
