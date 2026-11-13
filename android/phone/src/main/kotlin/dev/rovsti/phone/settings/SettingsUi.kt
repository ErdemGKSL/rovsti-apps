package dev.rovsti.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosMetrics
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

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

/**
 * One row of an inset-grouped card whose rows are separate lazy items (a reorderable list cannot sit in one item): the
 * card colour, rounded ends and a separator above every row but the first.
 */
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
            .background(RovstiTheme.colors.groupedSurface),
    ) {
        if (index > 0) RowSeparator()
        content()
    }
}

/** The grey explanatory text under a group. */
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

/** A heading above a group, in the settings style. */
@Composable
internal fun GroupHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    IosText(
        text,
        modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
        color = IosTheme.colors.secondaryLabel,
        style = IosTheme.typography.subheadline,
    )
}

/** A row whose whole title is the action, in the accent colour or, when [destructive], red. */
@Composable
internal fun ActionRow(
    title: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = RovstiTheme.colors
    ListRow(
        title = title,
        modifier = modifier.testTag(tag),
        titleColor = if (destructive) colors.destructive else colors.accent,
        onClick = if (enabled) onClick else null,
    )
}

/** Names of the Settings sub-pages' test tags. */
object SettingsTags {
    fun page(name: String) = "settings-page-$name"

    const val Footer = "settings-footer"
}

/** The page gutter of a [dev.rovsti.shell.settings.SettingsPage] whose body is made of sections that pad themselves. */
internal val NoGutter = Spacing.gutter - Spacing.gutter
