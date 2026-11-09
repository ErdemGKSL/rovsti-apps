package dev.rovsti.contacts.emergency

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.findComponentActivity
import dev.rovsti.contacts.startSafely
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.files.ShareIntents

/** The Emergency Contacts view model, owned by the activity (it outlives a card pushed over the page). */
@Composable
fun rememberEmergencyViewModel(container: ContactsContainer): EmergencyViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer { EmergencyViewModel(container.book, container.store, container.ops) }
            }
        ViewModelProvider(owner, factory)[EmergencyViewModel::class.java]
    }
}

/** The destination of `ContactsRoute.Emergency`. */
@Composable
fun EmergencyPage(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
    onBack: () -> Unit,
) {
    val viewModel = rememberEmergencyViewModel(container)
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    val latestOverlays = rememberUpdatedState(overlays)
    LaunchedEffect(viewModel, navigator) {
        viewModel.effects.collect { effect ->
            val toast: (String) -> Unit = { text -> latestOverlays.value.showToast(text) }
            when (effect) {
                is EmergencyEffect.OpenCard -> navigator.push(ContactsRoute.ContactCard(effect.id))
                is EmergencyEffect.Dial -> startSafely(context, dialIntent(effect.number), toast)
                is EmergencyEffect.Share -> startSafely(context, ShareIntents.text(effect.text), toast)
                is EmergencyEffect.Toast -> toast(effect.text)
            }
        }
    }
    EmergencyScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        listState = rememberLazyListState(),
    )
}

private fun dialIntent(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
