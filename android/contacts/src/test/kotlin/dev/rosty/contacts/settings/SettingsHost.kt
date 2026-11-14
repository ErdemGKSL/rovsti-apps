package dev.rosty.contacts.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rosty.contacts.ContactsRoots
import dev.rosty.contacts.ContactsRoute
import dev.rosty.contacts.ContactsTabs
import dev.rosty.contacts.SettingsTab
import dev.rosty.contacts.chromeFor
import dev.rosty.contacts.contactsTabItems
import dev.rosty.contacts.en
import dev.rosty.core.model.contacts.AccountCount
import dev.rosty.core.model.contacts.HourChoice
import dev.rosty.core.model.contacts.StatRow
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.haptics.RostyHaptics
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.text.UiText
import dev.rosty.design.transition.TabNavigator
import dev.rosty.shell.RostyShell
import dev.rosty.shell.settings.FakeCommonSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Holds the state like the view model would and records what the page asks for. */
internal class SettingsHarness(
    initial: ContactsSettingsState = SettingsSample.state(),
    val common: FakeCommonSettings = FakeCommonSettings(),
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<SettingsAction>()
    val opened = mutableListOf<String>()
    val overlays = OverlayController()

    fun on(action: SettingsAction) {
        actions += action
        ui =
            when (action) {
                is SettingsAction.SetNameFormat -> {
                    ui.copy(nameFormat = action.value)
                }

                is SettingsAction.SetSortBy -> {
                    ui.copy(sortBy = action.value)
                }

                is SettingsAction.SetSaveTo -> {
                    ui.copy(saveTo = action.key, saveToLabel = ui.accounts.first { it.key == action.key }.label)
                }

                is SettingsAction.SetReminders -> {
                    ui.copy(reminders = action.on)
                }

                is SettingsAction.SetHour -> {
                    ui.copy(hour = action.hour, hourLabel = ui.hours.first { it.hour == action.hour }.label)
                }

                is SettingsAction.NotificationsAnswered -> {
                    ui
                }

                SettingsAction.SyncPeopleNow -> {
                    ui.copy(peopleSync = ui.peopleSync?.copy(running = true))
                }

                SettingsAction.ContinuePeople -> {
                    ui.copy(peopleSync = ui.peopleSync?.copy(running = true, canContinue = false))
                }

                SettingsAction.StopPeople -> {
                    ui.copy(peopleSync = ui.peopleSync?.copy(running = false, canContinue = true, progress = null))
                }
            }
    }

    fun links() =
        SettingsLinks(
            openNameFormat = { opened += "name-format" },
            openSortBy = { opened += "sort-by" },
            openSaveTo = { opened += "save-to" },
            openSummary = { opened += "summary" },
            openReminderTime = { opened += "reminder-time" },
            importFile = { opened += "import-file" },
            exportVcf = { opened += "export-vcf" },
            exportCsv = { opened += "export-csv" },
            importCsv = { opened += "import-csv" },
            findDuplicates = { opened += "duplicates" },
            cleanUp = { opened += "clean-up" },
            scanQr = { opened += "scan-qr" },
            pasteContact = { opened += "paste" },
            emergency = { opened += "emergency" },
            trash = { opened += "trash" },
            whatsApp = { opened += "whatsapp" },
        )
}

internal object SettingsSample {
    val hours =
        (6..21).map {
            HourChoice(
                it,
                "%d:00 %s".format(
                    if (it % 12 ==
                        0
                    ) {
                        12
                    } else {
                        it % 12
                    },
                    if (it < 12) "AM" else "PM",
                ),
            )
        }

    fun state(
        reminders: Boolean = false,
        accounts: Boolean = false,
    ): ContactsSettingsState {
        val list =
            if (accounts) {
                listOf(
                    AccountOption("", "On this phone"),
                    AccountOption("com.google|ayse@example.org", "Google (ayse@example.org)"),
                    AccountOption("com.whatsapp|", "WhatsApp"),
                )
            } else {
                listOf(AccountOption("", "On this phone"))
            }
        return ContactsSettingsState(
            accounts = list.toImmutableList(),
            saveTo = "com.google|ayse@example.org".takeIf { accounts }.orEmpty(),
            saveToLabel = if (accounts) "Google (ayse@example.org)" else "On this phone",
            summaryText = "428 contacts",
            summary = summary(),
            reminders = reminders,
            hour = 9,
            hourLabel = "9:00 AM",
            hours = hours.toImmutableList(),
            hasContacts = true,
        )
    }

    fun summary() =
        SummaryUi(
            totalText = "428 contacts",
            rows =
                persistentListOf(
                    StatRow("Contacts", "428"),
                    StatRow("With a phone number", "401"),
                    StatRow("With an email", "187"),
                    StatRow("With a birthday", "64"),
                    StatRow("Starred", "12"),
                ),
            accounts =
                persistentListOf(
                    AccountCount("", "On this phone", 233),
                    AccountCount("com.google|ayse@example.org", "Google (ayse@example.org)", 188),
                    AccountCount("com.whatsapp|", "WhatsApp", 7),
                ),
        )
}

/** [content] shown on [route] of the real shell (tab bar and chrome as in the app). */
@Composable
internal fun HostedSettings(
    harness: SettingsHarness,
    route: ContactsRoute = ContactsRoute.Settings,
    haptics: RostyHaptics? = null,
    content: @Composable () -> Unit,
) {
    val navigator =
        remember {
            TabNavigator(ContactsTabs, ContactsRoots).also {
                it.select(SettingsTab)
                if (route != ContactsRoute.Settings) it.push(route)
            }
        }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    val body: @Composable () -> Unit = {
        RostyShell(
            navigator = navigator,
            tabs = tabs,
            chromeFor = { chromeFor(it, UiText::en) },
            overlays = harness.overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<ContactsRoute.ContactsList> {}
            entry<ContactsRoute.Favorites> {}
            entry<ContactsRoute.Groups> {}
            entry<ContactsRoute.Settings> { if (route == ContactsRoute.Settings) content() }
            entry<ContactsRoute.SettingsNameFormat> { content() }
            entry<ContactsRoute.SettingsSortBy> { content() }
            entry<ContactsRoute.SettingsSaveTo> { content() }
            entry<ContactsRoute.SettingsSummary> { content() }
            entry<ContactsRoute.SettingsReminderTime> { content() }
            entry<ContactsRoute.WhatsAppSettings> { content() }
            entry<ContactsRoute.Appearance> { content() }
            entry<ContactsRoute.Haptics> { content() }
            entry<ContactsRoute.About> { content() }
            entry<ContactsRoute.Licences> { content() }
            entry<ContactsRoute.LicenceDetail> { content() }
        }
    }
    if (haptics != null) CompositionLocalProvider(LocalRostyHaptics provides haptics) { body() } else body()
}
