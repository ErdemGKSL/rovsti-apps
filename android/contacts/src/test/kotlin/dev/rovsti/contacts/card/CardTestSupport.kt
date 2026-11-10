package dev.rovsti.contacts.card

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.R
import dev.rovsti.contacts.chromeFor
import dev.rovsti.contacts.contactsTabItems
import dev.rovsti.contacts.en
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.UiText
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.design.transition.rovstiSharedBounds
import dev.rovsti.shell.RovstiShell
import kotlinx.coroutines.flow.MutableStateFlow

/** Holds the page state the way the view model would and records what the screen asks for. */
internal class CardHarness(
    initial: ContactCardUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<ContactCardAction>()
    var backs = 0
    lateinit var listState: LazyListState
    val overlays = OverlayController()
    val navigator = TabNavigator(ContactsTabs, ContactsRoots)

    fun on(action: ContactCardAction) {
        actions += action
    }
}

/** The card inside the real shell (backdrop, overlay host, page transitions); the tab bar is hidden on this route. */
@Composable
internal fun HostedCard(
    harness: CardHarness,
    live: ContactCardViewModel? = null,
    gate: (@Composable () -> Unit)? = null,
    startOnCard: Boolean = true,
    list: (@Composable () -> Unit)? = null,
    callsModel: CallOptionsViewModel? = null,
    photoViewer: PhotoViewerLauncher? = null,
) {
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    val navigator = harness.navigator
    remember { if (startOnCard) navigator.push(ContactsRoute.ContactCard(harness.ui.header?.id ?: 1)) else false }
    harness.listState = rememberLazyListState()
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { chromeFor(it, UiText::en) },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<ContactsRoute.ContactsList> { list?.invoke() }
        entry<ContactsRoute.Favorites> {}
        entry<ContactsRoute.Groups> {}
        entry<ContactsRoute.Settings> {}
        entry<ContactsRoute.ContactCard> {
            if (live != null) {
                val context: Context = LocalContext.current
                ContactCardEffects(live, navigator, harness.overlays, context) {}
                val state by live.state.collectAsState()
                val calls by (callsModel?.state ?: MutableStateFlow<CallOptionsUiState?>(null)).collectAsState()
                if (callsModel != null) CallOptionsEffects(callsModel, harness.overlays, context)
                ContactCardScreen(
                    state,
                    live::onAction,
                    { harness.backs++ },
                    listState = harness.listState,
                    gate = gate,
                    calls = calls,
                    onCalls = { callsModel?.onAction(it) },
                    photoViewer = photoViewer,
                )
            } else {
                ContactCardScreen(
                    harness.ui,
                    harness::on,
                    { harness.backs++ },
                    listState = harness.listState,
                    gate = gate,
                )
            }
        }
    }
}

/** A stand-in list page for the shared-element test: one row whose avatar has the key the real list row uses. */
@Composable
internal fun FakeListPage(id: Long) {
    Avatar(
        "Ayşe Yılmaz",
        modifier = Modifier.rovstiSharedBounds("avatar-$id").testTag("fake-avatar-source"),
        colorKey = id.toString(),
    )
}

/** The row text under "Block This Caller" when this app is not the default phone app. */
internal val NEEDS_ROLE_TEXT: String
    get() = UiText.Res(R.string.contacts_set_default_phone).en()
