package dev.rovsti.shell.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.platform.system.permissions.NextAction
import dev.rovsti.platform.system.permissions.PermissionController
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.system.permissions.SettingsKind
import dev.rovsti.platform.system.permissions.SpecialAccess
import dev.rovsti.platform.system.permissions.nextAction
import dev.rovsti.platform.system.permissions.startFirst
import dev.rovsti.shell.R

/** Test tag of the button of a row: `permission-action-<group id>`. */
fun permissionActionTag(group: PermissionGroup): String = "permission-action-${group.id}"

/**
 * The row of one group in a setup or settings section, wired: its button asks (the system dialog, through the
 * controller's contract) while Android still shows the dialog, and opens the app's page in Settings when only the user
 * can change it. Put it in an `InsetGroupedSection`; it draws no card of its own.
 */
@Composable
fun PermissionRow(
    group: PermissionGroup,
    modifier: Modifier = Modifier,
    controller: PermissionController = LocalPermissionController.current,
    special: SpecialAccess = LocalSpecialAccess.current,
) {
    val state by controller.state(group).collectAsState()
    val launcher = rememberLauncherForActivityResult(remember(controller, group) { controller.contract(group) }) {}
    val context = LocalContext.current
    PermissionRow(
        title = stringResource(group.titleRes()),
        state = state,
        icon = group.icon(),
        modifier = modifier,
        actionTag = permissionActionTag(group),
        onAction = {
            when (nextAction(state)) {
                NextAction.Ask -> launcher.launch(Unit)
                NextAction.OpenSettings -> context.startFirst(special.settingsChain(SettingsKind.AppDetails))
                NextAction.None -> Unit
            }
        },
    )
}

/**
 * The stateless row: [title], what [state] means in words, and one button from `nextAction(state)`: "Allow" or "Open
 * Settings" (never both, none when granted). The button is one line; the title column gives way to it.
 */
@Composable
fun PermissionRow(
    title: String,
    state: PermissionState,
    icon: ImageVector,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionTag: String = "permission-action",
) {
    val label =
        when (nextAction(state)) {
            NextAction.Ask -> stringResource(R.string.permission_allow)
            NextAction.OpenSettings -> stringResource(R.string.permission_open_settings)
            NextAction.None -> null
        }
    ListRow(
        title = title,
        secondary = statusText(state),
        secondaryMaxLines = 2,
        modifier = modifier,
        leading = { IconTile(icon) },
        trailing =
            label?.let { text ->
                { TextButton(text = text, onClick = onAction, modifier = Modifier.testTag(actionTag)) }
            },
    )
}

/** The 32 dp rounded accent tile with a white glyph that leads settings rows. */
@Composable
internal fun IconTile(icon: ImageVector) {
    val colors = RovstiTheme.colors
    Box(
        Modifier
            .size(
                PermissionMetrics.rowTile,
            ).clip(RoundedCornerShape(PermissionMetrics.rowTileCorner))
            .background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(icon, null, tint = colors.onAccent, size = PermissionMetrics.rowGlyph)
    }
}
