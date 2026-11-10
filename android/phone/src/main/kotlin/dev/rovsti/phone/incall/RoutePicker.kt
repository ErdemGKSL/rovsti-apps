package dev.rovsti.phone.incall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.core.model.phone.RowKind
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.InCallMetrics
import kotlinx.collections.immutable.ImmutableList

/**
 * The audio output list inside the route popover: one row per output the core listed (and the app may use), a check on
 * the selected one. A vanished device stays as a dimmed row that cannot be picked. With no rows (the core failed, or
 * every row needed a grant the app does not have) the list is empty and nothing crashes.
 */
@Composable
internal fun RoutePickerContent(
    rows: ImmutableList<RouteRowUi>,
    strings: InCallStrings,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    Column(
        modifier.testTag(InCallTags.RouteList).width(InCallMetrics.RouteWidth).padding(vertical = Spacing.xs),
    ) {
        rows.forEach { row ->
            val label = strings.route(row)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = InCallMetrics.RouteRow)
                    .testTag(InCallTags.route(row.id))
                    .clickable(enabled = !row.unavailable, role = Role.RadioButton) { onPick(row.id) }
                    .padding(horizontal = Spacing.m, vertical = Spacing.s)
                    .semantics(mergeDescendants = true) {
                        selected = row.selected
                        contentDescription = label
                        if (row.unavailable) stateDescription = strings.routeUnavailable
                    },
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(InCallMetrics.RouteGlyph), contentAlignment = Alignment.Center) {
                    if (row.selected) {
                        RovstiIcon(RovstiIcons.Checkmark, null, tint = colors.accent, size = InCallMetrics.RouteGlyph)
                    }
                }
                Column(Modifier.weight(1f)) {
                    RovstiText(
                        label,
                        style = RovstiTheme.type.body,
                        color = if (row.unavailable) colors.labelTertiary else colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.subtitle.isNotBlank()) {
                        RovstiText(
                            row.subtitle,
                            style = RovstiTheme.type.footnote,
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                RovstiIcon(
                    glyph(row.kind),
                    null,
                    tint = if (row.unavailable) colors.labelTertiary else colors.labelPrimary,
                    size = InCallMetrics.RouteGlyph,
                )
            }
        }
    }
}

private fun glyph(kind: RowKind): ImageVector =
    when (kind) {
        RowKind.Bluetooth -> RovstiIcons.Bluetooth
        RowKind.WiredHeadset -> RovstiIcons.Headphones
        RowKind.Speaker -> RovstiIcons.Speaker
        else -> RovstiIcons.Phone
    }
