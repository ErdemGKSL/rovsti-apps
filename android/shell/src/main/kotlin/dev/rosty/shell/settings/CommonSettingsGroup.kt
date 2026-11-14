package dev.rosty.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosChevron
import dev.rosty.design.ios.component.IosText
import dev.rosty.shell.R
import dev.rosty.shell.settings.dev.DeveloperSettingsGroup
import dev.rosty.shell.settings.dev.LocalDevMode

/** The pages the common rows open; an app maps them onto its own back-stack keys. */
enum class CommonSettingsPage { Appearance, Haptics, About, Licences, Logs }

/**
 * The common rows every app's settings page ends with: Appearance and Haptics (each hidden when its preference is null),
 * then About; while developer mode is on ([LocalDevMode]) the Developer group follows. Put it inside a `SettingsPage`.
 */
@Composable
fun CommonSettingsGroup(
    settings: CommonSettings,
    onOpen: (CommonSettingsPage) -> Unit,
) {
    val appearance by settings.appearance.collectAsState()
    val haptics by settings.haptics.collectAsState()
    IosSettingsGroup(
        items =
            listOfNotNull(
                appearance?.let { value ->
                    IosSettingsItem(
                        icon = tilePainter(RostyIcons.Appearance),
                        title = { IosText(stringResource(R.string.settings_appearance)) },
                        trailingContent = { ValueWithChevron(appearanceName(value)) },
                        onClick = { onOpen(CommonSettingsPage.Appearance) },
                    )
                },
                haptics?.let { level ->
                    IosSettingsItem(
                        icon = tilePainter(RostyIcons.Haptics),
                        title = { IosText(stringResource(R.string.settings_haptics)) },
                        trailingContent = { ValueWithChevron(levelName(level)) },
                        onClick = { onOpen(CommonSettingsPage.Haptics) },
                    )
                },
                IosSettingsItem(
                    icon = tilePainter(RostyIcons.Info),
                    title = { IosText(stringResource(R.string.settings_about)) },
                    onClick = { onOpen(CommonSettingsPage.About) },
                ),
            ),
    )
    LocalDevMode.current?.let { devMode ->
        DeveloperSettingsGroup(devMode, onOpenLogs = { onOpen(CommonSettingsPage.Logs) })
    }
}

/** The value on the right of a row that also opens a page: iOS shows the value, then the chevron. */
@Composable
private fun ValueWithChevron(value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsMetrics.S8),
    ) {
        IosText(value)
        IosChevron()
    }
}
