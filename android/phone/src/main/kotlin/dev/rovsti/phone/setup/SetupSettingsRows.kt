package dev.rovsti.phone.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.core.model.phone.ItemState
import dev.rovsti.core.model.phone.SetupItem
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.phone.R
import dev.rovsti.shell.settings.IosSettingsItem

/** The rows of the Setup section of Settings, as test tags. */
object SetupSettingsTags {
    fun row(item: SetupItem) = "settings-setup-${item.name}"

    const val ExactAlarm = "settings-setup-exact-alarm"
}

/**
 * The checklist as rows of the Settings page (E5.12): the same rows the Setup sheet shows, one line each. A row that is
 * not done says what is missing and its first (primary) button's word on the right, and a tap does what that button
 * does; a row that is done shows its check and nothing to press. The extra "Exact reminders" row appears while the
 * exact-alarm grant is missing.
 */
@Composable
fun setupSettingsItems(
    state: SetupUiState,
    onAction: (SetupAction) -> Unit,
): List<IosSettingsItem> {
    val items = mutableListOf<IosSettingsItem>()
    for (row in state.rows) {
        val done = row.state == ItemState.Done
        val press = row.buttons.firstOrNull { it.primary } ?: row.buttons.firstOrNull()
        val icon =
            when (row.state) {
                ItemState.Done -> RovstiIcons.CircleCheck
                else -> RovstiIcons.Circle
            }
        items +=
            IosSettingsItem(
                icon = rememberVectorPainter(icon),
                title = { IosText(row.title, Modifier.testTag(SetupSettingsTags.row(row.item))) },
                description = if (done) null else ({ IosText(row.reason) }),
                trailingContent =
                    press?.takeIf { !done }?.let { button ->
                        { IosText(stringResource(button.act.label())) }
                    },
                onClick =
                    press?.takeIf { !done }?.let { button ->
                        { onAction(SetupAction.Press(row.item, button)) }
                    },
            )
    }
    if (state.exactAlarmMissing) {
        items +=
            IosSettingsItem(
                icon = rememberVectorPainter(RovstiIcons.Circle),
                title = {
                    IosText(
                        stringResource(R.string.settings_exact_alarm_title),
                        Modifier.testTag(SetupSettingsTags.ExactAlarm),
                    )
                },
                description = { IosText(stringResource(R.string.settings_exact_alarm_reason)) },
                trailingContent = { IosText(stringResource(R.string.settings_exact_alarm_action)) },
                onClick = { onAction(SetupAction.OpenExactAlarms) },
            )
    }
    return items
}
