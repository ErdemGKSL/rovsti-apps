package dev.rosty.phone.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.rosty.core.model.phone.ItemState
import dev.rosty.core.model.phone.SetupItem
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.phone.DONE_CARD_ALPHA
import dev.rosty.phone.PhoneMetrics
import dev.rosty.phone.R
import dev.rosty.phone.STACK_FONT_SCALE
import dev.rosty.phone.findComponentActivity
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.PermissionController
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.SettingsKind
import dev.rosty.platform.system.permissions.SpecialAccess

/** Test tags of the Setup sheet. */
object SetupTags {
    const val Sheet = "setup-sheet"
    const val Progress = "setup-progress"
    const val ExactAlarmRow = "setup-row-exact-alarm"
    const val ExactAlarmButton = "setup-button-exact-alarm"

    fun row(item: SetupItem) = "setup-row-${item.name}"

    fun button(
        item: SetupItem,
        act: SetupAct,
    ) = "setup-button-${item.name}-${act.name}"
}

/**
 * The Setup sheet (E5.1): a header with the title and progress, one card per row of the checklist, and the line that
 * says why nothing opened. It has no state of its own: [SetupSheetContent] feeds it and carries the effects out.
 */
@Composable
fun SetupSheetScreen(
    state: SetupUiState,
    onAction: (SetupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = Spacing.l)
            .padding(bottom = Spacing.l)
            .testTag(SetupTags.Sheet),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        SetupHeader(state)
        for (row in state.rows) {
            SetupRowCard(row, onAction)
        }
        if (state.exactAlarmMissing) ExactAlarmCard(onAction)
        state.note?.let {
            RostyText(
                stringResource(it.text()),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
    }
}

private fun SetupNote.text(): Int =
    when (this) {
        SetupNote.CouldNotOpen -> R.string.setup_note_could_not_open
    }

@Composable
private fun SetupHeader(state: SetupUiState) {
    val colors = RostyTheme.colors
    val tint = if (state.complete) colors.success else colors.accent
    Column(
        Modifier.padding(horizontal = Spacing.xs).padding(bottom = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(Modifier.weight(1f)) {
                RostyText(
                    when {
                        state.complete -> stringResource(R.string.setup_all_set)
                        else -> state.changedTitle ?: stringResource(R.string.setup_title)
                    },
                    style = RostyTheme.type.title2,
                    modifier = Modifier.semantics { heading() },
                )
                RostyText(
                    stringResource(R.string.setup_subtitle),
                    style = RostyTheme.type.footnote,
                    color = colors.labelSecondary,
                )
            }
            RostyText(state.progressLabel, style = RostyTheme.type.subheadline, color = tint)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(PhoneMetrics.Bar)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.fillSecondary)
                .testTag(SetupTags.Progress),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.fraction.coerceIn(0f, 1f))
                    .height(PhoneMetrics.Bar)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(tint),
            )
        }
    }
}

