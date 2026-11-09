package dev.rovsti.contacts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.card.ContactCardPage
import dev.rovsti.contacts.cleanup.CleanupPage
import dev.rovsti.contacts.dupes.DupesPage
import dev.rovsti.contacts.editor.ContactEditorPage
import dev.rovsti.contacts.emergency.EmergencyPage
import dev.rovsti.contacts.favorites.FavoritesPage
import dev.rovsti.contacts.favorites.UpcomingPage
import dev.rovsti.contacts.groups.GroupPageDestination
import dev.rovsti.contacts.groups.GroupsPage
import dev.rovsti.contacts.intake.IntakePage
import dev.rovsti.contacts.intake.rememberImportActions
import dev.rovsti.contacts.list.SelectToolbar
import dev.rovsti.contacts.list.rememberContactsListViewModel
import dev.rovsti.contacts.recent.RecentPage
import dev.rovsti.contacts.settings.ContactsSettingsEntry
import dev.rovsti.contacts.settings.NameFormatEntry
import dev.rovsti.contacts.settings.ReminderTimeEntry
import dev.rovsti.contacts.settings.SaveToEntry
import dev.rovsti.contacts.settings.SettingsLinks
import dev.rovsti.contacts.settings.SortByEntry
import dev.rovsti.contacts.settings.SummaryEntry
import dev.rovsti.contacts.settings.rememberContactsSettingsViewModel
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
    // "Recently added" (C15) is marked on every pull of the book, whichever page is open.
    remember(container) { container.recentTracker }
    // Birthday alarms follow the book, the switch and the hour (C22).
    remember(container) { container.birthdays }
    // Select mode (E4.8) folds the tab bar and puts its toolbar beside the circle.
    val list = rememberContactsListViewModel(container)
    val select by list.selectToolbar.collectAsState()
    val selecting = select != null
    val selectAccessory: (@Composable (Modifier, Boolean) -> Unit)? =
        select?.let { bar ->
            { modifier, inline -> SelectToolbar(bar, list::onAction, overlays, modifier, inline) }
        }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { route -> chromeFor(route, selecting) },
        overlays = overlays,
        modifier = Modifier.fillMaxSize(),
        accessory = selectAccessory,
    ) {
        entry<ContactsRoute.ContactsList> { ContactsListPage(container, navigator) }
        entry<ContactsRoute.Favorites> { FavoritesPage(container, navigator) }
        entry<ContactsRoute.Groups> { GroupsPage(container, navigator) }
        entry<ContactsRoute.Settings> {
            val import = rememberImportActions(container, navigator, overlays)
            ContactsSettingsEntry(
                container,
                SettingsLinks(
                    openNameFormat = { navigator.push(ContactsRoute.SettingsNameFormat) },
                    openSortBy = { navigator.push(ContactsRoute.SettingsSortBy) },
                    openSaveTo = { navigator.push(ContactsRoute.SettingsSaveTo) },
                    openSummary = { navigator.push(ContactsRoute.SettingsSummary) },
                    openReminderTime = { navigator.push(ContactsRoute.SettingsReminderTime) },
                    importFile = import.importFile,
                    exportVcf = import.exportVcf,
                    exportCsv = import.exportCsv,
                    importCsv = import.importCsv,
                    findDuplicates = { navigator.push(ContactsRoute.Duplicates) },
                    cleanUp = { navigator.push(ContactsRoute.Cleanup) },
                    scanQr = import.scanQr,
                    pasteContact = import.pasteContact,
                    emergency = { navigator.push(ContactsRoute.Emergency) },
                    trash = { navigator.push(ContactsRoute.Trash) },
                    whatsApp = { navigator.push(ContactsRoute.WhatsAppSettings) },
                    openCommon = { page ->
                        navigator.push(
                            when (page) {
                                CommonSettingsPage.Appearance -> ContactsRoute.Appearance
                                CommonSettingsPage.Haptics -> ContactsRoute.Haptics
                                CommonSettingsPage.About -> ContactsRoute.About
                                CommonSettingsPage.Licences -> ContactsRoute.Licences
                            },
                        )
                    },
                ),
            )
        }
        entry<ContactsRoute.SettingsNameFormat> {
            NameFormatEntry(rememberContactsSettingsViewModel(container), pop, popToRoot)
        }
        entry<ContactsRoute.SettingsSortBy> {
            SortByEntry(
                rememberContactsSettingsViewModel(container),
                pop,
                popToRoot,
            )
        }
        entry<ContactsRoute.SettingsSaveTo> {
            SaveToEntry(
                rememberContactsSettingsViewModel(container),
                pop,
                popToRoot,
            )
        }
        entry<ContactsRoute.SettingsSummary> {
            SummaryEntry(
                rememberContactsSettingsViewModel(container),
                pop,
                popToRoot,
            )
        }
        entry<ContactsRoute.SettingsReminderTime> {
            ReminderTimeEntry(rememberContactsSettingsViewModel(container), pop, popToRoot)
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
        entry<ContactsRoute.Intake> { route -> IntakePage(container, route, navigator) }
        // The pages below arrive with E4.2-E4.9; until then a route that a link or a later screen can reach still shows
        // an honest placeholder instead of nothing.
        entry<ContactsRoute.ContactCard> { route -> ContactCardPage(container, route.id, navigator, pop) }
        entry<ContactsRoute.ContactEditor> { route ->
            ContactEditorPage(container, route, outcome = { _, _ -> }, onClosed = pop)
        }
        entry<ContactsRoute.Trash> { TrashPage(container, pop) }
        entry<ContactsRoute.GroupPage> { route -> GroupPageDestination(container, route.id, navigator, pop) }
        entry<ContactsRoute.UpcomingBirthdays> { UpcomingPage(container, navigator, pop) }
        entry<ContactsRoute.Duplicates> { DupesPage(container, pop) }
        entry<ContactsRoute.Cleanup> { CleanupPage(container, pop) }
        entry<ContactsRoute.Emergency> { EmergencyPage(container, navigator, pop) }
        entry<ContactsRoute.Recent> { RecentPage(container, navigator, pop) }
    }
}
