package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.Badge
import dev.rovsti.design.controls.MessageSource
import dev.rovsti.design.controls.SourceGlyph
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.GroupAvatar
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.theme.RovstiColors
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

/**
 * Contacts-and-messages rows on the page itself: avatar slot, title, one secondary line, meta, badge. Every text starts
 * on one vertical line because the leading slot is fixed.
 */
@Composable
internal fun RowsPage() {
    var opened by remember { mutableStateOf("none") }
    var deleted by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader("Rows", "Opened: $opened. Deleted by action: $deleted")
        ListRow(
            title = "Ayşe Yılmaz",
            secondary = "See you at seven, bring the keys",
            meta = "10:24",
            leading = { Avatar("Ayşe Yılmaz") },
            trailing = { Badge(2) },
            onClick = { opened = "Ayşe" },
            actions = persistentListOf(CustomAction("Delete") { deleted++ }),
            modifier = Modifier.testTag("row-ayse"),
        )
        RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = "Weekend plans",
            secondary = "Can: I booked the cabin for Saturday",
            meta = "Yesterday",
            leading = { GroupAvatar(persistentListOf("Ali Kaya", "Can Demir")) },
            trailing = { SourceGlyph(MessageSource.WhatsApp) },
            onClick = { opened = "group" },
        )
        RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = "+90 532 111 22 33",
            secondary = "Missed call",
            meta = "Mon",
            leading = { Avatar("+90 532 111 22 33") },
            trailing = { SourceGlyph(MessageSource.Sms) },
            onClick = { opened = "number" },
        )
        RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = "A very long conversation title that has to be clipped with an ellipsis",
            secondary = "A long preview line that also stays on one single line and ends in an ellipsis too",
            meta = "Sun",
            leading = { Avatar("Zeynep Arslan") },
            onClick = { opened = "long" },
        )
        RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = "Mehmet Öz",
            leading = { Avatar("Mehmet Öz") },
            trailing = { ListRowChevron() },
            onClick = { opened = "Mehmet" },
        )
        RowSeparator(inset = TextStartWithLeading)
        ListRow(
            title = "Elif Şahin",
            secondary = "mobile",
            leading = { Avatar("Elif Şahin") },
            trailing = { ListRowChevron() },
            onClick = { opened = "Elif" },
        )
        RowSeparator(inset = TextStartWithLeading)
        ListRow(title = "Static row without a click", secondary = "No press fill, no button role", leading = {
            Avatar("Static Row")
        })
    }
}

/** An inset-grouped settings page: leading icon tiles, meta, chevrons, a destructive row and a footer. */
@Composable
internal fun GroupedSectionPage() {
    var taps by remember { mutableStateOf(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap - Spacing.s),
    ) {
        PageHeader("Grouped sections", "Radius 20, separators from the text start. Taps: $taps")
        InsetGroupedSection(
            header = "Account",
            separatorInset = TextStartWithLeading,
            modifier = Modifier.testTag("section-account"),
        ) {
            row { SettingsRow("Profile", RovstiIcons.Person, "Ayşe") { taps++ } }
            row { SettingsRow("Messages", RovstiIcons.Message, "WhatsApp, SMS") { taps++ } }
            row { SettingsRow("Calls", RovstiIcons.Phone, null) { taps++ } }
        }
        InsetGroupedSection(
            header = "Maps",
            footer = "Tiles are cached on the device. Separators stop before the last row.",
            separatorInset = TextStartWithLeading,
        ) {
            row { SettingsRow("Tile server", RovstiIcons.Map, "versatiles.org") { taps++ } }
            row { SettingsRow("General", RovstiIcons.Gear, null) { taps++ } }
        }
        InsetGroupedSection(header = "Plain rows") {
            row {
                ListRow(
                    title = "Connect key",
                    meta = "Linked",
                    trailing = { ListRowChevron() },
                    onClick = { taps++ },
                )
            }
            row {
                ListRow(
                    title = "Appearance",
                    meta = "System",
                    trailing = { ListRowChevron() },
                    onClick = { taps++ },
                )
            }
            row {
                ListRow(
                    title = "Delete all data",
                    titleColor = RovstiTheme.colors.destructive,
                    onClick = { taps++ },
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    meta: String?,
    onClick: () -> Unit,
) {
    val c: RovstiColors = RovstiTheme.colors
    ListRow(
        title = title,
        meta = meta,
        leading = {
            Row(
                Modifier.size(SettingsTile).clip(SettingsTileShape).background(c.accent),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RovstiIcon(icon, null, tint = c.onAccent, size = SettingsGlyph)
            }
        },
        trailing = { ListRowChevron() },
        onClick = onClick,
    )
}

private val SettingsTile = 30.dp
private val SettingsGlyph = 18.dp
private val SettingsTileShape = RoundedCornerShape(8.dp)
