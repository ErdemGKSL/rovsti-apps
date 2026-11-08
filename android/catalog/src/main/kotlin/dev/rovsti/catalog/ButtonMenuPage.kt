package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

private val DemoItems =
    persistentListOf<MenuItem>(
        MenuItem.Action("Select", RovstiIcons.Checkmark, onClick = {}),
        MenuItem.Action("Sort by", onClick = {}),
        MenuItem.Action("Disabled", enabled = false, onClick = {}),
        MenuItem.Separator,
        MenuItem.Action("Delete all", RovstiIcons.Trash, destructive = true, onClick = {}),
    )

/** E1.10: three "..." triggers, one near the top, one near the bottom, one hard against the trailing edge. */
@Composable
internal fun ButtonMenuPage() {
    val controller = LocalOverlay.current
    Box(Modifier.fillMaxSize().padding(insetsOnly())) {
        Column(Modifier.align(Alignment.TopStart)) {
            PageHeader("Button menu", "Opens on touch-down and grows out of the trigger.")
        }
        Trigger(
            controller,
            "menu-trigger-top",
            Modifier.align(Alignment.TopStart).padding(start = Spacing.gutter, top = 120.dp),
        )
        Trigger(controller, "menu-trigger-bottom", Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.s))
        Trigger(controller, "menu-trigger-edge", Modifier.align(Alignment.CenterEnd).padding(end = Spacing.xs))
        RovstiText(
            "Near the top it opens below, near the bottom above, and at the edge it stays 12 dp inside.",
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelSecondary,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.gutter + Sizes.touch),
        )
    }
}

@Composable
private fun Trigger(
    controller: dev.rovsti.design.overlay.OverlayController,
    tag: String,
    modifier: Modifier,
) {
    val items = remember { DemoItems }
    Box(
        modifier
            .size(Sizes.button)
            .background(RovstiTheme.colors.fillSecondary, CircleShape)
            .menuTrigger(controller, items)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(RovstiIcons.Ellipsis, contentDescription = null)
    }
}
