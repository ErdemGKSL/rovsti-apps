package dev.rovsti.contacts.settings

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.contacts.AccountCount
import dev.rovsti.core.model.contacts.HourChoice
import dev.rovsti.core.model.contacts.StatRow
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One account the new contacts can be saved to: [key] is the provider's (empty: on this phone). */
@Immutable
data class AccountOption(
    val key: String,
    val label: String,
)

/** The counts of the Contacts summary page (C29), as `contacts.stats` words them. */
@Immutable
data class SummaryUi(
    val totalText: String = "",
    val rows: ImmutableList<StatRow> = persistentListOf(),
    val accounts: ImmutableList<AccountCount> = persistentListOf(),
)

/** The "WhatsApp Names and Photos" rows (X.1b): what the last sync did and whether one is running. */
@Immutable
data class PeopleSyncUi(
    /** The status line: "Synced, 5 min. ago", "Checking numbers 30/120", "WhatsApp limit reached. Continues in 4 min". */
    val last: UiText = UiText.Literal(""),
    /** A run is active: "Sync Now" is "Stop" and the progress bar shows. */
    val running: Boolean = false,
    /** Of the current stage, 0..1, while [running]. */
    val progress: Float? = null,
    /** A resumable run exists: the "Continue Where It Left Off" row shows. */
    val canContinue: Boolean = false,
    /** False while WhatsApp's limit runs (D8): both buttons wait for the ETA. */
    val continueEnabled: Boolean = true,
    val syncEnabled: Boolean = true,
    /** "Sync Now" asks "Start over?" first (a resumable run would be replaced). */
    val confirmRestart: Boolean = false,
)

/** What the Settings root and its choice pages show. */
@Immutable
data class ContactsSettingsState(
    val nameFormat: NameFormat = NameFormat.FirstLast,
    val sortBy: SortBy = SortBy.FirstName,
    /** Accounts new contacts can go to; the Save To row shows only when there is more than one. */
    val accounts: ImmutableList<AccountOption> = persistentListOf(),
    /** The key that is chosen now (the saved one when it still exists, else this phone). */
    val saveTo: String = "",
    val saveToLabel: String = "",
    /** "512 contacts", empty until the book is read. */
    val summaryText: String = "",
    val summary: SummaryUi = SummaryUi(),
    val reminders: Boolean = false,
    val hour: Int = 9,
    val hourLabel: String = "",
    val hours: ImmutableList<HourChoice> = persistentListOf(),
    /** The book can be exported (there is at least one contact). */
    val hasContacts: Boolean = false,
    /** The sync rows; null while WhatsApp is not linked (the cache is only fed while it is). */
    val peopleSync: PeopleSyncUi? = null,
) {
    val showSaveTo: Boolean get() = accounts.size > 1
}

sealed interface SettingsAction {
    data class SetNameFormat(
        val value: NameFormat,
    ) : SettingsAction

    data class SetSortBy(
        val value: SortBy,
    ) : SettingsAction

    data class SetSaveTo(
        val key: String,
    ) : SettingsAction

    /** The switch: turning on asks for the notification permission first when it is missing. */
    data class SetReminders(
        val on: Boolean,
    ) : SettingsAction

    data class SetHour(
        val hour: Int,
    ) : SettingsAction

    /** The "Sync Now" row of the WhatsApp names and photos (after "Start over?" when a run is resumable). */
    data object SyncPeopleNow : SettingsAction

    /** "Continue Where It Left Off". */
    data object ContinuePeople : SettingsAction

    /** "Stop" while a sync runs. */
    data object StopPeople : SettingsAction

    /** The answer of the system dialog that [SettingsEffect.AskNotifications] opened. */
    data class NotificationsAnswered(
        val granted: Boolean,
    ) : SettingsAction
}

sealed interface SettingsEffect {
    /** Show the system notification dialog and answer with [SettingsAction.NotificationsAnswered]. */
    data object AskNotifications : SettingsEffect

    data class Toast(
        val text: UiText,
    ) : SettingsEffect
}
