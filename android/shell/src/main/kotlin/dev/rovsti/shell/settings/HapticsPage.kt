package dev.rovsti.shell.settings

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
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.shell.R

private val RovstiIconSlot = dev.rovsti.design.icons.DefaultIconSize

/** Names the radio row of one level; the tests click it. */
fun hapticsRowTag(level: HapticsLevel): String = "settings-haptics-${level.name.lowercase()}"

/** The tap the preview plays, and the one a level change plays: `Confirm` is the clearest of the vocabulary. */
val HapticsPreviewEvent: HapticEvent = HapticEvent.Confirm

/**
 * Off, Light or Normal as a radio list, with a "Try it" row. Choosing a level plays the preview at once, so the person
 * feels what they chose. [preview] defaults to the ambient [RovstiHaptics]; the app container sets its mode from the new
 * level before the page calls it.
 */
@Composable
fun HapticsPage(
    selected: HapticsLevel,
    onSelect: (HapticsLevel) -> Unit,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    haptics: RovstiHaptics = LocalRovstiHaptics.current,
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
                                { RovstiIcon(RovstiIcons.Checkmark, null, tint = IosTheme.tint) }
                            } else {
                                { Spacer(Modifier.size(RovstiIconSlot)) }
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
                        icon = tilePainter(RovstiIcons.Haptics),
                        title = { IosText(stringResource(R.string.settings_haptics_try)) },
                        description = { IosText(stringResource(R.string.settings_haptics_try_desc)) },
                        enabled = selected != HapticsLevel.Off,
                        // An action, not a page: no chevron.
                        trailingContent = { Spacer(Modifier.size(RovstiIconSlot)) },
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
internal fun appearanceName(value: dev.rovsti.core.model.stops.Appearance): String =
    stringResource(
        when (value) {
            dev.rovsti.core.model.stops.Appearance.Auto -> R.string.settings_appearance_summary_auto
            dev.rovsti.core.model.stops.Appearance.Light -> R.string.settings_appearance_summary_light
            dev.rovsti.core.model.stops.Appearance.Dark -> R.string.settings_appearance_summary_dark
        },
    )
