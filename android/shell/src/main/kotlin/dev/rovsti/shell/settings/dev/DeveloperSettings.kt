package dev.rovsti.shell.settings.dev

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.rovsti.core.log.DevMode
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.component.IosToggle
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.showToast
import dev.rovsti.shell.R
import dev.rovsti.shell.settings.IosSettingsGroup
import dev.rovsti.shell.settings.IosSettingsItem
import dev.rovsti.shell.settings.tilePainter

/**
 * The developer mode of this app, provided by `RovstiShellActivity` from `ShellContainer.devLog`. Null in previews,
 * screenshots and tests that do not provide it: the Version row then cannot be held and the Developer group is hidden.
 */
val LocalDevMode = staticCompositionLocalOf<DevMode?> { null }

/** Test tags of the developer-mode UI. */
object DevTags {
    const val Version = "dev-version-row"
    const val Group = "dev-group"
    const val Logs = "dev-logs-row"
    const val Switch = "dev-mode-switch"
}

/**
 * What holding the Version row for [DEV_HOLD_MS] does: switches [devMode], plays the confirm haptic (switching on) or
 * the toggle-off one, and says so in a toast.
 */
@Composable
fun rememberDevModeToggle(devMode: DevMode): () -> Unit {
    val haptics by rememberUpdatedState(LocalRovstiHaptics.current)
    val overlay by rememberUpdatedState(LocalOverlayOrNull.current)
    val on = stringResource(R.string.dev_mode_on)
    val off = stringResource(R.string.dev_mode_off)
    return remember(devMode, on, off) {
        {
            val enabled = devMode.toggle()
            if (enabled) haptics.confirm() else haptics.toggle(false)
            overlay?.showToast(if (enabled) on else off)
        }
    }
}

/**
 * The "Developer" group of the settings root, shown only while developer mode is on: Logs, and the switch that turns
 * developer mode off (which deletes the log files).
 */
@Composable
fun DeveloperSettingsGroup(
    devMode: DevMode,
    onOpenLogs: () -> Unit,
) {
    val enabled by devMode.enabled.collectAsState()
    if (!enabled) return
    val haptics = LocalRovstiHaptics.current
    Box(Modifier.testTag(DevTags.Group)) {
        IosSettingsGroup(
            title = stringResource(R.string.dev_group),
            items =
                listOf(
                    IosSettingsItem(
                        icon = tilePainter(RovstiIcons.Document),
                        title = { IosText(stringResource(R.string.dev_logs), Modifier.testTag(DevTags.Logs)) },
                        description = { IosText(stringResource(R.string.dev_logs_desc)) },
                        onClick = onOpenLogs,
                    ),
                    IosSettingsItem(
                        icon = tilePainter(RovstiIcons.Gear),
                        title = {
                            Box(
                                Modifier.testTag(DevTags.Switch).semantics {
                                    role = Role.Switch
                                    selected = true
                                },
                            ) { IosText(stringResource(R.string.dev_mode)) }
                        },
                        trailingContent = { IosToggle(true, onCheckedChange = null, exposeSemantics = false) },
                        onClick = {
                            haptics.toggle(false)
                            devMode.set(false)
                        },
                    ),
                ),
        )
    }
}
