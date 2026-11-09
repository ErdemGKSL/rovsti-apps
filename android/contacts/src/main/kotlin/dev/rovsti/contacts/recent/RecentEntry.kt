package dev.rovsti.contacts.recent

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.findComponentActivity
import dev.rovsti.design.transition.TabNavigator

/** The Recents view model, owned by the activity (it outlives a card pushed over the page, so the segment stays). */
@Composable
fun rememberRecentListsViewModel(container: ContactsContainer): RecentListsViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer { RecentListsViewModel(container.book, container.store, container.ops) }
            }
        ViewModelProvider(owner, factory)[RecentListsViewModel::class.java]
    }
}

/** The destination of `ContactsRoute.Recent`. */
@Composable
fun RecentPage(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
    onBack: () -> Unit,
) {
    val viewModel = rememberRecentListsViewModel(container)
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel, navigator) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecentEffect.OpenCard -> navigator.push(ContactsRoute.ContactCard(effect.id))
            }
        }
    }
    RecentScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        listState = rememberLazyListState(),
    )
}
