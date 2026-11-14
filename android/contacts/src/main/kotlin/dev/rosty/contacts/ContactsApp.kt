package dev.rosty.contacts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.rosty.contacts.card.ContactCardPage
import dev.rosty.contacts.cleanup.CleanupPage
import dev.rosty.contacts.dupes.DupesPage
import dev.rosty.contacts.editor.ContactEditorPage
import dev.rosty.contacts.emergency.EmergencyPage
import dev.rosty.contacts.favorites.FavoritesPage
import dev.rosty.contacts.favorites.UpcomingPage
import dev.rosty.contacts.groups.GroupPageDestination
import dev.rosty.contacts.groups.GroupsPage
import dev.rosty.contacts.intake.IntakePage
import dev.rosty.contacts.intake.rememberImportActions
import dev.rosty.contacts.list.SelectToolbar
import dev.rosty.contacts.list.rememberContactsListViewModel
import dev.rosty.contacts.people.PeopleSync
import dev.rosty.contacts.recent.RecentPage
import dev.rosty.contacts.settings.ContactsSettingsEntry
import dev.rosty.contacts.settings.NameFormatEntry
import dev.rosty.contacts.settings.ReminderTimeEntry
import dev.rosty.contacts.settings.SaveToEntry
import dev.rosty.contacts.settings.SettingsLinks
import dev.rosty.contacts.settings.SortByEntry
import dev.rosty.contacts.settings.SummaryEntry
import dev.rosty.contacts.settings.rememberContactsSettingsViewModel
import dev.rosty.contacts.trash.TrashPage
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.text.UiText
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.shell.RostyShell
import dev.rosty.shell.settings.CommonSettingsPage

/** The screens of Rosty Contacts: one [RostyShell] over the activity's navigator. */
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
    val context = LocalContext.current
    RostyShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { route -> chromeFor(route, selecting) { it.resolve(context) } },
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
                                CommonSettingsPage.Logs -> ContactsRoute.Logs()
                            },
                        )
                    },
                    openSyncDetails = { navigator.push(ContactsRoute.Logs(tag = PeopleSync.TAG)) },
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
        entry<ContactsRoute.Logs> { route -> LogsEntry(container, route.tag, pop, popToRoot) }
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