@Composable
private fun SetupRowCard(
    row: SetupRowUi,
    onAction: (SetupAction) -> Unit,
) {
    val colors = RostyTheme.colors
    val done = row.state == ItemState.Done
    val (icon, tint) = row.state.status()
    val word =
        stringResource(
            when (row.state) {
                ItemState.Done -> R.string.setup_row_done
                ItemState.NotDone -> R.string.setup_row_not_done
                ItemState.Unverifiable -> R.string.setup_row_confirm
            },
        )
    val label = "${row.title}, $word"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoneMetrics.CardRadius))
            .background(if (done) colors.groupedSurface.copy(alpha = DONE_CARD_ALPHA) else colors.groupedSurface)
            .padding(PhoneMetrics.CardPad)
            .semantics { contentDescription = label }
            .testTag(SetupTags.row(row.item)),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            RostyIcon(
                icon,
                null,
                tint = tint,
                size = PhoneMetrics.StatusIcon,
                modifier = Modifier.size(PhoneMetrics.StatusIcon),
            )
            Column(Modifier.weight(1f)) {
                RostyText(row.title, style = RostyTheme.type.headline)
                RostyText(row.reason, style = RostyTheme.type.footnote, color = colors.labelSecondary)
            }
        }
        row.steps?.let { steps ->
            RostyText(
                stepsText(steps, row.tail),
                style = RostyTheme.type.caption1,
                color = colors.labelSecondary,
            )
        }
        if (row.buttons.isNotEmpty()) {
            val stacked = row.buttons.size > 2 || LocalDensity.current.fontScale > STACK_FONT_SCALE
            val button: @Composable (SetupButtonUi, Modifier) -> Unit = { button, modifier ->
                CapsuleButton(
                    text = stringResource(button.act.label()),
                    onClick = { onAction(SetupAction.Press(row.item, button)) },
                    style = if (button.primary) CapsuleStyle.Filled else CapsuleStyle.Glass,
                    fillWidth = stacked,
                    modifier = modifier.testTag(SetupTags.button(row.item, button.act)),
                )
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    for (b in row.buttons) button(b, Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    for (b in row.buttons) button(b, Modifier)
                }
            }
        }
    }
}

/** The extra row of E5.12: reminders are inexact until "Alarms and reminders" is allowed. It is not a checklist item. */
@Composable
private fun ExactAlarmCard(onAction: (SetupAction) -> Unit) {
    val colors = RostyTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoneMetrics.CardRadius))
            .background(colors.groupedSurface)
            .padding(PhoneMetrics.CardPad)
            .testTag(SetupTags.ExactAlarmRow),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            RostyIcon(
                RostyIcons.Circle,
                null,
                tint = IosTheme.colors.systemOrange,
                size = PhoneMetrics.StatusIcon,
                modifier = Modifier.size(PhoneMetrics.StatusIcon),
            )
            Column(Modifier.weight(1f)) {
                RostyText(stringResource(R.string.settings_exact_alarm_title), style = RostyTheme.type.headline)
                RostyText(
                    stringResource(R.string.settings_exact_alarm_reason),
                    style = RostyTheme.type.footnote,
                    color = colors.labelSecondary,
                )
            }
        }
        CapsuleButton(
            text = stringResource(R.string.settings_exact_alarm_action),
            onClick = { onAction(SetupAction.OpenExactAlarms) },
            style = CapsuleStyle.Filled,
            modifier = Modifier.testTag(SetupTags.ExactAlarmButton),
        )
    }
}

@Composable
private fun ItemState.status(): Pair<ImageVector, Color> =
    when (this) {
        ItemState.Done -> RostyIcons.CircleCheck to RostyTheme.colors.success
        ItemState.NotDone -> RostyIcons.Circle to RostyTheme.colors.labelTertiary
        ItemState.Unverifiable -> RostyIcons.Circle to IosTheme.colors.systemOrange
    }

@Composable
private fun stepsText(
    steps: SetupSteps,
    tail: StepsTail,
): String {
    val base =
        stringResource(
            when (steps) {
                SetupSteps.Restricted -> R.string.setup_steps_restricted
                SetupSteps.LockScreen -> R.string.setup_steps_lock_screen
                SetupSteps.Popups -> R.string.setup_steps_popups
                SetupSteps.Battery -> R.string.setup_steps_battery
                SetupSteps.Autostart -> R.string.setup_steps_autostart
            },
        )
    return when (tail) {
        StepsTail.None -> base
        StepsTail.FellBack -> base + stringResource(R.string.setup_tail_fell_back)
        StepsTail.OpenFirst -> base + stringResource(R.string.setup_tail_open_first)
    }
}

