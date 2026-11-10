package dev.rovsti.contacts.emergency

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.UiText
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.toImmutableList

/** Holds the page state like the view model would (a drop reorders it) and records what the screen asks for. */
internal class EmergencyHarness(
    initial: EmergencyUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<EmergencyAction>()
    val overlays = OverlayController()
    var backs = 0
    lateinit var listState: LazyListState

    fun on(action: EmergencyAction) {
        actions += action
        when (action) {
            EmergencyAction.ToggleEdit -> {
                ui = ui.copy(editing = !ui.editing)
            }

            is EmergencyAction.Move -> {
                val list = ui.people.toMutableList()
                list.add(action.to, list.removeAt(action.from))
                ui = ui.copy(people = list.toImmutableList())
            }

            else -> {
                Unit
            }
        }
    }
}

internal object EmergencyUi {
    private fun person(
        id: Long,
        name: String,
        number: String,
    ) = PersonItem(id, name, number)

    /** Numbers in the display format of `contacts.card`. */
    val people =
        listOf(
            person(3, "Elif Kaya", "+90 212 555 00 11"),
            person(1, "Ayşe Yılmaz", "+90 555 111 22 33"),
            person(8, "Can Aydın", "0506 333 44 55"),
        )

    fun state(
        people: List<PersonItem> = this.people,
        editing: Boolean = false,
    ) = EmergencyUiState(
        phase = EmergencyPhase.Ready,
        people = people.toImmutableList(),
        editing = editing && people.size >= 2,
        shareText =
            "Emergency contacts:\n" +
                people.mapIndexed { i, p -> "${i + 1}. ${p.name}: ${p.secondary}" }.joinToString("\n"),
        pickables =
            listOf(
                person(2, "Burak Demir", "0532 123 45 67"),
                person(7, "Ahmet Çelik", "0505 222 33 44"),
            ).toImmutableList(),
    )
}

/** The page pushed over the Contacts tab, as in the app (tab bar hidden on this route). */
@Composable
internal fun HostedEmergency(
    harness: EmergencyHarness,
    haptics: RovstiHaptics? = null,
) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots).also { it.push(ContactsRoute.Emergency) } }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    harness.listState = rememberLazyListState()
    val content: @Composable () -> Unit = {
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
            entry<ContactsRoute.Emergency> {
                EmergencyScreen(
                    state = harness.ui,
                    onAction = harness::on,
                    onBack = { harness.backs++ },
                    listState = harness.listState,
                )
            }
        }
    }
    if (haptics != null) {
        CompositionLocalProvider(LocalRovstiHaptics provides haptics) { content() }
    } else {
        content()
    }
}
