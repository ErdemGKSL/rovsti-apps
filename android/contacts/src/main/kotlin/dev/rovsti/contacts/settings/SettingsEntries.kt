package dev.rovsti.contacts.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.R
import dev.rovsti.contacts.findComponentActivity
import dev.rovsti.core.model.contacts.HourChoice
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.resolve
import dev.rovsti.platform.system.permissions.PermissionGroup

/** The settings view model, owned by the activity so a choice page and the root share one state. */
@Composable
fun rememberContactsSettingsViewModel(container: ContactsContainer): ContactsSettingsViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    ContactsSettingsViewModel(
                        book = container.book,
                        store = container.store,
                        repo = container.contacts,
                        ops = container.ops,
                        notifications = { container.permissions.state(PermissionGroup.NOTIFICATIONS).value },
                        hour12 = {
                            !android.text.format.DateFormat
                                .is24HourFormat(container.context)
                        },
                    )
                }
            }
        ViewModelProvider(owner, factory)[ContactsSettingsViewModel::class.java]
    }
}

/** The Settings root: the page over its view model, with the effects (toasts, the notification dialog) handled. */
@Composable
fun ContactsSettingsEntry(
    container: ContactsContainer,
    links: SettingsLinks,
    viewModel: ContactsSettingsViewModel = rememberContactsSettingsViewModel(container),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val latest = rememberUpdatedState(overlays)
    val ask =
        rememberLauncherForActivityResult(
            remember(container) { container.permissions.contract(PermissionGroup.NOTIFICATIONS) },
        ) { answer ->
            viewModel.onAction(SettingsAction.NotificationsAnswered(answer.isNotEmpty() && answer.values.all { it }))
        }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.AskNotifications -> ask.launch(Unit)
                is SettingsEffect.Toast -> latest.value.showToast(effect.text.resolve(context))
            }
        }
    }
    ContactsSettingsPage(state, container.commonSettings, links, viewModel::onAction)
}

@Composable
fun NameFormatEntry(
    viewModel: ContactsSettingsViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val first = stringResource(R.string.settings_format_first_last)
    val last = stringResource(R.string.settings_format_last_first)
    ChoicePage(
        title = stringResource(R.string.settings_name_format),
        options = nameFormatOptions { if (it == dev.rovsti.core.model.search.NameFormat.FirstLast) first else last },
        selected = state.nameFormat,
        onSelect = { viewModel.onAction(SettingsAction.SetNameFormat(it)) },
        footer = stringResource(R.string.settings_name_format_footer),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

@Composable
fun SortByEntry(
    viewModel: ContactsSettingsViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val first = stringResource(R.string.settings_sort_first)
    val last = stringResource(R.string.settings_sort_last)
    ChoicePage(
        title = stringResource(R.string.settings_sort_by),
        options = sortOptions { if (it == dev.rovsti.core.model.phone.SortBy.FirstName) first else last },
        selected = state.sortBy,
        onSelect = { viewModel.onAction(SettingsAction.SetSortBy(it)) },
        footer = stringResource(R.string.settings_sort_by_footer),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

@Composable
fun SaveToEntry(
    viewModel: ContactsSettingsViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    ChoicePage(
        title = stringResource(R.string.settings_save_to),
        options = state.accounts.map { ChoiceOption(it.key, it.label) },
        selected = state.saveTo,
        onSelect = { viewModel.onAction(SettingsAction.SetSaveTo(it)) },
        footer = stringResource(R.string.settings_save_to_footer),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

@Composable
fun SummaryEntry(
    viewModel: ContactsSettingsViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    SummaryPage(state.summary, onBack, onBackLongClick)
}

@Composable
fun ReminderTimeEntry(
    viewModel: ContactsSettingsViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    ChoicePage(
        title = stringResource(R.string.settings_reminder_time),
        options = state.hours.map { h: HourChoice -> ChoiceOption(h.hour, h.label) },
        selected = state.hour,
        onSelect = { viewModel.onAction(SettingsAction.SetHour(it)) },
        footer = stringResource(R.string.settings_reminders_footer),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
