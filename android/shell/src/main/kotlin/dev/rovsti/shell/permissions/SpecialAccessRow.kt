package dev.rovsti.shell.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.ListRow
import dev.rovsti.platform.system.permissions.SettingsKind
import dev.rovsti.platform.system.permissions.SpecialAccess
import dev.rovsti.platform.system.permissions.SpecialKind
import dev.rovsti.platform.system.permissions.startFirst
import dev.rovsti.shell.R

/** Test tag of a special-access row: `special-<kind>`. */
fun specialRowTag(kind: SpecialKind): String = "special-${kind.name}"

/** Whether [kind] is on, or null when Android cannot tell (the Xiaomi autostart page). */
fun SpecialAccess.isOn(kind: SpecialKind): Boolean? =
    when (kind) {
        SpecialKind.ExactAlarms -> exactAlarms()
        SpecialKind.FullScreenIntent -> fullScreenIntent()
        SpecialKind.BatteryOptimisation -> ignoresBatteryOptimisation()
        SpecialKind.XiaomiBackground -> null
    }

internal fun SpecialKind.settings(): SettingsKind =
    when (this) {
        SpecialKind.ExactAlarms -> SettingsKind.ExactAlarms
        SpecialKind.FullScreenIntent -> SettingsKind.FullScreenIntent
        SpecialKind.BatteryOptimisation -> SettingsKind.Battery
        SpecialKind.XiaomiBackground -> SettingsKind.XiaomiAutostart
    }

private fun SpecialKind.icon(): ImageVector =
    when (this) {
        SpecialKind.ExactAlarms -> RovstiIcons.Message
        SpecialKind.FullScreenIntent -> RovstiIcons.Phone
        SpecialKind.BatteryOptimisation, SpecialKind.XiaomiBackground -> RovstiIcons.Gear
    }

/**
 * A row for access that only Settings grants (exact alarms, full-screen calls, battery, Xiaomi autostart). It re-reads
 * [special] each time the screen resumes, so coming back from Settings updates it. Off shows "Open Settings", which
 * starts the first page of the chain that some activity accepts (the app's own page is the last one).
 */
@Composable
fun SpecialAccessRow(
    kind: SpecialKind,
    modifier: Modifier = Modifier,
    special: SpecialAccess = LocalSpecialAccess.current,
) {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    val owner = context.findActivity() as? LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val on = remember(resumes, special, kind) { special.isOn(kind) }
    ListRow(
        title = stringResource(kind.titleRes()),
        secondary = stringResource(kind.statusRes(on)),
        secondaryMaxLines = 2,
        modifier = modifier,
        leading = { IconTile(kind.icon()) },
        trailing =
            if (on == true) {
                null
            } else {
                {
                    TextButton(
                        text = stringResource(R.string.permission_open_settings),
                        onClick = { context.startFirst(special.settingsChain(kind.settings())) },
                        modifier = Modifier.testTag(specialRowTag(kind)),
                    )
                }
            },
    )
}
