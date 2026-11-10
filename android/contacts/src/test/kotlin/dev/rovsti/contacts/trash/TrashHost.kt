package dev.rovsti.contacts.trash

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.chromeFor
import dev.rovsti.contacts.contactsTabItems
import dev.rovsti.contacts.en
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.UiText
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.toImmutableList

/** Holds the page state like the view model would and records what the screen asks for. */
internal class TrashHarness(
    initial: TrashUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<TrashAction>()
    var backs = 0
    val overlays = OverlayController()
}

internal val SampleTrash =
    TrashUiState(
        phase = TrashPhase.Ready,
        rows =
            listOf(
                TrashRow(1, UiText.Literal("Ayşe Yılmaz"), UiText.Literal("Deleted today"), "1"),
                TrashRow(2, UiText.Literal("Burak Demir"), UiText.Literal("Deleted 3 days ago · 27 days left"), "2"),
                TrashRow(3, UiText.Literal("Elif Kaya"), UiText.Literal("Deleted 12 days ago · 18 days left"), "3"),
                TrashRow(4, UiText.Literal("Mehmet Öz"), UiText.Literal("Deleted 29 days ago · 1 day left"), "4"),
            ).toImmutableList(),
    )

/** The Trash pushed over the Settings tab in the real shell (tab bar hidden, as in the app). */
@Composable
internal fun HostedTrash(harness: TrashHarness) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots) }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    LaunchedEffect(Unit) { navigator.push(ContactsRoute.Trash) }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { chromeFor(it, UiText::en) },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<ContactsRoute.ContactsList> {}
        entry<ContactsRoute.Favorites> {}
        entry<ContactsRoute.Groups> {}
        entry<ContactsRoute.Settings> {}
        entry<ContactsRoute.Trash> {
            TrashScreen(state = harness.ui, onAction = { harness.actions += it }, onBack = { harness.backs++ })
        }
    }
}
