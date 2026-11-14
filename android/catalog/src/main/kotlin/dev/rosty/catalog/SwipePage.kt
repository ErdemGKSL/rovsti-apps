package dev.rosty.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rosty.design.ios.component.IosButton
import dev.rosty.design.ios.component.IosButtonStyle
import dev.rosty.design.ios.component.IosSwipeAction
import dev.rosty.design.ios.component.IosSwipeableRow
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.Spacing

/** E1.29: Convx swipe actions (reveal, full swipe) and the Convx button styles. */
@Composable
internal fun SwipePage() {
    var log by remember { mutableStateOf("Swipe a row left (Delete, Archive) or right (Pin).") }
    val colors = IosTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState(),
            ).padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RostyText("Swipe actions")
        RostyText(log, modifier = Modifier.testTag("swipe-log"))
        Column {
            for (name in listOf("Ada Lovelace", "Grace Hopper", "Alan Turing")) {
                IosSwipeableRow(
                    modifier = Modifier.testTag("row-$name"),
                    leadingActions = listOf(IosSwipeAction("Pin", colors.systemOrange) { log = "Pin $name" }),
                    trailingActions =
                        listOf(
                            IosSwipeAction("Archive", colors.systemBlue) { log = "Archive $name" },
                            IosSwipeAction("Delete", colors.systemRed) { log = "Delete $name" },
                        ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.m)) {
                        IosText(name)
                        IosText("Last message preview", color = colors.secondaryLabel)
                    }
                }
            }
        }
        RostyText("Button styles")
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IosButton("Plain", {}, style = IosButtonStyle.Plain)
            IosButton("Tinted", {}, style = IosButtonStyle.Bordered)
            IosButton("Filled", {}, style = IosButtonStyle.BorderedProminent)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IosButton("Glass", {}, style = IosButtonStyle.Glass)
            IosButton("Prominent", {}, style = IosButtonStyle.GlassProminent)
        }
    }
}
