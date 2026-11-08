package dev.rovsti.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.controls.ChipModel
import dev.rovsti.design.controls.ChipRow
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

internal val CatalogChips =
    persistentListOf(
        ChipModel("all", "All", 128),
        ChipModel("unread", "Unread", 4),
        ChipModel("whatsapp", "WhatsApp", 31),
        ChipModel("sms", "SMS", 97),
        ChipModel("favourites", "Favourites"),
        ChipModel("groups", "Groups", 6),
        ChipModel("missed", "Missed", 2),
        ChipModel("voicemail", "Voicemail"),
        ChipModel("archived", "Archived", 12),
        ChipModel("blocked", "Blocked"),
        ChipModel("recent", "Recent"),
        ChipModel("spam", "Spam", 3),
    )

/** Twelve chips in one row: they run off the right edge and scroll, they never wrap onto a second line. */
@Composable
internal fun ChipsPage() {
    var selected by remember { mutableStateOf("unread") }
    Column(Modifier.fillMaxSize().padding(insetsOnly())) {
        PageHeader("Chips", "One non-wrapping row. Selected: $selected")
        ChipRow(CatalogChips, selected, { selected = it }, Modifier.testTag("chip-row"))
        RovstiText(
            "Counts are a separate small label. The row scrolls sideways; the last chip is cut by the screen edge.",
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        )
    }
}
