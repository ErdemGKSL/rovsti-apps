package dev.rosty.shell.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.rosty.design.ios.component.IosText
import dev.rosty.platform.system.permissions.NextAction
import dev.rosty.platform.system.permissions.PermissionController
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.SettingsKind
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.system.permissions.SpecialKind
import dev.rosty.platform.system.permissions.nextAction
import dev.rosty.platform.system.permissions.startFirst
import dev.rosty.shell.R
import dev.rosty.shell.settings.IosSettingsItem

/**
 * [PermissionRow] as a row of an `IosSettingsGroup`, so a settings page lists its access rows in the same cards as its
 * other groups: the group's title, what its state means, and the next action's word on the right ("Allow", "Open
 * Settings"; none when granted). A tap does that action.
 */
@Composable
fun permissionSettingsItem(
    group: PermissionGroup,
    controller: PermissionController = LocalPermissionController.current,
    special: SpecialAccess = LocalSpecialAccess.current,
): IosSettingsItem {
    val state by controller.state(group).collectAsState()
    val launcher = rememberLauncherForActivityResult(remember(controller, group) { controller.contract(group) }) {}
    val context = LocalContext.current
    val next = nextAction(state)
    val label =
        when (next) {
            NextAction.Ask -> stringResource(R.string.permission_allow)
            NextAction.OpenSettings -> stringResource(R.string.permission_open_settings)
            NextAction.None -> null
        }
    val status = statusText(state)
    return IosSettingsItem(
        icon = rememberVectorPainter(group.icon()),
        title = { IosText(stringResource(group.titleRes())) },
        description = { IosText(status) },
        trailingContent = label?.let { { IosText(it, Modifier.testTag(permissionActionTag(group))) } },
        onClick =
            when (next) {
                NextAction.Ask -> {
                    { launcher.launch(Unit) }
                }

                NextAction.OpenSettings -> {
                    { context.startFirst(special.settingsChain(SettingsKind.AppDetails)) }
                }

                NextAction.None -> {
                    null
                }
            },
    )
}

/** [SpecialAccessRow] as a row of an `IosSettingsGroup`; re-read on every resume. */
@Composable
fun specialAccessSettingsItem(
    kind: SpecialKind,
    special: SpecialAccess = LocalSpecialAccess.current,
): IosSettingsItem {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    val owner = context.findActivity() as? LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val on = remember(resumes, special, kind) { special.isOn(kind) }
    val open = stringResource(R.string.permission_open_settings)
    val status = stringResource(kind.statusRes(on))
    return IosSettingsItem(
        icon = rememberVectorPainter(kind.icon()),
        title = { IosText(stringResource(kind.titleRes())) },
        description = { IosText(status) },
        trailingContent = if (on == true) null else ({ IosText(open, Modifier.testTag(specialRowTag(kind))) }),
        onClick = if (on == true) null else ({ context.startFirst(special.settingsChain(kind.settings())) }),
    )
}
