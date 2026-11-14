package dev.rosty.contacts.intake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.rosty.contacts.ContactsContainer
import dev.rosty.contacts.ContactsRoute
import dev.rosty.contacts.R
import dev.rosty.contacts.people.presentContactChooser
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.UiText
import dev.rosty.design.text.resolve
import dev.rosty.design.text.text
import dev.rosty.design.transition.TabNavigator
import kotlinx.collections.immutable.persistentListOf
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
            source =
                when {
                    route.paste -> IntakeSource.Paste
                    route.scan -> IntakeSource.Scan(route.uri)
                    route.csv -> IntakeSource.Csv(route.uri)
                    else -> IntakeSource.File(route.uri)
                },
            ops = container.ops,
            repo = container.contacts,
            book = container.book,
            files = container.files,
            clipboard = container.clipboard,
            trash = container.trash,
            scope = container.scope,
            stagingDir = container.context.cacheDir,
            qr = container.qr,
            luma = AndroidLumaReader(container.context),
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
    val strContactsOk = stringResource(R.string.contacts_ok)
    val strContactsAddToExisting = stringResource(R.string.contacts_add_to_existing)
    val context = LocalContext.current
    val latestOverlays = rememberUpdatedState(overlays)
    LaunchedEffect(model, navigator) {
        model.effects.collect { effect ->
            val host = latestOverlays.value
            when (effect) {
                is IntakeEffect.Toast -> {
                    host.showToast(effect.text.resolve(context))
                }

                is IntakeEffect.Undo -> {
                    host.showUndo(
                        effect.message.resolve(context),
                        onUndo = { scope.launch { host.showToast(effect.undo().resolve(context)) } },
                    )
                }

                is IntakeEffect.Alert -> {
                    host.present(
                        AlertEntry(
                            heading = effect.title.resolve(context),
                            message = effect.message.resolve(context),
                            actions =
                                persistentListOf(
                                    AlertAction(strContactsOk, AlertRole.Cancel),
                                ),
                        ),
                    )
                }

                is IntakeEffect.ChooseExisting -> {
                    host.presentContactChooser(strContactsAddToExisting, effect.people) { id ->
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
