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
