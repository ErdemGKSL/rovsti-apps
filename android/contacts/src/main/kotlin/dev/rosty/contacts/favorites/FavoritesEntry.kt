package dev.rosty.contacts.favorites

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.contacts.ContactsContainer
import dev.rosty.contacts.ContactsRoute
import dev.rosty.contacts.FavoritesTab
import dev.rosty.contacts.R
import dev.rosty.contacts.cards.CardStudioSubject
import dev.rosty.contacts.cards.openCardStudio
import dev.rosty.contacts.findComponentActivity
import dev.rosty.contacts.startSafely
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.platform.files.ExportDocument
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.files.ShareIntents
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.shell.permissions.PermissionGate
import dev.rosty.shell.permissions.PermissionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The Favorites view model, owned by the activity (it outlives the card being pushed over the tab and popped back). */
@Composable
fun rememberFavoritesViewModel(container: ContactsContainer): FavoritesViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    FavoritesViewModel(
                        book = container.book,
                        store = container.store,
                        ops = container.ops,
                        contacts = container.contacts,
                        callLog = container.callLog,
                        callLogPermission = container.permissions.state(PermissionGroup.CALL_LOG),
                    )
                }
            }
        ViewModelProvider(owner, factory)[FavoritesViewModel::class.java]
    }
}

/** The destination of the Favorites tab root. */
@Composable
fun FavoritesPage(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(navigator, listState) {
        navigator.scrollToTop.collect { if (it.tab == FavoritesTab) listState.animateScrollToItem(0) }
    }
    PermissionGate(PermissionGroup.CALL_LOG)
    val viewModel = rememberFavoritesViewModel(container)
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    FavoritesEffects(viewModel.effects, navigator, overlays, context) { subject ->
        overlays.openCardStudio(context, container, subject)
    }
    FavoritesScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenUpcoming = { navigator.push(ContactsRoute.UpcomingBirthdays) },
        listState = listState,
        modifier = Modifier.testTag("favorites-page"),
        gate = {
            InsetGroupedSection {
                row { PermissionRow(PermissionGroup.CALL_LOG) }
            }
        },
    )
}

/** The destination of `ContactsRoute.UpcomingBirthdays`. */
@Composable
fun UpcomingPage(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
    onBack: () -> Unit,
) {
    val viewModel = rememberFavoritesViewModel(container)
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<FavoritesEffect.SaveCalendar?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ExportDocument("text/calendar")) { result ->
            val file = pending ?: return@rememberLauncherForActivityResult
            pending = null
            if (result is FileResult.Ok && result.uri != null) {
                scope.launch {
                    val written = container.files.writeExport(result.uri!!, text = file.text)
                    overlays.showToast(
                        if (written is FileResult.Ok) {
                            file.notice
                        } else {
                            resources.getString(
                                R.string.contacts_cant_save_file,
                            )
                        },
                    )
                }
            }
        }
    LaunchedEffect(viewModel) {
        viewModel.pageEffects.collect { effect ->
            when (effect) {
                is FavoritesEffect.SaveCalendar -> {
                    pending = effect
                    launcher.launch(effect.fileName)
                }

                is FavoritesEffect.Sms -> {
                    startSafely(context, ShareIntents.sendSmsTo(effect.number, effect.body)) { overlays.showToast(it) }
                }

                is FavoritesEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is FavoritesEffect.OpenCard -> {
                    navigator.push(ContactsRoute.ContactCard(effect.id))
                }

                is FavoritesEffect.OpenCardStudio -> {
                    overlays.openCardStudio(context, container, effect.subject)
                }
            }
        }
    }
    val listState = rememberLazyListState()
    UpcomingScreen(
        state = state,
        onAction = { action ->
            viewModel.onAction(
                when (action) {
                    is FavoritesAction.Wish -> action.copy(onPage = true)
                    is FavoritesAction.MakeCard -> action.copy(onPage = true)
                    else -> action
                },
            )
        },
        onBack = onBack,
        listState = listState,
    )
}

/** Runs the tab's effects: opens cards, starts the composer, shows toasts. */
@Composable
fun FavoritesEffects(
    effects: Flow<FavoritesEffect>,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController,
    context: Context,
    onCardStudio: (CardStudioSubject) -> Unit = {},
) {
    val latestOverlays = rememberUpdatedState(overlays)
    val latestStudio = rememberUpdatedState(onCardStudio)
    LaunchedEffect(effects, navigator) {
        effects.collect { effect ->
            val toast: (String) -> Unit = { text -> latestOverlays.value.showToast(text) }
            when (effect) {
                is FavoritesEffect.OpenCard -> {
                    navigator.push(ContactsRoute.ContactCard(effect.id))
                }

                is FavoritesEffect.Sms -> {
                    startSafely(
                        context,
                        ShareIntents.sendSmsTo(effect.number, effect.body),
                        toast,
                    )
                }

                is FavoritesEffect.Toast -> {
                    toast(effect.text.resolve(context))
                }

                is FavoritesEffect.SaveCalendar -> {
                    Unit
                }

                is FavoritesEffect.OpenCardStudio -> {
                    latestStudio.value(effect.subject)
                }
            }
        }
    }
}
