package dev.rovsti.contacts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.card.ContactCardPage
import dev.rovsti.contacts.editor.ContactEditorPage
import dev.rovsti.contacts.trash.TrashPage
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell
import dev.rovsti.shell.settings.CommonSettingsPage

/** The screens of Rovsti Contacts: one [RovstiShell] over the activity's navigator. */
@Composable
fun ContactsApp(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController = remember { OverlayController() },
) {
    val tabs =
        contactsTabItems(
            contacts = stringResource(R.string.tab_contacts),
            favorites = stringResource(R.string.tab_favorites),
            groups = stringResource(R.string.tab_groups),
            settings = stringResource(R.string.tab_settings),
        )
    val pop: () -> Unit =
        remember(navigator) {
            {
                navigator.pop()
                Unit
            }
        }
    // A long press on a back button jumps to the root of the tab: selecting the current tab does that.
    val popToRoot: () -> Unit = remember(navigator) { { navigator.select(navigator.currentTab) } }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = ::chromeFor,
        overlays = overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<ContactsRoute.ContactsList> { ContactsListPage(container, navigator) }
        entry<ContactsRoute.Favorites> {
            TabPlaceholderPage(
                stringResource(R.string.tab_favorites),
                navigator,
                FavoritesTab,
            )
        }
        entry<ContactsRoute.Groups> { TabPlaceholderPage(stringResource(R.string.tab_groups), navigator, GroupsTab) }
        entry<ContactsRoute.Settings> {
            ContactsSettingsPage(
                settings = container.commonSettings,
                onOpenWhatsApp = { navigator.push(ContactsRoute.WhatsAppSettings) },
                onOpenTrash = { navigator.push(ContactsRoute.Trash) },
                onOpenCommon = { page ->
                    navigator.push(
                        when (page) {
                            CommonSettingsPage.Appearance -> ContactsRoute.Appearance
                            CommonSettingsPage.Haptics -> ContactsRoute.Haptics
                            CommonSettingsPage.About -> ContactsRoute.About
                            CommonSettingsPage.Licences -> ContactsRoute.Licences
                        },
                    )
                },
            )
        }
        entry<ContactsRoute.WhatsAppSettings> { WhatsAppSettingsEntry(container) }
        entry<ContactsRoute.Appearance> { AppearanceEntry(container.commonSettings, pop, popToRoot) }
        entry<ContactsRoute.Haptics> { HapticsEntry(container.commonSettings, pop, popToRoot) }
        entry<ContactsRoute.About> {
            AboutEntry(container, onOpenLicences = { navigator.push(ContactsRoute.Licences) }, pop, popToRoot)
        }
        entry<ContactsRoute.Licences> {
            LicencesEntry(onOpen = { navigator.push(ContactsRoute.LicenceDetail(it)) }, pop, popToRoot)
        }
        entry<ContactsRoute.LicenceDetail> { route -> LicenceDetailEntry(route.id, pop, popToRoot) }
        entry<ContactsRoute.Intake> { IntakePlaceholderPage(pop) }
        // The pages below arrive with E4.2-E4.9; until then a route that a link or a later screen can reach still shows
        // an honest placeholder instead of nothing.
        entry<ContactsRoute.ContactCard> { route -> ContactCardPage(container, route.id, navigator, pop) }
        entry<ContactsRoute.ContactEditor> { route ->
            ContactEditorPage(container, route, outcome = { _, _ -> }, onClosed = pop)
        }
        entry<ContactsRoute.Trash> { TrashPage(container, pop) }
        entry<ContactsRoute.GroupPage> { route -> ComingPage("Group ${route.id}", pop) }
        entry<ContactsRoute.UpcomingBirthdays> { ComingPage("Upcoming Birthdays", pop) }
    }
}