internal fun SetupAct.label(): Int =
    when (this) {
        SetupAct.AppInfo -> R.string.setup_act_app_info

        SetupAct.RequestRole -> R.string.setup_act_role

        SetupAct.DefaultApps -> R.string.setup_act_default_apps

        SetupAct.Allow -> R.string.setup_act_allow

        SetupAct.OpenSettings, SetupAct.FullScreen, SetupAct.XiaomiAutostart, SetupAct.XiaomiBattery,
        SetupAct.XiaomiOther,
        -> R.string.setup_act_open_settings

        SetupAct.BatteryDialog -> R.string.setup_act_quick_allow

        SetupAct.Confirm -> R.string.setup_act_confirm

        SetupAct.Unconfirm -> R.string.setup_act_unconfirm
    }

/**
 * [SetupSheetScreen] over its view model: carries out what the view model asks (the role dialog, the permission
 * dialogs, the settings pages) and re-reads Android when the screen resumes, which is where a change made in Settings
 * shows up (`setup.reopened` then names the row that was lost).
 */
@Composable
fun SetupSheetContent(
    viewModel: SetupSheetViewModel,
    special: SpecialAccess,
    permissions: PermissionController,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    SetupEffectsRunner(viewModel, special, permissions)
    SetupSheetScreen(state, viewModel::onAction, modifier)
}

/**
 * Carries out what the setup view model asks (the role dialog, the permission dialogs, the settings pages). The sheet and
 * the Setup section of Settings both host it, so a row works wherever it is pressed.
 */
@Composable
fun SetupEffectsRunner(
    viewModel: SetupSheetViewModel,
    special: SpecialAccess,
    permissions: PermissionController,
) {
    val context = LocalContext.current
    val role =
        rememberLauncherForActivityResult(
            remember(special) { special.requestRoleContract(AppPermissions.ROLE_DIALER) },
        ) {
            viewModel.onAction(SetupAction.RoleAnswered(it))
        }
    val phone =
        rememberLauncherForActivityResult(remember(permissions) { permissions.contract(PermissionGroup.PHONE) }) {}
    val contacts =
        rememberLauncherForActivityResult(remember(permissions) { permissions.contract(PermissionGroup.CONTACTS) }) {}
    val notifications =
        rememberLauncherForActivityResult(
            remember(permissions) { permissions.contract(PermissionGroup.NOTIFICATIONS) },
        ) {}
    val latest by rememberUpdatedState(viewModel)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SetupEffect.RequestRole -> {
                    role.launch(Unit)
                }

                is SetupEffect.RequestPermission -> {
                    when (effect.group) {
                        PermissionGroup.PHONE -> phone.launch(Unit)
                        PermissionGroup.CONTACTS -> contacts.launch(Unit)
                        PermissionGroup.NOTIFICATIONS -> notifications.launch(Unit)
                        else -> Unit
                    }
                }

                is SetupEffect.OpenSettings -> {
                    val chain = special.settingsChain(effect.kind)
                    val index = context.startChain(chain)
                    // Only App info is left in the chain: the dedicated page of a Xiaomi row did not open.
                    val fellBack = effect.kind.isXiaomi() && chain.size > 1 && index == chain.lastIndex
                    latest.onAction(SetupAction.SettingsOpened(effect.item, index >= 0, fellBack))
                }

                SetupEffect.BatteryDialog -> {
                    context.startChain(special.settingsChain(SettingsKind.Battery))
                }

                SetupEffect.OpenExactAlarms -> {
                    context.startChain(special.settingsChain(SettingsKind.ExactAlarms))
                }
            }
        }
    }
}

private fun SettingsKind.isXiaomi() =
    this == SettingsKind.XiaomiAutostart || this == SettingsKind.XiaomiBattery || this == SettingsKind.XiaomiPermissions

/** Starts the first intent of [chain] some activity takes and returns its index, or -1 when none did. */
private fun Context.startChain(chain: List<Intent>): Int {
    for ((index, intent) in chain.withIndex()) {
        try {
            startActivity(if (this is Activity) intent else Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return index
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return -1
}

/** Calls [onResume] on every `ON_RESUME` of the current lifecycle owner (the activity), and once at the start. */
@Composable
fun OnResume(onResume: () -> Unit) {
    val owner = LocalContext.current.findComponentActivity()
    val latest by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
