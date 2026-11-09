package dev.rovsti.contacts.intake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.people.presentContactChooser
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.transition.TabNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The model of one open of the import page; it lives as long as the page is composed. */
@Composable
fun rememberIntakeModel(
    container: ContactsContainer,
    route: ContactsRoute.Intake,
): IntakeViewModel =
    remember(container, route) {
        IntakeViewModel(
            source = if (route.paste) IntakeSource.Paste else IntakeSource.File(route.uri),
            ops = container.ops,
            repo = container.contacts,
            book = container.book,
            files = container.files,
            clipboard = container.clipboard,
            trash = container.trash,
            scope = container.scope,
            stagingDir = container.context.cacheDir,
        )
    }

/** The destination of [ContactsRoute.Intake]: the screen over its model, with the effects wired to the app. */
@Composable
fun IntakePage(
    container: ContactsContainer,
    route: ContactsRoute.Intake,
    navigator: TabNavigator<ContactsRoute>,
) {
    val model = rememberIntakeModel(container, route)
    val state by model.state.collectAsState()
    IntakeEffects(model, navigator, LocalOverlay.current, container.scope)
    IntakeScreen(state, model::onAction)
}

/** Runs the model's effects: toasts, the undo pill, the contact chooser, the editor and leaving the page. */
@Composable
fun IntakeEffects(
    model: IntakeViewModel,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController,
    scope: CoroutineScope,
) {
    val latestOverlays = rememberUpdatedState(overlays)
    LaunchedEffect(model, navigator) {
        model.effects.collect { effect ->
            val host = latestOverlays.value
            when (effect) {
                is IntakeEffect.Toast -> {
                    host.showToast(effect.text)
                }

                is IntakeEffect.Undo -> {
                    host.showUndo(
                        effect.message,
                        onUndo = { scope.launch { host.showToast(effect.undo()) } },
                    )
                }

                is IntakeEffect.ChooseExisting -> {
                    host.presentContactChooser("Add to Existing Contact", effect.people) { id ->
                        model.onAction(IntakeAction.PickExisting(id))
                    }
                }

                is IntakeEffect.OpenEditor -> {
                    navigator.push(ContactsRoute.ContactEditor(null, effect.seed))
                }

                IntakeEffect.Close -> {
                    navigator.pop()
                }
            }
        }
    }
}
