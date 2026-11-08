package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.GlassIconButton
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

private val People =
    listOf(
        "Ayşe Yılmaz" to "See you at seven",
        "Can Demir" to "Missed call",
        "Deniz Arslan" to "Photo",
        "Elif Kaya" to "Thanks!",
        "Furkan Şahin" to "Incoming call",
        "Gül Aydın" to "On my way",
        "Hakan Öz" to "Voice message",
        "İpek Çelik" to "See you tomorrow",
        "Kemal Aksoy" to "Missed call",
        "Leyla Polat" to "Okay",
        "Mert Koç" to "Photo",
        "Nur Güneş" to "Sent a location",
    )

/**
 * E1.11: long-press menus. Rows lift and open a panel beside them (each row also has a "..." button that must keep
 * its own click); the two contact tiles grow into a preview card with the panel under it, left- and right-aligned.
 */
@Composable
internal fun ContextMenuPage() {
    val controller = LocalOverlay.current
    var last by remember { mutableStateOf("nothing yet") }
    Column(Modifier.fillMaxSize().padding(insetsOnly())) {
        PageHeader("Context menu", "Hold a row or a contact tile; drag the card down to dismiss.")
        RovstiText(
            "Last: $last",
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter).testTag("ctx-status"),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            ContactTile("Ayşe Yılmaz", "+90 532 111 22 33", "ctx-tile-left", Modifier.weight(1f)) { last = it }
            ContactTile("Can Demir", "+90 533 444 55 66", "ctx-tile-right", Modifier.weight(1f)) { last = it }
        }
        LazyColumn(Modifier.weight(1f).testTag("ctx-list")) {
            itemsIndexed(People) { index, (name, secondary) ->
                if (index > 0) RowSeparator(inset = TextStartWithLeading)
                val items =
                    remember(name) {
                        persistentListOf(
                            MenuItem.Action("Message", RovstiIcons.Message, onClick = { last = "Message $name" }),
                            MenuItem.Action("Call", RovstiIcons.Phone, onClick = { last = "Call $name" }),
                            MenuItem.Action("Mute", onClick = { last = "Mute $name" }),
                            MenuItem.Separator,
                            MenuItem.Action("Delete", RovstiIcons.Trash, destructive = true, onClick = {
                                last =
                                    "Delete $name"
                            }),
                        )
                    }
                Box(
                    Modifier
                        .longPressMenu(
                            controller = controller,
                            items = items,
                            style = ContextMenuStyle.Lifted(preview = { PersonRow(name, secondary, {}) }),
                            onTap = { last = "opened $name" },
                        ).testTag("ctx-row-$index"),
                ) {
                    PersonRow(name, secondary) { last = "more $name" }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(
    name: String,
    secondary: String,
    onMore: () -> Unit,
) {
    ListRow(
        title = name,
        secondary = secondary,
        leading = { Avatar(name, size = 44.dp) },
        trailing = {
            GlassIconButton(RovstiIcons.Ellipsis, "More actions for $name", onMore, Modifier.testTag("ctx-more-$name"))
        },
    )
}

@Composable
private fun ContactTile(
    name: String,
    number: String,
    tag: String,
    modifier: Modifier,
    onResult: (String) -> Unit,
) {
    val controller = LocalOverlay.current
    val items =
        remember(name) {
            persistentListOf(
                MenuItem.Action("Message", RovstiIcons.Message, onClick = { onResult("Message $name") }),
                MenuItem.Action("Call", RovstiIcons.Phone, onClick = { onResult("Call $name") }),
                MenuItem.Action("Copy number", onClick = { onResult("Copy $number") }),
                MenuItem.Separator,
                MenuItem.Action(
                    "Delete",
                    RovstiIcons.Trash,
                    destructive = true,
                    onClick = { onResult("Delete $name") },
                ),
            )
        }
    Column(
        modifier
            .longPressMenu(
                controller = controller,
                items = items,
                style = ContextMenuStyle.Card(card = { ContactCard(name, number) }, cardHeight = 216.dp),
                onTap = { onResult("opened $name") },
            ).background(RovstiTheme.colors.fillSecondary, RoundedCornerShape(20.dp))
            .padding(vertical = Spacing.l, horizontal = Spacing.m)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(name, size = 56.dp)
        RovstiText(name, style = RovstiTheme.type.callout, maxLines = 1, modifier = Modifier.padding(top = Spacing.s))
        RovstiText(number, style = RovstiTheme.type.caption1, color = RovstiTheme.colors.labelSecondary, maxLines = 1)
    }
}

/** What the tile grows into. */
@Composable
private fun ContactCard(
    name: String,
    number: String,
) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Avatar(name, size = 88.dp)
        RovstiText(name, style = RovstiTheme.type.title2, maxLines = 1, modifier = Modifier.padding(top = Spacing.m))
        RovstiText(
            number,
            style = RovstiTheme.type.subheadline,
            color = RovstiTheme.colors.labelSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.s))
        RovstiText(
            "Last call today, 10:24",
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelTertiary,
        )
    }
}
