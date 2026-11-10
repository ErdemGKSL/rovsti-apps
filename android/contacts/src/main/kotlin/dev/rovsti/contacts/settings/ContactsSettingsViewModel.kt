package dev.rovsti.contacts.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.AccountLabelRequest
import dev.rovsti.core.model.contacts.DraftsRequest
import dev.rovsti.core.model.contacts.ReminderAnswer
import dev.rovsti.core.model.contacts.ReminderRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.system.permissions.NextAction
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.system.permissions.nextAction
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Contacts settings (E4.15). The name format, the sort order, the account new contacts are saved to and the
 * birthday reminders are fields of `contacts-prefs.json` ([ContactsStore]); the lists read the same flow, so changing
 * the name format re-sorts the Contacts tab through `contacts.view` at once. The words of the summary and of the hour
 * rows are the core's (`contacts.stats`, `contacts.birthday_reminders`). The alarms themselves are armed by
 * `BirthdayAlarms`, which follows the switch and the hour.
 *
 * Turning reminders on asks for the notification permission first when [notifications] says Android still shows the
 * dialog; the page launches it and answers with [SettingsAction.NotificationsAnswered].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsSettingsViewModel(
    private val book: BookRepository,
    private val store: ContactsStore,
    private val repo: ContactsRepository,
    private val ops: ContactsOps,
    private val notifications: () -> PermissionState,
    private val hour12: () -> Boolean = { false },
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val accounts = MutableStateFlow<List<AccountOption>>(emptyList())
    private val outbox = Channel<SettingsEffect>(Channel.UNLIMITED)

    val state: StateFlow<ContactsSettingsState> =
        combine(store.prefs, book.book, accounts) { p, b, a -> Triple(p, b, a) }
            .conflate()
            .mapLatest { (p, b, a) -> build(p, b, a) }
            .flowOn(worker)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ContactsSettingsState())

    val effects: Flow<SettingsEffect> = outbox.receiveAsFlow()

    init {
        viewModelScope.launch {
            store.load()
            accounts.value = loadAccounts()
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetNameFormat -> save { it.copy(nameFormat = action.value) }
            is SettingsAction.SetSortBy -> save { it.copy(sortBy = action.value) }
            is SettingsAction.SetSaveTo -> save { it.copy(saveTo = action.key) }
            is SettingsAction.SetHour -> save { it.copy(reminderHour = action.hour.coerceIn(0, LAST_HOUR)) }
            is SettingsAction.SetReminders -> setReminders(action.on)
            is SettingsAction.NotificationsAnswered -> answered(action.granted)
        }
    }

    private fun save(
        change: (dev.rovsti.core.model.contacts.ContactsPrefs) -> dev.rovsti.core.model.contacts.ContactsPrefs,
    ) {
        viewModelScope.launch {
            if (store.update(change) is Outcome.Err) outbox.trySend(SettingsEffect.Toast(CANT_SAVE))
        }
    }

    private fun setReminders(on: Boolean) {
        if (!on) {
            viewModelScope.launch { turn(false) }
            return
        }
        when (nextAction(notifications())) {
            NextAction.None -> viewModelScope.launch { turn(true) }
            NextAction.Ask -> outbox.trySend(SettingsEffect.AskNotifications)
            NextAction.OpenSettings -> outbox.trySend(SettingsEffect.Toast(DENIED))
        }
    }

    private fun answered(granted: Boolean) {
        if (granted) viewModelScope.launch { turn(true) } else outbox.trySend(SettingsEffect.Toast(DENIED))
    }

    private suspend fun turn(on: Boolean) {
        val saved = store.update { it.copy(birthdayReminders = on) }
        if (saved is Outcome.Err) {
            outbox.trySend(SettingsEffect.Toast(CANT_SAVE))
            return
        }
        val label = (saved as Outcome.Ok).value.reminderHour.let { hourLabel(it) }
        outbox.trySend(
            SettingsEffect.Toast(
                if (on) {
                    UiText.Res(
                        R.string.contacts_birthday_reminders_on,
                        listOf(label),
                    )
                } else {
                    UiText.Res(R.string.contacts_birthday_reminders_off)
                },
            ),
        )
    }

    private suspend fun hourLabel(hour: Int): String = reminder(emptyList(), hour)?.hourLabel.orEmpty()

    private suspend fun reminder(
        drafts: List<dev.rovsti.core.model.contacts.ContactDraft>,
        hour: Int,
    ): ReminderAnswer? =
        (
            ops.birthdayReminders(
                ReminderRequest(drafts, 0L, 0, hour.coerceIn(0, LAST_HOUR), hour12(), emptyList()),
            ) as? Outcome.Ok
        )?.value

    private suspend fun loadAccounts(): List<AccountOption> {
        val list = (repo.accounts() as? ContactResult.Ok)?.value.orEmpty().ifEmpty { listOf(Account()) }
        return list.map { AccountOption(it.key, labelOf(it)) }
    }

    private suspend fun labelOf(account: Account): String =
        (ops.accountLabel(AccountLabelRequest(account.key)) as? Outcome.Ok)?.value?.label ?: account.name

    private suspend fun build(
        prefs: dev.rovsti.core.model.contacts.ContactsPrefs,
        b: Book,
        list: List<AccountOption>,
    ): ContactsSettingsState {
        val chosen =
            list.firstOrNull { it.key == prefs.saveTo } ?: list.firstOrNull { it.key.isEmpty() } ?: list.firstOrNull()
        val plan = reminder(emptyList(), prefs.reminderHour)
        val ready = b as? Book.Ready
        val stats = ready?.let { (ops.stats(DraftsRequest(it.drafts)) as? Outcome.Ok)?.value }
        return ContactsSettingsState(
            nameFormat = prefs.nameFormat,
            sortBy = prefs.sortBy,
            accounts = list.toImmutableList(),
            saveTo = chosen?.key.orEmpty(),
            saveToLabel = chosen?.label.orEmpty(),
            summaryText = stats?.totalText.orEmpty(),
            summary =
                stats?.let { SummaryUi(it.totalText, it.rows.toImmutableList(), it.accounts.toImmutableList()) }
                    ?: SummaryUi(),
            reminders = prefs.birthdayReminders,
            hour = prefs.reminderHour.coerceIn(0, LAST_HOUR),
            hourLabel = plan?.hourLabel.orEmpty(),
            hours = plan?.hours.orEmpty().toImmutableList(),
            hasContacts = ready?.drafts?.isNotEmpty() == true,
        )
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val DENIED: UiText = UiText.Res(R.string.contacts_notifications_off)
        val CANT_SAVE: UiText = UiText.Res(R.string.contacts_cant_save_setting)
        private const val LAST_HOUR = 23
    }
}
