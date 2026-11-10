package dev.rovsti.contacts.groups

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.GroupsTab
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Holds the Groups tab and the group page like the view models would and records what the screens ask for. */
internal class GroupsHarness(
    initial: GroupsUiState = GroupsUi.state(),
    page: GroupPageUi = GroupsUi.family(),
) {
    var tab by mutableStateOf(initial)
    var group by mutableStateOf(page)
    val actions = mutableListOf<GroupsAction>()
    val pageActions = mutableListOf<GroupPageAction>()
    var newTapped = 0
    var renameTapped = 0
    var backs = 0
    val overlays = OverlayController()
    lateinit var listState: LazyListState
}

internal object GroupsUi {
    private fun person(
        id: Long,
        name: String,
        secondary: String? = null,
    ) = PersonItem(id, name, secondary)

    fun state(groups: List<GroupItem> = sample) = GroupsUiState(GroupsPhase.Ready, groups.toImmutableList())

    val sample =
        listOf(
            GroupItem(10, "Family", UiText.Literal("3 contacts")),
            GroupItem(11, "Work", UiText.Literal("2 contacts")),
            GroupItem(12, "Football", UiText.Literal("1 contact")),
            GroupItem(13, "Neighbours", UiText.Literal("5 contacts")),
        )

    val everyone =
        listOf(
            person(1, "Ayşe Yılmaz"),
            person(2, "Burak Demir"),
            person(3, "Elif Kaya", "Work and 1 more"),
            person(4, "Mehmet Öz"),
            person(5, "Özgür Şahin"),
            person(6, "Zeynep Arslan", "Acme Yazılım"),
            person(7, "Ahmet Çelik"),
            person(8, "Can Aydın"),
            person(9, "Deniz Korkmaz"),
            person(10, "İpek Güneş"),
        )

    fun family(members: List<PersonItem> = listOf(everyone[0], everyone[2], everyone[4])) =
        GroupPageUi(
            id = 10,
            title = "Family",
            subtitle = UiText.Literal("${members.size} contacts"),
            joinHint = null,
            members = members.toImmutableList(),
            canMessage = members.isNotEmpty(),
            canEmail = members.isNotEmpty(),
            everyone = everyone.toImmutableList(),
        )
}

/** The Groups tab inside the real shell. */
@Composable
internal fun HostedGroups(
    harness: GroupsHarness,
    haptics: RovstiHaptics? = null,
    openPage: Boolean = false,
) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots).also { it.select(GroupsTab) } }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    harness.listState = rememberLazyListState()
    if (openPage) LaunchedEffect(Unit) { navigator.push(ContactsRoute.GroupPage(harness.group.id)) }
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
            entry<ContactsRoute.Groups> {
                GroupsScreen(
                    state = harness.tab,
                    onAction = { harness.actions += it },
                    onNew = { harness.newTapped++ },
                    listState = rememberLazyListState(),
                )
            }
            entry<ContactsRoute.Settings> {}
            entry<ContactsRoute.GroupPage> {
                GroupPageScreen(
                    page = harness.group,
                    onAction = { harness.pageActions += it },
                    onRename = { harness.renameTapped++ },
                    onBack = { harness.backs++ },
                    listState = rememberLazyListState(),
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
