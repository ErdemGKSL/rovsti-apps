package dev.rosty.shell.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.rosty.core.model.phone.HapticsLevel
import dev.rosty.design.haptics.HapticEvent
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.haptics.RostyHaptics
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.shell.R

private val RostyIconSlot = dev.rosty.design.icons.DefaultIconSize

/** Names the radio row of one level; the tests click it. */
fun hapticsRowTag(level: HapticsLevel): String = "settings-haptics-${level.name.lowercase()}"

/** The tap the preview plays, and the one a level change plays: `Confirm` is the clearest of the vocabulary. */
val HapticsPreviewEvent: HapticEvent = HapticEvent.Confirm

/**
 * Off, Light or Normal as a radio list, with a "Try it" row. Choosing a level plays the preview at once, so the person
 * feels what they chose. [preview] defaults to the ambient [RostyHaptics]; the app container sets its mode from the new
 * level before the page calls it.
 */
@Composable
fun HapticsPage(
    selected: HapticsLevel,
    onSelect: (HapticsLevel) -> Unit,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    haptics: RostyHaptics = LocalRostyHaptics.current,
    preview: () -> Unit = { haptics.play(HapticsPreviewEvent) },
) {
    SettingsPage(
        title = stringResource(R.string.settings_haptics),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            title = stringResource(R.string.settings_haptics_level_header),
            items =
                HapticsLevel.entries.map { level ->
                    val isSelected = level == selected
                    IosSettingsItem(
                        title = {
                            Box(
                                Modifier.testTag(hapticsRowTag(level)).semantics {
                                    role = Role.RadioButton
                                    this.selected = isSelected
                                },
                            ) {
                                IosText(levelName(level))
                            }
                        },
                        description = { IosText(levelDescription(level)) },
                        // A radio row never shows a disclosure chevron: the slot holds the tick or stays empty.
                        trailingContent =
                            if (isSelected) {
                                { RostyIcon(RostyIcons.Checkmark, null, tint = IosTheme.tint) }
                            } else {
                                { Spacer(Modifier.size(RostyIconSlot)) }
                            },
                        onClick = {
                            if (!isSelected) {
                                onSelect(level)
                                if (level != HapticsLevel.Off) preview()
                            }
                        },
                    )
                },
        )
        GroupGap()
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        icon = tilePainter(RostyIcons.Haptics),
                        title = { IosText(stringResource(R.string.settings_haptics_try)) },
                        description = { IosText(stringResource(R.string.settings_haptics_try_desc)) },
                        enabled = selected != HapticsLevel.Off,
                        // An action, not a page: no chevron.
                        trailingContent = { Spacer(Modifier.size(RostyIconSlot)) },
                        onClick = preview,
                    ),
                ),
        )
        SettingsFooter(stringResource(R.string.settings_haptics_footer))
    }
}

@Composable
internal fun levelName(level: HapticsLevel): String =
    stringResource(
        when (level) {
            HapticsLevel.Off -> R.string.settings_haptics_off
            HapticsLevel.Light -> R.string.settings_haptics_light
            HapticsLevel.Normal -> R.string.settings_haptics_normal
        },
    )

@Composable
private fun levelDescription(level: HapticsLevel): String =
    stringResource(
        when (level) {
            HapticsLevel.Off -> R.string.settings_haptics_off_desc
            HapticsLevel.Light -> R.string.settings_haptics_light_desc
            HapticsLevel.Normal -> R.string.settings_haptics_normal_desc
        },
    )

@Composable
internal fun appearanceName(value: dev.rosty.core.model.stops.Appearance): String =
    stringResource(
        when (value) {
            dev.rosty.core.model.stops.Appearance.Auto -> R.string.settings_appearance_summary_auto
            dev.rosty.core.model.stops.Appearance.Light -> R.string.settings_appearance_summary_light
            dev.rosty.core.model.stops.Appearance.Dark -> R.string.settings_appearance_summary_dark
        },
    )
