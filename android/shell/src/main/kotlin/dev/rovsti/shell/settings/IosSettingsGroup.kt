/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/IosSettingsGroup.kt
 * Changes for Rovsti: package dev.rovsti.shell.settings and imports (our shapes, IosTheme tokens and IosText/IosChevron/IosBadge
 * from :design). The group itself is as written; the numeric dp literals are named in SettingsMetrics (ConventionTest bans raw dp in :shell).
 * Rovsti change (F35): a row with trailing content uses :design's StackingTrailingRow, which puts the action under the text at large fonts.
 * Rovsti change (people sync v2): IosSettingsItem takes an optional row modifier.
 */

package dev.rovsti.shell.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.rovsti.design.ios.component.IosBadge
import dev.rovsti.design.ios.component.IosChevron
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosMetrics
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.LocalIosContentColor
import dev.rovsti.design.ios.foundation.LocalIosTextStyle
import dev.rovsti.design.ios.foundation.iosRowPress
import dev.rovsti.design.list.StackingTrailingRow
import dev.rovsti.design.shapes.ContinuousRoundedRectangle

/**
 * An iOS inset-grouped settings section: a sentence-case header over one rounded card
 * whose rows are split by separators inset to the text column.
 *
 * @param title the section header. iOS 26 sets these in sentence case, not caps.
 */
@Composable
fun IosSettingsGroup(
    title: String? = null,
    items: List<IosSettingsItem>,
) {
    val colors = IosTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 220))
            // Inset-grouped sections are 35 pt apart on iOS: this 24 dp plus the next header's 12 dp top padding.
            .padding(bottom = SettingsMetrics.S24),
    ) {
        title?.let {
            IosText(
                text = it,
                style = IosTheme.typography.subheadline,
                color = colors.secondaryLabel,
                modifier =
                    Modifier.padding(
                        start = SettingsMetrics.S20,
                        end = SettingsMetrics.S20,
                        top = SettingsMetrics.S12,
                        bottom = SettingsMetrics.S8,
                    ),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedRectangle(IosMetrics.GroupedCornerRadius))
                    .background(colors.secondarySystemGroupedBackground),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        Modifier
                            // The separator starts at the text, not the card edge —
                            // and clears the icon column when there is one.
                            .padding(start = if (item.hasLeading) SettingsMetrics.S60 else SettingsMetrics.S16)
                            .fillMaxWidth()
                            .height(IosMetrics.SeparatorThickness)
                            .background(colors.separator),
                    )
                }
                IosSettingsItemRow(item)
            }
        }
    }
}

@Composable
private fun IosSettingsItemRow(item: IosSettingsItem) {
    val colors = IosTheme.colors
    val contentColor = if (item.enabled) colors.label else colors.tertiaryLabel
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .then(item.modifier)
            .iosRowPress(
                enabled = item.enabled,
                onClick = item.onClick?.let { click -> { click() } },
            ).padding(horizontal = SettingsMetrics.S16, vertical = SettingsMetrics.S12)
    val minHeight = maxOf(IosMetrics.ListRowHeight, SettingsMetrics.S56)

    val leading: (@Composable () -> Unit)? =
        when {
            item.leadingContent != null -> item.leadingContent
            item.icon != null -> ({ IosSettingsIconTile(item.icon, item) })
            else -> null
        }
    val textColumn: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsMetrics.S2)) {
            CompositionLocalProvider(
                LocalIosTextStyle provides IosTheme.typography.body,
                LocalIosContentColor provides contentColor,
            ) { item.title() }

            item.description?.let { description ->
                CompositionLocalProvider(
                    LocalIosTextStyle provides IosTheme.typography.footnote,
                    LocalIosContentColor provides
                        if (item.enabled) colors.secondaryLabel else colors.tertiaryLabel,
                ) { description() }
            }
        }
    }

    if (item.trailingContent != null) {
        // F35: the trailing action drops under the text at large font sizes or when it would squeeze the text.
        StackingTrailingRow(
            text = textColumn,
            trailing = {
                CompositionLocalProvider(
                    LocalIosTextStyle provides IosTheme.typography.body,
                    LocalIosContentColor provides colors.secondaryLabel,
                ) { item.trailingContent.invoke() }
            },
            modifier = rowModifier.defaultMinSize(minHeight = minHeight),
            leading = leading,
            leadingGap = SettingsMetrics.S12,
            trailingGap = SettingsMetrics.S12,
            stackGap = SettingsMetrics.S2,
        )
        return
    }

    Row(
        modifier = rowModifier.defaultMinSize(minHeight = minHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(SettingsMetrics.S12))
        }
        Box(Modifier.weight(1f)) { textColumn() }
        if (item.onClick != null) {
            // A row that goes somewhere gets a chevron; one that just toggles does not.
            Spacer(Modifier.width(SettingsMetrics.S8))
            IosChevron()
        }
    }
}

/**
 * The filled rounded-square glyph tile iOS puts at the head of a settings row — the
 * icon sits on the accent, not tinted into a faint container.
 */
@Composable
private fun IosSettingsIconTile(
    icon: Painter,
    item: IosSettingsItem,
) {
    val colors = IosTheme.colors

    Box(contentAlignment = Alignment.TopEnd) {
        if (item.tintIcon) {
            Box(
                modifier =
                    Modifier
                        .size(IosMetrics.IconTileSize)
                        .clip(item.iconShape ?: ContinuousRoundedRectangle(IosMetrics.IconTileRadius))
                        .background(
                            when {
                                !item.enabled -> colors.systemGray3
                                item.isHighlighted -> IosTheme.tint
                                else -> IosTheme.tint.copy(alpha = 0.9f)
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SettingsMetrics.S18),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
        } else {
            Image(
                painter = icon,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(IosMetrics.IconTileSize)
                        .clip(item.iconShape ?: ContinuousRoundedRectangle(IosMetrics.IconTileRadius)),
                contentScale = ContentScale.Crop,
            )
        }

        if (item.showBadge) IosBadge("", Modifier.padding(start = SettingsMetrics.S4))
    }
}

/** One row of an [IosSettingsGroup]. */
data class IosSettingsItem(
    val icon: Painter? = null,
    val leadingContent: (@Composable () -> Unit)? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val showBadge: Boolean = false,
    val isHighlighted: Boolean = false,
    val tintIcon: Boolean = true,
    val iconShape: Shape? = null,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
    /** Extra behaviour of the whole row (Rovsti: the About Version row's hold for developer mode). */
    val modifier: Modifier = Modifier,
) {
    internal val hasLeading: Boolean get() = icon != null || leadingContent != null
}
