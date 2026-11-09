package dev.rovsti.shell.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.design.controls.SegmentedControl
import dev.rovsti.shell.R
import kotlinx.collections.immutable.persistentListOf

/** Test tag of the segmented control on the Appearance page. */
const val AppearanceSegmentsTag = "settings-appearance-segments"

/** Auto, Light or Dark in one segmented control. The theme follows the preference at once (see [rememberDarkTheme]). */
@Composable
fun AppearancePage(
    selected: Appearance,
    onSelect: (Appearance) -> Unit,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val labels =
        persistentListOf(
            stringResource(R.string.settings_appearance_auto),
            stringResource(R.string.settings_appearance_light),
            stringResource(R.string.settings_appearance_dark),
        )
    SettingsPage(
        title = stringResource(R.string.settings_appearance),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        title = {
                            SegmentedControl(
                                items = labels,
                                selected = Appearance.entries.indexOf(selected),
                                onSelect = { onSelect(Appearance.entries[it]) },
                                modifier = Modifier.fillMaxWidth().testTag(AppearanceSegmentsTag),
                            )
                        },
                    ),
                ),
        )
        SettingsFooter(stringResource(R.string.settings_appearance_footer))
    }
}
