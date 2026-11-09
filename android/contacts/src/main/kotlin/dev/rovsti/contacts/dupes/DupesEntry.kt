package dev.rovsti.contacts.dupes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.design.overlay.LocalOverlay

/** The destination of `ContactsRoute.Duplicates`: the page over its model, with the effects wired to the overlays. */
@Composable
fun DupesPage(
    container: ContactsContainer,
    onBack: () -> Unit,
) {
    val model =
        remember(container) {
            DupesViewModel(container.book, container.contacts, container.ops, container.trash, container.scope)
        }
    val state by model.state.collectAsState()
    val overlays = LocalOverlay.current
    DupesEffects(model.effects, overlays, model::onAction)
    DupesScreen(state = state, onAction = model::onAction, onBack = onBack, overlays = overlays)
}
