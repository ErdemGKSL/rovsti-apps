package dev.rosty.messages.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.rosty.design.icons.DefaultIconSize
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.component.IosToggle
import dev.rosty.design.ios.foundation.IosMetrics
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsFooter

// The small building blocks of the Messages settings pages (E6.12), in the style of Phone's settings pages.

/** The corners of row [index] of [count] in an inset-grouped card made of separate lazy items: only the ends round. */
internal fun groupedShape(
    index: Int,
    count: Int,
): RoundedCornerShape {
    val round = CornerSize(IosMetrics.GroupedCornerRadius)
    val top = if (index == 0) round else ZeroCornerSize
    val bottom = if (index == count - 1) round else ZeroCornerSize
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** One row of an inset-grouped card whose rows are separate lazy items (a reorderable list cannot sit in one item). */
@Composable
internal fun GroupedCell(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(groupedShape(index, count))
            .background(RostyTheme.colors.groupedSurface),
    ) {
        if (index > 0) RowSeparator()
        content()
    }
}

/** The grey explanatory text under a group of a lazy page. */
@Composable
internal fun GroupFooter(
    text: String,
    modifier: Modifier = Modifier,
) {
    IosText(
        text,
        modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        color = IosTheme.colors.secondaryLabel,
        style = IosTheme.typography.footnote,
    )
}

/** A row whose whole title is the action, in the accent colour. */
@Composable
internal fun ActionRow(
    title: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ListRow(
        title = title,
        modifier = modifier.testTag(tag),
        titleColor = if (enabled) RostyTheme.colors.accent else RostyTheme.colors.labelTertiary,
        onClick = if (enabled) onClick else null,
    )
}

/** A footer under an [dev.rosty.shell.settings.IosSettingsGroup], with the gap to the next group. */
@Composable
internal fun Footnote(text: String) {
    SettingsFooter(text)
    Spacer(Modifier.height(Spacing.l))
}

/** A switch row: the whole row toggles, the switch only shows the state. */
@Composable
internal fun switchItem(
    title: String,
    checked: Boolean,
    tag: String,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) = IosSettingsItem(
    icon = icon?.let { rememberVectorPainter(it) },
    title = {
        Box(
            Modifier.testTag(tag).semantics {
                role = Role.Switch
                selected = checked
            },
        ) { IosText(title) }
    },
    description = description?.let { { IosText(it) } },
    trailingContent = { IosToggle(checked, onCheckedChange = null, enabled = enabled, exposeSemantics = false) },
    enabled = enabled,
    onClick = onToggle,
)

/** A radio row: a tick on the chosen one, never a chevron. */
internal fun radioItem(
    title: String,
    chosen: Boolean,
    tag: String,
    onClick: () -> Unit,
) = IosSettingsItem(
    title = {
        Box(
            Modifier.testTag(tag).semantics {
                role = Role.RadioButton
                selected = chosen
            },
        ) { IosText(title) }
    },
    trailingContent =
        if (chosen) {
            { RostyIcon(RostyIcons.Checkmark, null, tint = IosTheme.tint) }
        } else {
            { Spacer(Modifier.size(DefaultIconSize)) }
        },
    onClick = onClick,
)
