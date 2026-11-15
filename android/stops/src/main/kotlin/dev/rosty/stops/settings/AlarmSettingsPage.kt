package dev.rosty.stops.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.stops.VibrationPattern
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.platform.location.AlarmPreview
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsPage
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer

/** Test tags of the Alarm page. */
object AlarmTags {
    const val Page = "stops-alarm-page"
    const val Sound = "stops-alarm-sound"
    const val Vibrate = "stops-alarm-vibrate"
    const val Picker = "stops-alarm-picker"
    const val Snooze = "stops-alarm-snooze"
    const val TestVolume = "stops-alarm-test-volume"
    const val TestAlarm = "stops-alarm-test"
    const val Reset = "stops-alarm-reset"

    fun volume(pct: Int) = "stops-alarm-volume-$pct"

    fun pattern(id: VibrationPattern) = "stops-alarm-pattern-${id.name.lowercase()}"
}

/** What the rows of the Alarm page do; no-op defaults for tests. */
@Immutable
class AlarmPageActions(
    val toggleSound: () -> Unit = {},
    val toggleVibrate: () -> Unit = {},
    val pickSound: () -> Unit = {},
    val nextSnooze: () -> Unit = {},
    val setVolume: (Int) -> Unit = {},
    val testVolume: () -> Unit = {},
    val setVibration: (VibrationChoiceUi) -> Unit = {},
    val testAlarm: () -> Unit = {},
    val reset: () -> Unit = {},
)

/** Settings > Alarm, pushed over the map. The test sound stops when the page goes away. */
@Composable
fun AlarmSettingsEntry(
    container: StopsContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val vm = rememberSettingsViewModel(container)
    val state by vm.state.collectAsState()
    SettingsEffects(vm.effects)
    val context = LocalContext.current
    val overlays = LocalOverlayOrNull.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            vm.onSoundPicked(AlarmPreview.picked(context, result.resultCode, result.data))
        }
    DisposableEffect(vm) { onDispose { vm.leaveAlarm() } }
    AlarmSettingsPage(
        alarm = state.alarm,
        actions =
            AlarmPageActions(
                toggleSound = vm::toggleSound,
                toggleVibrate = vm::toggleVibrate,
                pickSound = {
                    try {
                        picker.launch(AlarmPreview.pickerIntent(state.alarm.soundUri))
                    } catch (_: android.content.ActivityNotFoundException) {
                        vm.pickerMissing()
                    }
                },
                nextSnooze = vm::nextSnooze,
                setVolume = vm::setVolume,
                testVolume = vm::testVolume,
                setVibration = vm::setVibration,
                testAlarm = vm::testAlarm,
                reset = {
                    overlays?.confirm(
                        context,
                        heading = context.getString(R.string.settings_alarm_reset_title),
                        message = context.getString(R.string.settings_alarm_reset_message),
                        action = context.getString(R.string.settings_alarm_reset_action),
                        onConfirm = vm::resetAlarm,
                    )
                },
            ),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

@Composable
fun AlarmSettingsPage(
    alarm: AlarmUi,
    actions: AlarmPageActions,
    onBack: () -> Unit = {},
    onBackLongClick: () -> Unit = {},
) {
    SettingsPage(
        title = stringResource(R.string.settings_alarm),
        modifier = Modifier.testTag(AlarmTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            items =
                listOf(
                    toggleItem(
                        tile(RostyIcons.Speaker),
                        stringResource(R.string.settings_alarm_sound),
                        alarm.sound,
                        AlarmTags.Sound,
                        onToggle = actions.toggleSound,
                    ),
                    toggleItem(
                        tile(RostyIcons.Haptics),
                        stringResource(R.string.settings_alarm_vibration),
                        alarm.vibrate,
                        AlarmTags.Vibrate,
                        onToggle = actions.toggleVibrate,
                    ),
                    IosSettingsItem(
                        icon = tile(RostyIcons.Bell),
                        title = {
                            IosText(
                                stringResource(R.string.settings_alarm_tone),
                                Modifier.testTag(AlarmTags.Picker),
                            )
                        },
                        trailingContent = {
                            ValueChevron(
                                alarm.soundTitle?.ifBlank { null } ?: stringResource(R.string.settings_sound_default),
                            )
                        },
                        trailingShrinks = true,
                        enabled = alarm.sound,
                        onClick = actions.pickSound,
                    ),
                    IosSettingsItem(
                        icon = tile(RostyIcons.Clock),
                        title = {
                            IosText(
                                stringResource(R.string.settings_alarm_snooze),
                                Modifier.testTag(AlarmTags.Snooze),
                            )
                        },
                        trailingContent = {
                            Value(pluralStringResource(R.plurals.settings_minutes, alarm.snoozeMin, alarm.snoozeMin))
                        },
                        trailingShrinks = true,
                        onClick = actions.nextSnooze,
                    ),
                ),
        )
        Footnote(stringResource(R.string.settings_alarm_footer))
        if (alarm.sound && alarm.volumeChoices.isNotEmpty()) {
            IosSettingsGroup(
                title = stringResource(R.string.settings_alarm_volume),
                items =
                    alarm.volumeChoices.map { pct ->
                        choiceItem(
                            stringResource(R.string.settings_percent, pct),
                            pct == alarm.volumePct,
                            AlarmTags.volume(pct),
                        ) { actions.setVolume(pct) }
                    } +
                        IosSettingsItem(
                            icon = tile(RostyIcons.Speaker),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_test_volume),
                                    Modifier.testTag(AlarmTags.TestVolume),
                                )
                            },
                            description = { IosText(stringResource(R.string.settings_test_volume_desc)) },
                            trailingContent = { NoChevron() },
                            onClick = actions.testVolume,
                        ),
            )
            Footnote(stringResource(R.string.settings_alarm_volume_footer))
        }
        if (alarm.vibrate && alarm.vibrationChoices.isNotEmpty()) {
            IosSettingsGroup(
                title = stringResource(R.string.settings_alarm_pattern),
                items =
                    alarm.vibrationChoices.map { choice ->
                        choiceItem(
                            stringResource(patternName(choice.id)),
                            choice.id == alarm.vibration,
                            AlarmTags.pattern(choice.id),
                        ) { actions.setVibration(choice) }
                    },
            )
            Footnote(stringResource(R.string.settings_alarm_pattern_footer))
        }
        IosSettingsGroup(
            items =
                buildList {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.Bell),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_test_alarm),
                                    Modifier.testTag(AlarmTags.TestAlarm),
                                )
                            },
                            description = { IosText(stringResource(R.string.settings_test_alarm_desc)) },
                            trailingContent = { NoChevron() },
                            onClick = actions.testAlarm,
                        ),
                    )
                    if (!alarm.isDefault) {
                        add(
                            IosSettingsItem(
                                icon = tile(RostyIcons.Rotate),
                                title = {
                                    IosText(
                                        stringResource(R.string.settings_alarm_reset),
                                        Modifier.testTag(AlarmTags.Reset),
                                        color = IosTheme.colors.systemRed,
                                    )
                                },
                                trailingContent = { NoChevron() },
                                onClick = actions.reset,
                            ),
                        )
                    }
                },
        )
    }
}

/** The name of a vibration pattern in today's language. */
@StringRes
internal fun patternName(id: VibrationPattern): Int =
    when (id) {
        VibrationPattern.Standard -> R.string.settings_pattern_standard
        VibrationPattern.Pulse -> R.string.settings_pattern_pulse
        VibrationPattern.Long -> R.string.settings_pattern_long
        VibrationPattern.Heartbeat -> R.string.settings_pattern_heartbeat
    }
